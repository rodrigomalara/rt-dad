# Infrastructure — Start Here

Orientation map for RT-DAD's infrastructure. Read this first, then jump to the
task-specific doc:

- **[setup.md](setup.md)** — stand up infra + wire the CI/CD pipeline from zero.
- **[pipeline-operations.md](pipeline-operations.md)** — day-2: deploy, rollback, access, observability, footguns.
- **[observability.md](observability.md)** — what RT-DAD measures, the signal inventory (metrics/logs/dashboards), and gaps.

Step-by-step bootstrap commands live in the runbooks
(`docs/runbooks/`); these docs carry the **mental model** and link out to them.

## Topology

One environment today: **staging**, region **us-west-2**, account **007374813645**.

```
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

## Terraform layout

Root module `infra/envs/staging/` composes six modules in `infra/modules/`:

| Module | Builds |
| --- | --- |
| `vpc` | VPC `10.20.0.0/16`, 2 AZs, public + private subnets |
| `eks` | EKS 1.31, single spot `t3.medium` node group, IP-whitelisted NodePort SG, deploy-role access entry (edit on `rtdad` ns) |
| `ecr` | `rtdad-producer` + `rtdad-consumer` repositories |
| `addons` | EBS CSI (gp3), RabbitMQ, Prometheus/Grafana, External Secrets Operator |
| `secrets` | Secrets Manager secret + read policy |
| `github-oidc` | Three repo-trusted OIDC roles: plan / deploy / publish |

State in S3 (`backend.tf`, key `rtdad/staging/terraform.tfstate`), **S3-native
locking** (`use_lockfile = true`, no DynamoDB). Backend `bucket`/`region` are
passed at `init` time, never committed.

## The one non-obvious decision: hybrid plan-in-CI / apply-local

No admin-capable role is trusted by this **public** repo. Therefore:

- **`terraform apply` runs locally** on the operator's laptop under SSO admin.
- **CI only runs read-only `terraform plan`** (`infra.yml`, on `infra/**` PRs).

The very first local apply *creates* the CI OIDC roles — CI cannot bootstrap
itself. See [setup.md](setup.md).

## Identity (three distinct credentials, kept separate by design)

| Credential | Principal | Mechanism | Scope |
| --- | --- | --- | --- |
| Dev admin | operator laptop | SSO / local admin | full `terraform apply` |
| Pipeline plan | GitHub Actions | OIDC, no keys | read-only |
| Pipeline deploy | GitHub Actions | OIDC, no keys | ECR push + EKS deploy (`rtdad` ns) |
| Pipeline publish | GitHub Actions | OIDC, no keys | ECR push (build/release images) |

Assume every Actions log line is world-readable. Security rests on OIDC trust
conditions + environment approval, **not** on hiding account ID / region / ARN.

## Glossary of files

- `infra/` — Terraform (root `envs/staging`, reusable `modules/`).
- `.github/workflows/` — `ci.yml`, `infra.yml`, `deploy.yml`, `release.yml`.
- `deploy/helm/rtdad/` — Helm chart; `values-staging.yaml` overrides.
- `docs/runbooks/` — one-time bootstrap checklists (AWS + GitHub).
- `scripts/` — `aws-bootstrap.sh`, `github-bootstrap.sh`, `ci-smoke.sh`.
