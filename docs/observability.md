# Observability

What to measure in RT-DAD, why, and where it lives today vs. what's still
missing. Operational access (NodePorts, triage) is in
[pipeline-operations.md](pipeline-operations.md); detection semantics behind
the metrics are in [design.md](design.md).

Legend: **[have]** implemented today · **[gap]** proposed, not yet built.

## The system's observability shape

RT-DAD is a single-consumer stateful stream processor. That shape dictates
what matters:

- The consumer **cannot scale** (single in-memory `RollingWindow`,
  `concurrency=1`). So the primary risk is *this one instance* falling behind
  or losing state — not fleet-level aggregation.
- Detection quality is **statistical**, not binary. "Is it healthy" is not
  answerable from up/down alone; you need distributions (z-score, window
  stats) and rates (anomaly/regime/drop).
- The pipeline is **RabbitMQ-backed**, so queue depth and DLQ are first-class
  health signals, not afterthoughts.

## 1. Metrics

### Consumer — detection meters **[have]**

Source: `DetectorMetrics.java`. Scraped at `/actuator/prometheus` — **5s**
locally (`observability/prometheus/prometheus.yml`), **30s** in EKS via the
`ServiceMonitor` (`deploy/helm/rtdad/templates/consumer-servicemonitor.yaml`).

| Meter | Type | Reads as (Prometheus) | Tells you |
| --- | --- | --- | --- |
| `anomaly.detector.messages` | Counter (tag `status`) | `anomaly_detector_messages_total{status=...}` | throughput split by `ok` / `anomaly` / `regime_shift` / `dropped` |
| `anomaly.detector.zscore` | DistributionSummary | `anomaly_detector_zscore_*` | z-score distribution (finite values only) |
| `anomaly.detector.processing.seconds` | Timer | `..._seconds_count` / `_sum` | per-point evaluation latency |
| `anomaly.detector.dead.letters` | Counter | `anomaly_detector_dead_letters_total` | poison/failed messages — **should stay 0** |
| `anomaly.detector.window.size` | Gauge | | how full the window is (warm-up progress) |
| `anomaly.detector.rolling.mean` / `.stddev` | Gauge | | current baseline the detector scores against |
| `anomaly.detector.window.warm` | Gauge (0/1) | | whether scoring is active yet |

Design notes worth knowing when reading these: null/±Inf z-scores are
excluded from the summary; `dropped` is a data-quality event (non-finite
input) reported separately from `ok`. Gauges bind live to the injected
`RollingWindow` and are sampled at scrape time.

### Producer instrumentation **[gap]**

The producer emits nothing. Useful to close the loop on the "expected vs.
observed anomaly rate" question:

- messages published (counter), publish failures / confirm timeouts
- injected-anomaly counter — ground truth to validate detector recall
- publish latency / rate vs. configured `--rate-ms`

### Infra / runtime metrics **[gap / partial]**

- JVM + process metrics: free from Actuator/Micrometer once
  `micrometer-registry-prometheus` is on the classpath — heap, GC pause, CPU,
  thread count, file descriptors. Confirm they're actually exposed and
  dashboarded.
- **RabbitMQ**: queue depth (inbound), DLQ depth (`*_dlq`), fanout publish
  rate, unacked/consumer count. Today read manually via the mgmt UI
  ([pipeline-operations.md](pipeline-operations.md)); a `rabbitmq_exporter`
  (or the built-in Prometheus plugin) would make these alertable.
- **Node/pod**: single SPOT `t3.medium` — spot reclaim and OOMKill are real
  failure modes; surface node conditions and pod restarts. This is a
  **deliberate trim, not an oversight**: `monitoring.tf` disables
  `nodeExporter` and `kubeStateMetrics` to save pod slots on the single node.
  JVM/app metrics still flow via the ServiceMonitor; node-level series are the
  cost. Re-enabling them means finding headroom (or a second node) first.

## 2. Dashboards **[have — one]**

`observability/grafana/dashboards/rtdad-observability.json` (RT-DAD Detection
Health), auto-provisioned. Topics a mature dashboard set should cover:

- **Detection health**: message rate by status, anomaly ratio, regime-shift
  events, z-score distribution over time, dropped rate.
- **Window state**: size (warm-up), live mean/stddev, `warm` flag — the
  "is the detector even scoring" panel.
