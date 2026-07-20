# Observability

Welcome to the observability guide! This document explains what is measured in RT-DAD, why it is measured, where these metrics can be found today, and what is on the wish list for the future.

If looking for how to access dashboards and logs day-to-day (like NodePorts and triaging), head over to [pipeline-operations.md](pipeline-operations.md). To understand the math behind the detection metrics, check out [design.md](design.md).

*Legend: **[have]** means it is built. **[gap]** means it's an idea not yet implemented.*

## The Shape of the System

Because RT-DAD is a single-consumer stateful stream processor, observability is approached a bit differently than a typical web app:

- **The consumer cannot scale:** It relies on a single in-memory `RollingWindow` and a concurrency of 1. That means the biggest risk is *this single instance* falling behind or crashing, rather than a fleet-level issue.
- **Detection is statistical, not binary:** One can't just ask "is it up or down?" to know if it's healthy. Distributions (like z-scores and window stats) and rates (anomalies, regime shifts, dropped messages).
- **RabbitMQ dependency:** Queue depth and Dead Letter Queues (DLQs) are vital health signals, not just afterthoughts.

---

## 1. What is Measured (Metrics)

### Consumer: Detection Meters **[have]**

All of our detection metrics come from `DetectorMetrics.java`. We scrape them from the `/actuator/prometheus` endpoint. (Locally we scrape every 5 seconds; in EKS we scrape every 30 seconds via a `ServiceMonitor`).

| Metric | What it is (Type) | How it appears in Prometheus | What it tells you |
| --- | --- | --- | --- |
| `anomaly.detector.messages` | Counter | `anomaly_detector_messages_total{status=...}` | Throughput. It is split by `status` so one can see `ok`, `anomaly`, `regime_shift`, or `dropped` messages. |
| `anomaly.detector.zscore` | Distribution | `anomaly_detector_zscore_*` | The spread of z-scores (only finite values are counted). |
| `anomaly.detector.processing.seconds` | Timer | `..._seconds_count` / `_sum` | How long it takes to evaluate each data point. |
| `anomaly.detector.dead.letters` | Counter | `anomaly_detector_dead_letters_total` | Poison or failed messages. **This should always be 0!** |
| `anomaly.detector.window.size` | Gauge | | How full the rolling window is (useful for tracking warm-up progress). |
| `anomaly.detector.rolling.mean` / `.stddev` | Gauge | | The current baseline the detector is scoring against. |
| `anomaly.detector.window.warm` | Gauge (0/1) | | A simple 1 or 0 showing if the detector has enough data to start scoring. |

*A quick note:* Null or infinite z-scores are excluded from the summary. Also, "dropped" messages mean the input was invalid (like a non-finite value), which is reported separately from "ok" messages.

### Producer Instrumentation **[gap]**

Right now, the producer doesn't emit metrics. It would be great to add this to answer questions like, "Did the detector find the anomalies that were actually injected?"
- Messages published and publish failures should be tracked.
- An injected-anomaly counter would provide a ground truth to validate the detector's recall.
- Publish latency vs. the configured `--rate-ms` would help track performance.

### Infrastructure & Runtime Metrics **[gap / partial]**

- **JVM + Process Metrics:** These come free with Micrometer (heap, GC pauses, CPU, etc.), but they need to be confirmed as dashboarded.
- **RabbitMQ:** Today, queue depth, DLQs, and unacknowledged messages must be checked manually via the RabbitMQ UI. Adding an exporter would allow automatic alerts for these.
- **Node/Pod Metrics:** Since a single spot `t3.medium` node is used, spot reclaims and memory limits are real concerns. However, nodeExporter and kubeStateMetrics were intentionally disabled to save pod slots on the tiny node. If these node-level metrics are ever wanted back, a bigger node (or a second one).

---

## 2. Where to Look (Dashboards) **[have — one]**

One main dashboard is auto-provisioned at `observability/grafana/dashboards/rtdad-observability.json` called "RT-DAD Detection Health".

