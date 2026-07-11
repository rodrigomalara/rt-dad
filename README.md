# RT-DAD — Real-Time Data Anomaly Detector

A streaming anomaly-detection system over RabbitMQ. A **producer** generates
normally-distributed metric points (with occasional injected outliers) and
publishes them; a **consumer** maintains a rolling statistical window,
computes a Z-score per point, classifies anomalies, logs them, and
re-publishes confirmed anomalies to a fanout exchange.

Maven multi-module monorepo: `rtdad-common` (shared JSON contract),
`rtdad-producer` (CLI generator), `rtdad-consumer` (Z-score detector).

## Build

```bash
./mvnw spotless:apply
./mvnw verify
```

Build a single module (with its dependencies): `./mvnw -pl rtdad-consumer -am package`.

## Code Quality Tools

The project uses SpotBugs and PMD for static code analysis. Both tools are bound to the `verify` phase and will run automatically when you execute `./mvnw verify`.

To run them individually, use:

```bash
./mvnw spotbugs:check
./mvnw pmd:check
```

## Run via Docker Compose

```bash
docker compose up --build
```

This starts RabbitMQ (management UI at http://localhost:15672, `dev`/`dev`),
the producer (emits ~5 msg/s by default), the consumer (single instance,
logs detections to stdout), Prometheus at http://localhost:9090, and Grafana
at http://localhost:3000 (`dev`/`dev`). Grafana's Prometheus data source is
provisioned automatically together with the **RT-DAD Detection Health**
dashboard.

Stop everything: `docker compose down` (add `-v` to also drop the RabbitMQ
volume).

## Run the producer standalone

```bash
./mvnw -pl rtdad-producer -am package -DskipTests
java -jar rtdad-producer/target/rtdad-producer-*.jar \
  --host=localhost --port=5672 --username=dev --password=dev \
  --exchange=rtdad.metrics --routing-key=rtdad_metrics_inbound \
  --rate-ms=200 --mean=100 --stddev=15 --anomaly-probability=0.05 \
  --duration-seconds=30
```

| Arg                     | Default                | Meaning                                   |
|--------------------------|------------------------|--------------------------------------------|
| `--host`                | localhost               | RabbitMQ host                              |
| `--port`                | 5672                    | RabbitMQ port                              |
| `--username`            | dev                     | RabbitMQ user                              |
| `--password`            | dev                     | RabbitMQ password                          |
| `--exchange`            | rtdad.metrics           | Direct exchange to publish to              |
| `--routing-key`         | rtdad_metrics_inbound   | Routing key                                |
| `--duration-seconds`    | _(absent)_              | Run duration in seconds; absent or `0` = run until Ctrl+C |
| `--rate-ms`              | 200                     | Emit interval (~5 msg/s)                   |
| `--mean`                 | 100.0                   | Normal-distribution mean                   |
| `--stddev`               | 15.0                    | Normal-distribution stddev                 |
| `--anomaly-probability` | 0.05                    | P(inject outlier) per point                |

When `--duration-seconds` is absent or `0`, the producer runs until Ctrl+C.
Either way, shutdown is ordered: the poller stops generating new points
before the AMQP connection is closed, so no in-flight point is dropped.

## Tune the consumer

All settings are overridable via environment variables (see
`rtdad-consumer/src/main/resources/application.yml`):

| Env var                                       | Property                              | Default |
|------------------------------------------------|----------------------------------------|---------|
| `RTDAD_WINDOW_MIN_SAMPLES`                     | `rtdad.window.min-samples`             | 50      |
| `RTDAD_WINDOW_MAX_SAMPLES`                     | `rtdad.window.max-samples`             | 100     |
| `RTDAD_DETECTOR_Z_THRESHOLD`                   | `rtdad.detector.z-threshold`           | 3.0     |
| `RTDAD_DETECTOR_REGIME_SHIFT_RUN_FRACTION`     | `rtdad.detector.regime-shift-run-fraction` | 0.10 |
| `SPRING_RABBITMQ_HOST/PORT/USERNAME/PASSWORD`  | `spring.rabbitmq.*`                    | localhost/5672/dev/dev |

## Observe

- Consumer stdout: one line per data point (see log format below).
- Prometheus targets (`localhost:9090/targets`): verify that the producer and
  consumer Actuator endpoints are being scraped.
- Grafana (`localhost:3000`, `dev`/`dev`): open **Explore**, select the
  preconfigured Prometheus data source, and query `up` or any emitted metric.
- RabbitMQ management UI (`localhost:15672`, `dev`/`dev`): inspect
  `rtdad_metrics_inbound` depth, `rtdad_metrics_inbound_dlq` depth (should
  stay at 0 unless a malformed payload is dead-lettered), and publish rates
  on the `rtdad_anomalies_outbound` fanout.

## Log format

```
Normal:  [<TIMESTAMP>] Data point: <X.XX> | Status: OK | Z-score: <Z.ZZ>
Warmup:  [<TIMESTAMP>] Data point: <X.XX> | Status: OK | Z-score: N/A
Anomaly: [<TIMESTAMP>] Data point: <X.XX> | Status: ANOMALY DETECTED! | Z-score: <Z.ZZ> | ALERT: Significant deviation detected.
Regime:  [<TIMESTAMP>] Data point: <X.XX> | Status: REGIME SHIFT | Z-score: <Z.ZZ> | NOTICE: Sustained level change; window reseeded to new baseline.
```

- `N/A` — the window hasn't reached `min-samples` yet; the point is still
  admitted so the window can warm up, but no Z-score can be computed.
- `Inf` — the window is "flat" (`stddev == 0`, every sample identical so
  far) and the point differs from that constant value; any deviation from a
  perfectly flat baseline is an unbounded (infinite) Z-score.
- `REGIME SHIFT` — see below.

## Anomaly injection (producer)

With probability `--anomaly-probability` per point, the producer emits an
outlier instead of a normal sample: `mean ± (8..12) * stddev`, sign random.
This guarantees Z >> 3 once the consumer's window is warm, so injected
outliers reliably trigger `ANOMALY DETECTED!` (or, if sustained, a regime
shift — see below).

## Anomaly detection semantics

The detector scores each point **against the window's prior state**
(evaluate-then-add) — adding the point first would let it inflate its own
mean/stddev and suppress its own Z-score. A confirmed anomaly (a transient
spike) is logged/published but **not admitted** to the window
(anti-poisoning): a burst of one-off outliers cannot drag the reference
statistics off course.

### Regime shift

A single spike is noise; a *sustained, same-direction* run of anomalies is
evidence the underlying process has genuinely shifted to a new baseline.
The detector tracks a run of consecutive same-sign anomalies. Once the run
reaches:

```
K = max(2, ceil(regime-shift-run-fraction * max-samples))
```

(a **fraction of the window**, not an absolute count — a 100-point window
tolerating a 10-point run is proportionally as sensitive as a 50-point
window tolerating 5), the detector treats it as a regime shift:

- The window is **reseeded** with the buffered run (`window.reseed(...)`) —
  the run becomes the new baseline.
- This is logged as `REGIME SHIFT` (a notice, not an alert) and is **not**
  published to the anomalies fanout — it marks adaptation, not a fault.
- An opposite-sign outlier mid-run resets the counter (it's noise breaking
  up the trend, not a continuation of it).
- After reseeding, the window may briefly dip below `min-samples` and
  re-enter warm-up — this is expected, safe behavior.

## `AnomalyEvent` — fanout schema

Published to the `rtdad_anomalies_outbound` fanout exchange (JSON,
`shared` type lives in `rtdad-common` so any subscriber gets value + Z-score):

```json
{"timestamp":"2023-10-27T14:32:01.123Z","value":512.7,"zScore":8.42}
```

`zScore` may be a large or `Infinity` value for the flat-window case;
treat non-finite as "unbounded deviation".

## Do not scale the consumer

**Hard limitation: run exactly one consumer instance.** The `RollingWindow`
is a single in-memory bean and the listener is deliberately pinned to
`concurrency=1` so window mutation needs no locking. If you scale the
consumer (`docker compose up --scale consumer=2`, or raise listener
concurrency), RabbitMQ round-robins the stream across instances — each
consumer then sees a disjoint subset of the traffic, giving every instance
an incomplete window and statistically invalid mean/stddev. `docker-compose.yml`
deliberately omits `replicas` on the `consumer` service for this reason.

Correct horizontal scaling would require either:
- a partition key (e.g. `device_id`) with per-key routing, so each
  partition's full stream lands on exactly one consumer instance, or
- an external shared-state store (e.g. Redis) holding the rolling window,
  so multiple consumer instances share one source of truth.

Neither is implemented here (YAGNI) — this section exists so nobody scales
it blindly.
