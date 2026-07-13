# RT-DAD — AWS Bootstrap Checklist (Console or CLI)

**Date:** 2026-07-11 (CLI commands added 2026-07-12)
**Status:** Prerequisite for Terraform provisioning
**Scope:** One-time manual steps — via the AWS Web Console **or** the AWS CLI —
that unblock the OIDC-authenticated GitHub Actions Terraform pipeline.

## Context

The app pipeline authenticates to AWS via GitHub OIDC (no static keys). Infra is
provisioned by **local `terraform apply`** (hybrid model — CI only runs read-only
`plan`), with state in S3. The manual bootstrap below is the minimum needed
before that first local apply, which then creates the CI OIDC roles and all
infrastructure.

Staging (the first environment) builds: VPC, EKS (managed spot node group),
ECR, in-cluster RabbitMQ, in-cluster Prometheus/Grafana, Secrets Manager, and
External Secrets Operator.

Assume every GitHub Actions log line is world-readable. Security rests on the
OIDC trust condition + environment approval, not on hiding account ID / region /
ARN.

## Scripted path (recommended)

`scripts/aws-bootstrap.sh` automates Steps 1–3 idempotently via the AWS CLI —
the same pattern `scripts/github-bootstrap.sh` uses for the GitHub side. Both
scripts source `scripts/.env` for the shared static inputs (`AWS_REGION`,
`ACCOUNT_ID`, `TF_STATE_BUCKET`), so the state bucket name is defined once.

```bash
# Admin creds active (aws sts get-caller-identity succeeds), then:
./scripts/aws-bootstrap.sh
```

What it does:

- **Step 4 preflight** — verifies the active identity is the target account's
  admin. It never creates or modifies credentials (dev-admin stays SSO/manual by
  design — see Step 4).
- **Step 1** — creates the S3 state bucket if absent, then enforces versioning,
  SSE-S3 encryption, and full public-access-block (safe to re-run).
- **Step 2** — no-op: Terraform ≥ 1.10 uses S3-native locking, no DynamoDB.
- **Step 3** — creates the account-wide GitHub Actions OIDC provider only if it
  doesn't already exist.

Safe to re-run. The manual Console/CLI steps below remain the reference for what
each command does and for accounts where you'd rather click through.

## Prerequisites

- [ ] AWS account exists and you have admin access (console or CLI).
- [ ] Target region: `___`
- [ ] Account ID: `___`
- [ ] GitHub owner/repo slug: `rodrigomalara/rt-dad`

> The CLI commands below assume admin credentials are active
> (`aws sts get-caller-identity` succeeds). Set `REGION` / `ACCOUNT_ID` /
> `BUCKET` once and reuse:
> ```bash
> REGION=us-west-2
> ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
> BUCKET=rt-dad-tfstate-${ACCOUNT_ID}-${REGION}-an   # this deployment's bucket
> ```

## Step 1 — S3 bucket for Terraform state

**Console:** S3 → Create bucket → Block all public access **ON**, Versioning
**Enable**, Default encryption **SSE-S3**.

**CLI:**
```bash
# us-west-2 (and every region except us-east-1) REQUIRES LocationConstraint.
aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
  --create-bucket-configuration LocationConstraint="$REGION"

aws s3api put-bucket-versioning --bucket "$BUCKET" \
  --versioning-configuration Status=Enabled

aws s3api put-bucket-encryption --bucket "$BUCKET" \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'

aws s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
```

- [ ] Bucket created, versioned, encrypted, public access blocked.
- [ ] Record the exact bucket name (it feeds `terraform init -backend-config`):
      `rt-dad-tfstate-<ACCOUNT_ID>-<REGION>`

## Step 2 — State locking

- [ ] **Terraform ≥ 1.10:** nothing to do — S3-native locking
      (`use_lockfile = true`, already in `backend.tf`). No DynamoDB table.