A mature dashboard should answer these questions at a glance:
- **Detection Health:** What's the message rate? Are regime shifts being seen? Are messages dropping?
- **Window State:** Is the window warm? What is the current mean and standard deviation?
- **Pipeline Health:** Are queues backing up? Is the DLQ empty?
- **Runtime Health:** How is the JVM doing? Have there been recent restarts?

*Tip: Keep detection and infrastructure panels on separate rows, and always annotate deployments so one can see if a metric shifted right after a rollout!*

---

## 3. How Notifications Work (Alerting) **[gap]**

Currently, no Prometheus rules are set up. To add them, Alertmanager would first need to be re-enabled in `monitoring.tf` and enough room ensured on the single node for the necessary admission webhooks.

Once alerting is enabled, here are the most important alerts to build:

| Alert | Condition | Why it matters |
| --- | --- | --- |
| **Dead Letters** | `increase(anomaly_detector_dead_letters_total[5m]) > 0` | A poison message or bug occurred. This should never happen. |
| **Consumer Stalled** | Message rate is `0` while the producer is still running | The consumer is dead or stuck. |
| **DLQ Backing Up** | RabbitMQ DLQ depth `> 0` | Messages are failing repeatedly. |
| **Queue Backlog** | Inbound depth is growing continuously | The consumer can't keep up, and it cannot be scaled out. |
| **Window Cold** | `anomaly_detector_window_warm == 0` for too long | The scoring never started, which means there's no input or a restart loop. |
| **Target Down** | `up{job="rtdad-consumer"} == 0` | Prometheus can't scrape the consumer. |

---

## 4. Tracing the Steps (Logs) **[have — unstructured]**

Currently, the consumer logs one line per data point. Anomalies are logged as warnings, and `REGIME SHIFT` is logged as a notice. (See the root `README.md` for the format).

Things to consider for the future:
- **Structured (JSON) logging:** This would make it easier to parse logs with machines, though harder for humans to read.
- **Correlation IDs:** Passing an ID through the system would allow tying a specific log line to its corresponding metric event or trace.
- **Log volume:** Logging every single point is fine for staging, but it might be too noisy for production. Sampling logs may be needed later.
- **Log Aggregation:** Currently, only `kubectl logs` is used. Setting up something like Loki would be a huge upgrade.

---

## 5. End-to-End Tracing **[gap]**

Distributed tracing is not yet available. While lower priority for a simple two-hop pipeline, adding a span per message (from Producer → RabbitMQ → Consumer) would help pinpoint exactly where latency or message loss happens.

---

## 6. Defining Success (SLIs / SLOs) **[gap]**

Eventually, what "healthy" means numerically should be defined, so alerts and dashboards have clear targets:
- **Freshness / Lag:** How long does it take from publish to detection?
- **Availability:** Is the consumer up and being scraped?
- **Correctness:** Does the detector catch the injected anomalies? (Recall).
- **Loss:** Is the dead-letter rate perfectly 0?

---

## 7. Things to Keep in Mind (Cross-Cutting Concerns)

- **Keep tags bounded:** The `messages` metric is only tagged by `status` (which only has 4 possible values). Metrics should never be tagged by unique point values or IDs, as this will explode metric cardinality!
- **Zero-initialized meters:** The status series is registered at zero right at startup. This prevents Grafana panels from showing "No Data" before the first message arrives. This pattern should be kept if new meters are added.
- **Scrape intervals:** Scraping occurs every 5s locally and 30s in EKS, keeping data for 3 days. This is perfect for staging, but should be revisited for production to save costs.
- **Domain purity:** `DetectorMetrics` is the *only* Micrometer-aware class. Instrumentation code should be kept out of the core domain logic!

## Priority List for Future Work

If looking for what to build next:
1. **Alerting Rules (§3):** Highest value, cheapest to implement, and uses existing data.
2. **RabbitMQ + JVM Dashboards (§1):** Fixes our biggest blind spot in infrastructure.
3. **Producer Instrumentation (§1):** Allows measuring if the detector actually works.
4. **Structured Logging + Correlation IDs (§4).**
5. **SLOs (§6), then Tracing (§5).**
