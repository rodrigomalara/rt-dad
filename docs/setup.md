# Setup — Standing Up the Infrastructure

Part of the infra doc set → back to **[INFRASTRUCTURE.md](infrastructure.md)**.

Welcome to the setup guide! This document explains *why* the infrastructure is set up this way and the *order* in which things should be done. If looking for step-by-step commands to copy and paste, they can be found linked in the runbooks.

## Understanding the Execution Model

As mentioned in the [Infrastructure overview](infrastructure.md), a hybrid **plan-in-CI / apply-local** model is used. 

What does this mean? The very first time the environment is set up, `terraform apply` must be run locally using administrator credentials. It has to be done this way because that first run actually creates the security roles (OIDC) that GitHub Actions needs to run future CI pipelines. By doing the first step manually, the chicken-and-egg problem is solved!

---

## Part 1 — Building the AWS Infrastructure

### 1a. One-Time AWS Bootstrap

Before Terraform can do its job, a few prerequisites must be set up in AWS that Terraform can't create for itself:

1. **S3 state bucket:** This is where Terraform will store its state securely. It must be versioned, encrypted (SSE-S3), and blocked from public access.
2. **State locking:** Nothing extra needs to be done here! As long as Terraform 1.10 or higher is used, native S3 locking is utilized.
3. **GitHub OIDC provider:** This is an account-wide setting. It is set up manually so it survives just in case a `terraform destroy` is accidentally run.
4. **Local admin credentials:** SSO is preferred instead of static keys. These are only needed for running `apply`.

To get these set up, simply run the idempotent script `./scripts/aws-bootstrap.sh` or follow the checklist in **[docs/runbooks/aws-bootstrap-checklist.md](runbooks/aws-bootstrap-checklist.md)**.

### 1b. The First Apply (Done in Two Waves)

When setting up the cluster from scratch, it must be done in two waves. The cluster itself must exist before its add-ons can be installed (like RabbitMQ or Prometheus). This is because the Terraform modules that configure those add-ons need to talk to a live Kubernetes API endpoint.

Here is how it is done:

```bash
cd infra/envs/staging
terraform init -reconfigure \
  -backend-config="bucket=$BUCKET" -backend-config="region=$REGION"

# Wave 1 — Build the base AWS network and the EKS cluster
terraform apply -target=module.vpc -target=module.eks -target=module.ecr \
  -target=module.github_oidc -target=module.secrets -var-file=staging.tfvars

# Wave 2 — Install the cluster add-ons
terraform apply -var-file=staging.tfvars
```

*Note on `staging.tfvars`:* This file is not committed to version control. While the values aren't highly sensitive, they are generated per environment. One can look at `staging.tfvars.example` to see what it looks like. The following must be provided: `region`, `whitelist_cidrs`, and `oidc_provider_arn`.

### 1c. Capturing the Outputs

Once the apply finishes, Terraform will output some important information. These outputs must be saved for Part 2:

```bash
terraform output   
# Make sure to grab: cluster_name, ecr_repository_urls,
# ci_plan_role_arn, ci_deploy_role_arn, ci_publish_role_arn
```

---

## Part 2 — Wiring Up the CI/CD Pipeline

Now that AWS is ready, GitHub needs to be configured to talk to it securely.

### 2a. GitHub Bootstrap

Take the ARNs and configuration values obtained from Terraform and add them as **GitHub Actions Variables**. These can be scoped to the repository or to a specific environment (like `staging`), but should never be committed directly into the Terraform HCL files!

| Variable | Where to get it |
| --- | --- |
| `AWS_REGION` | Usually `us-west-2` |
| `TF_STATE_BUCKET` | The name of the created bootstrap bucket |
| `PLAN_ROLE_ARN` | `terraform output ci_plan_role_arn` |
| `DEPLOY_ROLE_ARN` | `terraform output ci_deploy_role_arn` |
| `PUBLISH_ROLE_ARN` | `terraform output ci_publish_role_arn` |
| `EKS_CLUSTER_NAME` | Usually `rtdad-staging` |
| `WHITELIST_CIDRS` | Formatted as an HCL list, e.g. `["203.0.113.10/32"]` |
| `OIDC_PROVIDER_ARN` | The ARN from the bootstrap step |

To automate this, one can run `./scripts/github-bootstrap.sh` or follow the manual checklist in **[docs/runbooks/github-bootstrap-checklist.md](runbooks/github-bootstrap-checklist.md)**.

### 2b. GitHub Environments

Two environments are defined in GitHub:
- `staging` — This is an automatic environment used by the `deploy.yml` workflow.
- `production` — (Coming in the future!) This will require manual approval from a reviewer.

### 2c. What Each GitHub Workflow Does

Here is a quick reference for the workflows that run in GitHub Actions:

| Workflow | When it triggers | Which role it uses | What it does |
| --- | --- | --- | --- |
| `ci.yml` | On PRs and pushes to `main` | Uses the publish role (on push) | Runs Maven verify, Spotless checks, and a Docker Compose smoke test. If it's a push to `main`, it builds and pushes images to ECR (tagged as `sha-<7>`) and runs an SBOM/Grype security scan. |
| `infra.yml` | On PRs that touch the `infra/` folder | Uses the plan role | Runs formatting (`fmt`), validation, and linting (`tflint`), then generates a read-only `terraform plan`. |
| `release.yml` | When a tag like `v*.*.*` is created | Uses the publish role | Retags the existing ECR image as `:vX.Y.Z` and publishes the `rtdad-common` library to GitHub Packages. |
| `deploy.yml` | Manually via `workflow_dispatch` | Uses the deploy role | Runs `helm upgrade` to safely deploy to the EKS cluster by image digest. |

**A note on required checks:**
`ci.yml` and `infra.yml` are set up as required status checks in GitHub. This means they **always run**. However, to save time, they skip the heavy lifting if the PR only touches docs or workflow files, returning a green checkmark in seconds. Avoid putting a top-level `paths:` filter on these files in GitHub, otherwise, the required check would sit waiting forever!

## Verifying the Setup

To make sure everything works:
1. Open a trivial PR that modifies a file in the `infra/` directory. This should cause `infra.yml` post a real Terraform plan.
2. Merge it to the `main` branch. This should cause `ci.yml` publish a new `sha-<7>` image to ECR.
3. Finally, dispatch the `deploy.yml` workflow manually to push the changes live. Check out [pipeline-operations.md](pipeline-operations.md) for more details on day-to-day operations.
