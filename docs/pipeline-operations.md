# Pipeline Operations — Day-2

Part of the infra doc set → back to **[INFRASTRUCTURE.md](infrastructure.md)**.

Routine tasks against a running staging environment.

## Deploy

Deploy is **manual** — `deploy.yml` via `workflow_dispatch`:

```
Actions ▸ deploy ▸ Run workflow ▸ selector = <tag>   (blank = newest in ECR)
```

Selector semantics:
- **blank** — newest image by push time, resolved per repo.
- `sha-abc1234` — a specific CI build (from a `main` push).
- `vX.Y.Z` — a released tag (from `release.yml`).

What the job does, in order:
1. Assume deploy role (OIDC), resolve each service's **image digest** in ECR.
2. **Open the EKS endpoint** — the control-plane public endpoint is locked to a
   static `/32` allowlist; a GitHub runner egresses from an unpredictable IP, so
   the job adds `runner-ip/32`, deploys, then **reverts unconditionally**
   (`if: always()`). A failed revert leaves the allowlist wide — check the job.
3. `helm upgrade --install rtdad` pinned to the resolved digests, `--wait`.
4. Rollout status + **post-deploy actuator check**: fails if
   `anomaly_detector_dead_letters_total > 0`.

Images are deployed **by digest, not tag** — immutable, so a retagged image
can't silently change what's running.

## Roll back

Re-dispatch `deploy.yml` with the previous good `sha-<7>` or `vX.Y.Z`. Or, with
cluster access:

```bash
helm -n rtdad history rtdad
helm -n rtdad rollback rtdad <REVISION>
```

## Access the cluster

Your IP must be in `WHITELIST_CIDRS` (control-plane) and the NodePort SG.

```bash
aws eks update-kubeconfig --name rtdad-staging --region us-west-2
kubectl -n rtdad get pods
kubectl -n rtdad logs deploy/rtdad-consumer -f
```

## Reach the services (NodePort, IP-whitelisted)

```bash
kubectl get nodes -o wide      # take a node's public DNS/IP, then:
```

| Service | Port |
| --- | --- |
| Grafana | `:30300` |
| Consumer actuator | `:30808` |
| Prometheus | `:30909` |
| RabbitMQ mgmt | `:30672` |

## Observe / triage

- **Consumer logs** — one line per data point (format in root `README.md`).
- **Actuator** — `/actuator/prometheus`; watch
  `anomaly_detector_dead_letters_total` (should stay `0`).
- **RabbitMQ mgmt** — inbound queue depth, DLQ depth (`*_dlq` should be `0`),
  anomalies fanout publish rate.
- **Grafana** — RT-DAD Detection Health dashboard.
- Signal reference & known gaps: [observability.md](observability.md).

## Reading a failed CI plan (`infra.yml`)

- Runs read-only: `-lock=false -refresh=false`. It validates config diffs
  against last state; it does **not** detect live drift (runner IP isn't
  whitelisted, plan role can't read secrets). Drift shows up at local `apply`.
- fmt / validate / tflint failures are config problems — fix in the PR.
- `apply` is never in CI; it stays local (see [setup.md](setup.md)).

## Footguns

- **Never scale the consumer.** Single in-memory `RollingWindow`, listener pinned
  `concurrency=1`. Two instances → each sees a disjoint stream → invalid
  statistics. Rationale in root `README.md`.
- **Bitnami images** — `docker.io/bitnami` tags were deleted Aug 2025; use the
  `bitnamilegacy` mirror for RabbitMQ / add-on charts.
- **`whitelist_cidrs`** gates both the control-plane endpoint *and* NodePort
  access. A changed office/VPN IP locks you out of both — update the Actions
  Variable and re-apply.
- **Never edit `deploy/helm/rtdad/charts/*/env`** files by hand.
- **Single spot node, single AZ** — no HA. A spot reclaim = brief outage; this is
  a staging/experiment cluster by design.
- **Deploy left the endpoint open?** If `deploy.yml`'s revert step failed, the
  EKS public allowlist still contains a runner `/32` — restore it manually with
  `aws eks update-cluster-config`.