- **Pipeline / RabbitMQ**: inbound depth, DLQ depth (must be 0), fanout rate.
- **Runtime**: JVM heap/GC, CPU, restarts.

[Screenshot of the dashboard](img/grafana-dashboard.png)

Guidance: keep detection and infra on separate rows; annotate deploys so a
metric shift can be tied to a rollout.

## 3. Alerting **[gap]**

No Prometheus rules exist. **Prerequisite in EKS**: `monitoring.tf` currently
sets `alertmanager.enabled = false` and disables the operator's
`admissionWebhooks` (comment: *"we author no PrometheusRules"*). Shipping any
of the rules below means re-enabling Alertmanager (for routing) and the
admission webhooks (which validate `PrometheusRule` CRDs) first — and the
webhook pre-upgrade hook needs a free pod slot on the single node, so mind the
headroom. Rules themselves are cheap; the enablement is the work.

Highest-value alerts for this topology:

| Alert | Condition | Why |
| --- | --- | --- |
| Dead letters | `increase(anomaly_detector_dead_letters_total[5m]) > 0` | poison messages / bug — never expected |
| Consumer stalled | `rate(anomaly_detector_messages_total[5m]) == 0` while producer publishing | consumer dead or wedged |
| DLQ backing up | RabbitMQ DLQ depth `> 0` | messages failing repeatedly |
| Queue backlog | inbound depth growing sustained | consumer can't keep up (can't scale out — investigate) |
| Window cold | `anomaly_detector_window_warm == 0` for > warm-up period | scoring never started → restart loop or no input |
| Target down | `up{job="rtdad-consumer"} == 0` | scrape failing |

Decide routing/severity and where notifications land (out of scope here).

## 4. Logs **[have — unstructured]**

Consumer logs one line per point (format in root `README.md`); `REGIME SHIFT`
is a notice, anomalies are alerts. Topics to decide:

- **Structured (JSON) logging** for machine parsing / aggregation vs. current
  human-readable lines.
- **Correlation**: carry a message/point id so a log line, its metric event,
  and (future) trace tie together.
- **Log levels & volume**: one-line-per-point is fine at dev rates; confirm
  it's sane at production throughput or sample it.
- **Aggregation backend**: none today (kubectl logs only) — Loki would slot
  next to the existing Grafana.

## 5. Tracing **[gap]**

Not present. Lower priority for a two-hop pipeline, but the useful topic is a
**span per message** across producer publish → RabbitMQ → consumer evaluate,
propagating context via AMQP headers (Micrometer Tracing / OpenTelemetry).
Value: pinpoint where latency/loss occurs end-to-end.

## 6. SLIs / SLOs **[gap]**

Define what "healthy" means numerically so alerts and dashboards have a
target:

- **Freshness / lag**: time from publish to detection (needs producer +
  consumer timestamps).
- **Availability**: consumer up and scraping.
- **Correctness proxy**: detected-anomaly rate vs. producer's injected rate
  (recall) — the closest thing to a quality SLI here.
- **Loss**: dead-letter rate as an error budget (target 0).

## 7. Cross-cutting concerns

- **Cardinality**: the only tagged meter is `messages{status}` (4 values,
  bounded). Keep it that way — never tag by point value or id.
- **Meter registration**: status series are pre-registered at zero so panels
  aren't empty before first message; preserve this when adding meters.
- **Scrape interval & retention**: 5s locally (`prometheus.yml`), 30s in EKS
  (ServiceMonitor); EKS Prometheus retains **3d** (`monitoring.tf`). Fine for
  the staging experiment; revisit interval/retention for production cost.
- **Domain purity**: `DetectorMetrics` is the *only* Micrometer-aware class;
  domain classes stay instrumentation-free. Add new taps there, not in the
  detector.
- **Endpoint exposure**: only `/actuator/prometheus` should be public;
  audit which actuator endpoints are exposed on the `:30808` NodePort.

## Priority if building out

1. Alerting rules (§3) — highest leverage, cheap, uses existing metrics.
2. RabbitMQ + JVM metrics into Prometheus/Grafana (§1) — close the infra blind spot.
3. Producer instrumentation (§1) — unlocks the recall SLI.
4. Structured logging + correlation ids (§4).
5. SLOs (§6), then tracing (§5).
