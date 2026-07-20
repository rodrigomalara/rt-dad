# Pipeline Operations — Day-2

Part of the infra doc set → back to **[INFRASTRUCTURE.md](infrastructure.md)**.

Welcome! This guide covers the day-to-day tasks handled when working with the staging environment. Whether deploying a new feature or troubleshooting an issue, this is the place to start.

## How to Deploy to Staging

Whenever the staging environment needs an update, the deployment is triggered manually. This is done through GitHub Actions, giving full control over when changes go live.

To get started:
1. Go to **Actions** in GitHub.
2. Select the **deploy** workflow.
3. Click **Run workflow**.

A `selector` tag is required. Here is how to choose the right one:
- **Leave it blank:** This is the default. It grabs the absolute newest image available in the container registry (ECR).
- **Use a specific commit (`sha-abc1234`):** If deploying a specific build from the `main` branch.
- **Use a release tag (`vX.Y.Z`):** If deploying a specific, official release.

**What happens behind the scenes?**
When the job is kicked off, the pipeline handles a few complex steps automatically:
1. It securely connects to AWS (using an OIDC role) and finds the exact, immutable image digest for each service. Deployment is done by digest rather than tag to guarantee that a retagged image won't silently change what's running.
2. It temporarily opens the EKS (Elastic Kubernetes Service) cluster's public endpoint just for this deployment. Since GitHub runners have unpredictable IP addresses, the pipeline adds the runner's IP, deploys, and then ensures it locks it back down afterward. If this revert step fails, the allowlist remains open, so be sure to check the job status!
3. It updates the services using Helm (the Kubernetes package manager) and waits for the rollout to finish safely.
4. Finally, it runs a quick check on the anomaly detector to make sure no messages were lost during the update (verifying `anomaly_detector_dead_letters_total` is 0).

## Reverting a Deployment (Rollbacks)

If something goes wrong after a deploy, it can be easily rolled back. The simplest way is to go back to GitHub Actions and re-dispatch the `deploy.yml` workflow with the previous known-good `sha-<7>` or `vX.Y.Z` tag.

Alternatively, if the command line is preferred and cluster access is available, it can be rolled back directly using Helm:

```bash
helm -n rtdad history rtdad
helm -n rtdad rollback rtdad <REVISION>
```

## Connecting to the Cluster

To interact with the cluster from a local machine, the IP address must be included in `WHITELIST_CIDRS`. This protects both the cluster control-plane and the service NodePorts.

Once the IP is allowed, the local Kubernetes config can be updated and the running services can be checked:

```bash
aws eks update-kubeconfig --name rtdad-staging --region us-west-2
kubectl -n rtdad get pods
kubectl -n rtdad logs deploy/rtdad-consumer -f
```

## Reaching the Services

To access the dashboards or management interfaces, it requires the public IP or DNS name of a cluster node:

```bash
kubectl get nodes -o wide
```

Once the node's address is known, the following services can be reached using their designated NodePorts (remember, the IP must be whitelisted):

| Service | Port | What it's for |
| --- | --- | --- |
| Grafana | `:30300` | Viewing the RT-DAD Detection Health dashboard. |
| Consumer actuator | `:30808` | Checking metrics like `anomaly_detector_dead_letters_total`. |
| Prometheus | `:30909` | Querying raw metrics. |
| RabbitMQ mgmt | `:30672` | Checking inbound queue depth and DLQ (Dead Letter Queue) depth. |

## Observing and Triaging

When figuring out what's happening in the system, here are the best places to look:

- **Consumer logs:** Each log line represents a data point. (The exact format can be found in the root `README.md`).
- **Actuator Endpoint (`/actuator/prometheus`):** Keep an eye on `anomaly_detector_dead_letters_total`. It should always be `0`.
- **RabbitMQ Management:** Monitor the inbound queue depth and ensure the DLQ (Dead Letter Queue) is empty (`*_dlq` should be `0`). The fanout publish rate for anomalies can also be observed.
- **Grafana:** The "RT-DAD Detection Health" dashboard is the go-to visual check.

For a deeper dive into the metrics and known observability gaps, check out [observability.md](observability.md).

## Understanding a Failed CI Plan (`infra.yml`)

When a Pull Request is opened that touches the Terraform infrastructure code, GitHub runs a read-only `terraform plan` to show what will change.

- **It's read-only:** The job uses `-lock=false -refresh=false`. It validates the configuration against the last known state but **does not** detect live drift in AWS. (It can't detect drift because the runner IP isn't whitelisted, and the plan role can't read secrets). True drift will only show up when `terraform apply` is run locally.
- **Formatting and Linting:** If the job fails on formatting (`fmt`), validation, or linting (`tflint`), it means there's a problem with the code itself. These need to be fixed in the PR.
- **No applies in CI:** `terraform apply` is never run in CI for security reasons. Applies are always done locally. (For details, see [setup.md](setup.md)).

## Common Pitfalls and Things to Avoid

Here are a few "gotchas" to keep in mind to avoid unexpected trouble:

- **Keep the consumer at a single instance:** Please don't scale the consumer to multiple instances. It relies on a single in-memory `RollingWindow`, and if two instances are running, they will each see a different part of the data stream, which leads to invalid statistics. (Read more on this in the root `README.md`).
- **Watch out for Bitnami images:** The `docker.io/bitnami` tags were deleted in August 2025. Be sure to use the `bitnamilegacy` mirror for RabbitMQ and other add-on charts.
- **Keep the IP allowlist updated:** The `whitelist_cidrs` variable controls access to both the EKS control-plane and the NodePorts. If the office or VPN IP changes, access will be locked out. The GitHub Actions Variable needs to be updated and changes re-applied.
- **Don't manually edit environment files:** Avoid editing the `deploy/helm/rtdad/charts/*/env` files by hand, as this can break the deployment process.
- **Expect occasional brief outages:** The cluster currently uses a single spot node in a single Availability Zone (AZ) without High Availability (HA). If AWS reclaims the spot instance, there will be a brief outage. This is intentional, as this is the staging/experimental cluster.
- **Check for an open endpoint after a failed deploy:** If the `deploy.yml` workflow fails during the revert step, the EKS public allowlist might still contain the GitHub runner's IP. It needs to be restored manually by running `aws eks update-cluster-config`.
