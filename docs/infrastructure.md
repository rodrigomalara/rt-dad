# Infrastructure — Start Here

Welcome to the RT-DAD infrastructure! This document is the orientation map. It provides a high-level understanding of how the systems are put together. 

Once the mental model from this page is clear, specific guides can be found depending on the required task:

- **[setup.md](setup.md)** — Use this when standing up the infrastructure and wiring the CI/CD pipeline from scratch.
- **[pipeline-operations.md](pipeline-operations.md)** — Use this for day-to-day tasks: deploying, rolling back, accessing the cluster, checking observability, and avoiding common pitfalls.
- **[observability.md](observability.md)** — Use this to understand what metrics are measured, where to find logs and dashboards, and what observability gaps still exist.

If looking for step-by-step commands to bootstrap the environment, they can be found in the `docs/runbooks/` folder. This page focuses on the "big picture."

## Cloud Topology

Currently, a single environment is run: **staging**. It lives in the AWS **us-west-2** region, under account **007374813645**.

Here is a visual summary of how code flows from GitHub into AWS:

```text
 GitHub PR ── infra.yml ─────► terraform plan (read-only, OIDC plan role)
 push main ── ci.yml ────────► build+test ─► publish images ──► ECR (sha-<7>)
 tag v*.*.* ─ release.yml ───► retag ECR :vX.Y.Z + publish rtdad-common
 manual ───── deploy.yml ────► helm upgrade ──► EKS

                                  AWS (us-west-2, acct 007374813645)
   ┌───────────────────────────────────────────────────────────────────┐
   │  ECR: rtdad-producer / rtdad-consumer                               │
   │  VPC 10.20.0.0/16 (2 AZ, public+private subnets)                    │
   │   └─ EKS 1.31  ── single SPOT t3.medium node (min=max=1, 1 AZ)      │
   │        namespace rtdad:  producer + consumer (Helm release "rtdad") │
   │        add-ons: RabbitMQ, Prometheus/Grafana, External Secrets,     │
   │                 EBS CSI (gp3 default StorageClass)                   │
   │  Secrets Manager  ◄── External Secrets Operator (IRSA)              │
   │  S3: terraform state (rtdad/staging/terraform.tfstate)              │
   └───────────────────────────────────────────────────────────────────┘
     NodePort access (IP-whitelisted): grafana :30300, actuator :30808,
                                       prometheus :30909, rabbitmq :30672
```

## How Terraform is Organized

The Terraform code is structured around a root module at `infra/envs/staging/`, which pieces together six smaller, reusable modules located in `infra/modules/`:

| Module | What it Builds |
| --- | --- |
| `vpc` | The VPC (`10.20.0.0/16`) spanning 2 Availability Zones, with both public and private subnets. |
| `eks` | The EKS 1.31 cluster. It uses a single spot `t3.medium` node group, an IP-whitelisted NodePort Security Group, and grants the deploy-role access to edit the `rtdad` namespace. |
| `ecr` | The container registries for the `rtdad-producer` and `rtdad-consumer` services. |
| `addons` | Essential cluster add-ons: EBS CSI (for gp3 volumes), RabbitMQ, Prometheus/Grafana, and the External Secrets Operator. |
| `secrets` | The AWS Secrets Manager secret and the policy that allows the cluster to read it. |
| `github-oidc` | Three OIDC roles trusted by the GitHub repository: one for planning, one for deploying, and one for publishing. |

**Where is the state?**
Terraform state is stored in S3 (configured in `backend.tf` under the key `rtdad/staging/terraform.tfstate`). Terraform's native S3 locking is relied upon (`use_lockfile = true`), meaning a separate DynamoDB table is not needed. Note that the backend `bucket` and `region` are passed dynamically during `terraform init` and are never committed to version control.

## Approach: Plan in CI, Apply Locally

One non-obvious decision made is how Terraform is run. Since the GitHub repository is public, it was chosen not to trust it with a role capable of making administrative changes to the AWS account.

Because of this:
- **`terraform apply` always runs locally** on an operator's machine, using local admin SSO credentials.
- **CI only runs read-only `terraform plan`** checks (via `infra.yml` on pull requests modifying the `infra/` folder).

This means when setting up the environment for the first time, the initial apply must be run manually. That first apply creates the CI OIDC roles that the pipeline will use later. For more details on this bootstrap process, check out [setup.md](setup.md).

## Managing Identities and Credentials

Three distinct sets of credentials are used, kept strictly separate by design. It is assumed that every log line produced by GitHub Actions is visible to the world, so security relies on OIDC trust conditions and manual environment approvals, rather than trying to hide account IDs or regions.

| Credential | Who Uses It | Mechanism | What It Can Do |
| --- | --- | --- | --- |
| Dev admin | Operator laptop | SSO / local admin | Can run full `terraform apply` locally. |
| Pipeline plan | GitHub Actions | OIDC (no keys stored) | Read-only access to generate plans. |
| Pipeline deploy | GitHub Actions | OIDC (no keys stored) | Can push to ECR and deploy to EKS (restricted to the `rtdad` namespace). |
| Pipeline publish | GitHub Actions | OIDC (no keys stored) | Can push build and release images to ECR. |

## Quick File Glossary

If wondering where everything lives, here's a quick map:

- `infra/` — All Terraform code. The root environment is in `envs/staging/`, and reusable components are in `modules/`.
- `.github/workflows/` — The CI/CD pipelines: `ci.yml`, `infra.yml`, `deploy.yml`, and `release.yml`.
- `deploy/helm/rtdad/` — The Helm chart used for deployments. Look at `values-staging.yaml` for environment overrides.
- `docs/runbooks/` — Checklists for one-time manual bootstrapping tasks (like setting up AWS and GitHub).
- `scripts/` — Helper scripts like `aws-bootstrap.sh`, `github-bootstrap.sh`, and `ci-smoke.sh`.
