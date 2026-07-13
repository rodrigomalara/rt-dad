# RT-DAD — GitHub Bootstrap Checklist (Manual Repo Settings)

**Date:** 2026-07-12
**Status:** Prerequisite for the CI/CD pipeline
**Scope:** One-time manual settings in the GitHub repository that the pipeline
depends on. Pairs with `2026-07-11-aws-bootstrap-checklist.md`.

## Context

The pipeline authenticates to AWS via OIDC (no stored keys) and deploys with
`helm upgrade`. GitHub must be configured with branch protection, the two
deployment environments, and the Actions Variables that carry the
Terraform-produced role ARNs. No GitHub **secret** is required — OIDC removes
static AWS keys, and the release job uses the built-in `GITHUB_TOKEN`.

Repo: `rodrigomalara/rt-dad` (public).

## Ordering (important)

These runbooks interleave:

1. **AWS bootstrap** steps 1–3 (state bucket, locking, OIDC provider) + local
   admin creds.
2. **Local `terraform apply` (staging)** — creates the CI plan/deploy roles and
   outputs their ARNs.
3. **This runbook** — environments must exist before per-environment Variables
   and before the OIDC `sub` can be scoped to `environment:*`; the role ARNs
   come from step 2's `terraform output`.

## Step 1 — Actions enabled + default permissions

Settings → **Actions → General**

- [ ] Actions enabled (GitHub-hosted Ubuntu runners).
- [ ] Workflow permissions: **Read repository contents** (least privilege).
      Jobs elevate per-job (`id-token: write`, `packages: write`) in workflow
      YAML, not globally.
- [ ] Fork PR runs: require approval for first-time contributors (limits public
      fork exposure). Credentialed jobs never run on `pull_request` from forks.

## Step 2 — Branch protection on `main`

Settings → **Branches → Add rule** (or Rulesets)

- [ ] Require a pull request before merging.
- [ ] Require status checks to pass: `build-test` (app CI) and `plan` (infra,
      when `infra/**` changed).
- [ ] Require branches up to date before merging.
- [ ] (Optional) require linear history.

## Step 3 — Environments

Settings → **Environments**

- [ ] `staging` — no required reviewers (auto-deploys after publish).
- [ ] `production` — **Required reviewers: yourself**; optional wait timer.
- [ ] (Optional) restrict each environment's deployment branches to `main` /
      tags.

## Step 4 — Actions Variables (no secrets)

Settings → **Secrets and variables → Actions → Variables**. Use
**environment-scoped** variables where the value differs per environment.

Repo-level:

- [ ] `AWS_REGION` = `<REGION>`
- [ ] `TF_STATE_BUCKET` = state bucket name — pinned in `scripts/.env` (carries a
      region + deployment suffix, e.g. `rt-dad-tfstate-<ACCOUNT_ID>-<REGION>-<suffix>`,
      not the bare account-id formula)
- [ ] `PLAN_ROLE_ARN` = read-only role ARN (`terraform output ci_plan_role_arn`)
- [ ] `PUBLISH_ROLE_ARN` = CI publish role ARN (`terraform output ci_publish_role_arn`)
- [ ] `WHITELIST_CIDRS` = source CIDRs allowed to reach NodePorts / admin
      surfaces — HCL list literal, e.g. `["x.x.x.x/32"]` (from `scripts/.env`)
- [ ] `OIDC_PROVIDER_ARN` = GitHub Actions OIDC provider ARN (env-independent,
      one per account: `arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com`)

Environment-scoped (`staging` / `production`):

- [ ] `DEPLOY_ROLE_ARN` = env deploy role ARN (`terraform output`)
- [ ] `EKS_CLUSTER_NAME` = env cluster name (`terraform output`)

> All low-sensitivity (ARNs/region/bucket). Fine as Variables; a public repo's
> logs are assumed world-readable, and security rests on the OIDC trust
> condition, not on hiding these.

## Step 5 — GitHub Packages (release artifact repo)

- [ ] No setup needed to publish — the tag-release job publishes `rtdad-common`
      to the GitHub Packages Maven registry using `GITHUB_TOKEN` +
      `packages: write` (job-scoped).
- [ ] After the first publish, set the package visibility to **public** (matches
      the public repo) under the repo's Packages tab if desired.

## Step 6 — (Optional) tag protection

- [ ] Protect `v*` tags (Rulesets → Tag) so only intended releases trigger the
      release/publish path.

## Cross-references

- AWS side: `2026-07-11-aws-bootstrap-checklist.md`
- Pipeline design: `../superpowers/specs/2026-07-11-rtdad-delivery-pipeline-design.md`
