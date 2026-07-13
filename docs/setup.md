# Setup — Stand Up Infra + Wire the Pipeline

Part of the infra doc set → back to **[INFRASTRUCTURE.md](infrastructure.md)**.

This is the **why + order**. Copy-paste commands live in the runbooks; follow
them where linked.

## Execution model (read first)

Hybrid **plan-in-CI / apply-local** (see
[INFRASTRUCTURE.md](infrastructure.md#the-one-non-obvious-decision-hybrid-plan-in-ci--apply-local)):
the operator runs `terraform apply` locally under admin; that first apply
creates the OIDC roles CI later uses. Chicken-and-egg is resolved by doing the
first apply by hand.

## Part 1 — Stand up the infrastructure

### 1a. AWS bootstrap (one-time, manual)

Prerequisites the first apply needs that Terraform can't create for itself:

1. **S3 state bucket** — versioned, SSE-S3, public access blocked.
2. **State locking** — nothing to do (Terraform ≥ 1.10 S3-native locking).
3. **Account-wide GitHub OIDC provider** — a per-account singleton, kept by hand
   so it survives an accidental `terraform destroy`.
4. **Local admin creds** — SSO preferred over static keys; used for apply only.

→ Run `./scripts/aws-bootstrap.sh` (idempotent) or follow
**[docs/runbooks/aws-bootstrap-checklist.md](runbooks/aws-bootstrap-checklist.md)**.

### 1b. First apply (two waves)

The cluster must exist before its add-ons (the `addons` module talks to the
Kubernetes/Helm providers, which need a live API endpoint):

```bash
cd infra/envs/staging
terraform init -reconfigure \
  -backend-config="bucket=$BUCKET" -backend-config="region=$REGION"
# wave 1 — base AWS + cluster
terraform apply -target=module.vpc -target=module.eks -target=module.ecr \
  -target=module.github_oidc -target=module.secrets -var-file=staging.tfvars
# wave 2 — cluster add-ons
terraform apply -var-file=staging.tfvars
```

`staging.tfvars` is **not committed** (values are low-sensitivity but generated,
not stored). See `staging.tfvars.example` for the shape; required inputs:
`region`, `whitelist_cidrs`, `oidc_provider_arn`.

### 1c. Capture the outputs

After apply, record for Part 2:

```bash
terraform output   # cluster_name, ecr_repository_urls,
                   # ci_plan_role_arn, ci_deploy_role_arn, ci_publish_role_arn
```

## Part 2 — Wire up the CI/CD pipeline

### 2a. GitHub bootstrap

Store the Terraform-output ARNs + config as **GitHub Actions Variables** (repo or
environment scope — never committed to HCL):

| Variable | Source |
| --- | --- |
| `AWS_REGION` | `us-west-2` |
| `TF_STATE_BUCKET` | bootstrap bucket name |
| `PLAN_ROLE_ARN` | `terraform output ci_plan_role_arn` |
| `DEPLOY_ROLE_ARN` | `terraform output ci_deploy_role_arn` |
| `PUBLISH_ROLE_ARN` | `terraform output ci_publish_role_arn` |
| `EKS_CLUSTER_NAME` | `rtdad-staging` |
| `WHITELIST_CIDRS` | HCL list, e.g. `["203.0.113.10/32"]` |
| `OIDC_PROVIDER_ARN` | bootstrap OIDC provider ARN |

→ Run `./scripts/github-bootstrap.sh` or follow
**[docs/runbooks/github-bootstrap-checklist.md](runbooks/github-bootstrap-checklist.md)**.

### 2b. GitHub environments

- `staging` — auto (used by `deploy.yml`).
- `production` — reviewer-gated (future).

### 2c. What each workflow does

| Workflow | Trigger | Role | Effect |
| --- | --- | --- | --- |
| `ci.yml` | PR + push `main` | build-test; publish (push only) | Maven verify + Spotless + compose smoke; on push, build & push images to ECR as `sha-<7>` + SBOM/Grype scan |
| `infra.yml` | PR | plan role | fmt/validate/tflint + read-only `terraform plan` on `infra/**` |
| `release.yml` | tag `v*.*.*` | publish role | retag ECR image `:vX.Y.Z` + publish `rtdad-common` to GitHub Packages |
| `deploy.yml` | manual `workflow_dispatch` | deploy role | `helm upgrade` to EKS by digest |

**Required-check pattern:** `ci.yml` and `infra.yml` are required status checks,
so they **always run** but gate their heavy steps behind a `paths-filter` — a
docs/workflow-only PR reports green in seconds without a real build or AWS
round-trip. Do not add a top-level `paths:` filter (it would leave the required
check waiting forever).

## Verify setup works

1. Open a trivial `infra/**` PR → `infra.yml` posts a real plan.
2. Merge to `main` → `ci.yml` publishes `sha-<7>` images to ECR.
3. Dispatch `deploy.yml` → see [pipeline-operations.md](pipeline-operations.md).