- [ ] **Older Terraform only:** create a DynamoDB table `rtdad-tflock`, partition
      key `LockID` (String), on-demand.

## Step 3 — IAM OIDC identity provider (one-time, account-wide)

**Console:** IAM → Identity providers → Add provider → OpenID Connect →
URL `https://token.actions.githubusercontent.com`, Audience `sts.amazonaws.com`.

**CLI:**
```bash
# Check first — the provider is a per-account singleton for this URL.
aws iam list-open-id-connect-providers

# Create only if absent:
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --query OpenIDConnectProviderArn --output text
```

- [ ] Provider exists. ARN (feeds `oidc_provider_arn` in `staging.tfvars`):
      `arn:aws:iam::ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com`

> Keep this provider by hand even though Terraform could own it: a manual
> provider survives an accidental `terraform destroy` and is the anchor the
> Terraform-managed CI roles trust.

## Step 4 — Local admin credentials for the first apply

**Execution model: hybrid (plan-in-CI, apply-local).** No admin-capable role is
trusted by this public repo. `terraform apply` runs locally under your admin
identity; the CI roles are created *by* that apply, not by hand.

Three distinct credentials exist by design — keep them separate:

| Credential | Principal | Mechanism | Scope |
| --- | --- | --- | --- |
| **Dev admin** | you, on your laptop | local admin (IAM Identity Center SSO preferred over a static access key) | full `terraform apply` |
| **Pipeline deploy** | GitHub Actions | OIDC, no stored keys | ECR push + EKS deploy only |
| **Pipeline plan** | GitHub Actions | OIDC, no stored keys | read-only |

- [ ] `aws sts get-caller-identity` returns your admin identity.
- [ ] Do **not** create a repo-trusted `AdministratorAccess` OIDC role.
- [ ] The pipeline deploy/plan roles are Terraform-managed (below), never the
      dev admin credential.

Terraform's `github-oidc` module creates two narrow, repo-trusted OIDC roles on
the first local apply:

- **CI plan role** — read-only (`ReadOnlyAccess`), assumed by `infra.yml` to run
  `terraform plan` on PRs. Safe to expose on a public repo.
- **App deploy role** — narrow ECR push + `eks:DescribeCluster` + EKS access
  entry, assumed by the app deploy job. Environment-scoped trust.

## After the bootstrap

1. First `terraform apply` runs **locally** with admin creds (bootstraps the
   OIDC roles + all infra). The staging design applies in two waves (cluster
   first, then add-ons):
   ```bash
   cd infra/envs/staging
   terraform init -reconfigure \
     -backend-config="bucket=$BUCKET" \
     -backend-config="region=$REGION"
   # wave 1 — base AWS + cluster
   terraform apply -target=module.vpc -target=module.eks -target=module.ecr \
     -target=module.github_oidc -target=module.secrets -var-file=staging.tfvars
   # wave 2 — cluster add-ons
   terraform apply -var-file=staging.tfvars
   ```
   (The backend `key = rtdad/staging/terraform.tfstate` is fixed in `backend.tf`.)
2. Store the Terraform-output **plan role** / **deploy role** ARNs + bucket name
   as **GitHub Actions Variables** (repo/environment scope), not committed to HCL.
3. Create GitHub **environments** `staging` (auto) and `production`
   (reviewer-gated) — see `github-bootstrap-checklist.md`.
4. Thereafter: `infra.yml` runs fmt/validate/tflint + read-only `plan` on
   `infra/**` PRs; `apply` stays local.

## Hardening follow-ups (post-bootstrap, Terraform-managed)

- [ ] Scope each OIDC role's trust `sub`: plan role to
      `repo:rodrigomalara/rt-dad:pull_request`; deploy role to
      `repo:rodrigomalara/rt-dad:environment:production` / `:staging`.
- [ ] Keep the plan role strictly read-only; never grant it write/apply.
- [ ] Confirm S3 state bucket: encrypted, versioned, no public access
      (real secrets live in state, never in logs).
