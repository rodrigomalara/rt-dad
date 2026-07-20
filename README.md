# RT-DAD — Real-Time Data Anomaly Detector

A streaming anomaly-detection system over RabbitMQ. A **producer** generates
normally-distributed metric points (with occasional injected outliers) and
publishes them; a **consumer** maintains a rolling statistical window,
computes a Z-score per point, classifies anomalies, logs them, and
re-publishes confirmed anomalies to a fanout exchange.

Maven multi-module monorepo: `rtdad-common` (shared JSON contract),
`rtdad-producer` (CLI generator), `rtdad-consumer` (Z-score detector).

For details on how the detection logic works (including evaluate-then-add, anti-poisoning, regime shifts, and anomaly injection), please see the [Design Documentation](docs/design.md).

## Prerequisites

To build and run the project locally, only Java and Docker are needed. The other tools listed are primarily for infrastructure provisioning and deployment workflows.

| Tool                  | Version    | Needed for                                            | Install |
|-----------------------|------------|-------------------------------------------------------|---------|
| **JDK**               | 25 (Temurin) | Build and run the Maven modules (`.java-version` pins `25`) | [Adoptium Temurin](https://adoptium.net/) / `sdk install java 25-tem` |
| **Maven**             | —          | Build — bundled via `./mvnw` wrapper, no separate install | — |
| **Docker**            | ≥ 24       | Local stack (RabbitMQ, Prometheus, Grafana, services)  | [Docker Engine](https://docs.docker.com/engine/install/) |
| **Docker Compose**    | v2 (`docker compose`) | `docker compose up --build`                            | Ships with Docker Desktop / [compose plugin](https://docs.docker.com/compose/install/) |
| **AWS CLI**           | v2         | ECR login, EKS access                                  | [AWS CLI v2](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) |
| **Terraform**         | 1.10.5     | Provision infra (`infra/`)                             | [HashiCorp Terraform](https://developer.hashicorp.com/terraform/install) |
| **tflint**            | 0.53.0     | Lint Terraform                                         | [tflint](https://github.com/terraform-linters/tflint#installation) |
| **kubectl**           | —          | Talk to the EKS cluster                                | [kubectl](https://kubernetes.io/docs/tasks/tools/) |
| **Helm**              | 3          | Deploy charts (`deploy/helm/`)                         | [Helm](https://helm.sh/docs/intro/install/) |
| **GitHub CLI (`gh`)** | —          | PRs, workflow runs, releases                           | [cli.github.com](https://cli.github.com/) |

## Build

To format the code and build the entire project:

```bash
./mvnw spotless:apply
./mvnw verify
```

To build a single module (along with its dependencies), run: `./mvnw -pl rtdad-consumer -am package`.

## Code Quality Tools

The project uses SpotBugs and PMD for static code analysis. Both tools are bound to the `verify` phase and will run automatically when `./mvnw verify`.

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

When testing is complete, everything can be stopped by running: `docker compose down` (add `-v` to also drop the RabbitMQ volume).

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

When `--duration-seconds` is absent or `0`, the producer will run continuously until stopped with Ctrl+C.
Either way, the shutdown process is ordered: the poller stops generating new points
before the AMQP connection is closed, ensuring that no in-flight data is lost.

## Tune the consumer

The consumer comes with sensible defaults, but all settings can be easily overridden via environment variables (these are mapped in
`rtdad-consumer/src/main/resources/application.yml`):

| Env var                                       | Property                              | Default |
|------------------------------------------------|----------------------------------------|---------|
| `RTDAD_WINDOW_MIN_SAMPLES`                     | `rtdad.window.min-samples`             | 50      |
| `RTDAD_WINDOW_MAX_SAMPLES`                     | `rtdad.window.max-samples`             | 100     |
| `RTDAD_DETECTOR_Z_THRESHOLD`                   | `rtdad.detector.z-threshold`           | 3.0     |
| `RTDAD_DETECTOR_REGIME_SHIFT_RUN_FRACTION`     | `rtdad.detector.regime-shift-run-fraction` | 0.10 |
| `SPRING_RABBITMQ_HOST/PORT/USERNAME/PASSWORD`  | `spring.rabbitmq.*`                    | localhost/5672/dev/dev |

## Observe

Once the system is running, it can be monitored as follows:

- **What is measured and why:** Check out the [Observability Docs](docs/observability.md) to see the signal inventory and any known gaps.
- **Live Logs:** Watch the consumer's standard output. It prints one line per data point (see the log format below).
- **Metrics Scraping:** Visit Prometheus at `http://localhost:9090/targets` to verify it's successfully scraping the consumer's Actuator endpoint.
- **Dashboards:** Log into Grafana (`http://localhost:3000`, `dev`/`dev`), head over to **Explore**, pick the preconfigured Prometheus data source, and try querying `up` or any other emitted metric.
- **Message Queues:** The RabbitMQ management UI (`http://localhost:15672`, `dev`/`dev`) is a great place to keep an eye on queue depths (like `rtdad_metrics_inbound` and the dead-letter queue, `rtdad_metrics_inbound_dlq`, which should ideally be empty) and publish rates on the `rtdad_anomalies_outbound` fanout.

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
- `REGIME SHIFT` — see [detection rationale](docs/design.md#adapting-to-change-the-regime-shift).

## `AnomalyEvent` — fanout schema

Published to the `rtdad_anomalies_outbound` fanout exchange (JSON,
`shared` type lives in `rtdad-common` so any subscriber gets value + Z-score):

```json
{"timestamp":"2023-10-27T14:32:01.123Z","value":512.7,"zScore":8.42}
```

`zScore` may be a large or `Infinity` value for the flat-window case;
treat non-finite as "unbounded deviation".

## Consumer scaling

**Please stick to running exactly one consumer instance for now.** 

Because of the detection algorithm currently being used, the `RollingWindow` is a single in-memory bean and the listener is deliberately pinned to `concurrency=1` so window mutation needs no locking. 

If the consumer is scaled (for example, by running `docker compose up --scale consumer=2`, or by raising listener concurrency), RabbitMQ will round-robin the stream across instances. This means each consumer will only see a disjoint subset of the traffic, leaving every instance with an incomplete window and a statistically invalid mean/stddev. The `docker-compose.yml` deliberately omits `replicas` on the `consumer` service for exactly this reason.

Correct horizontal scaling would require either:
- A partition key (e.g. `device_id`) with per-key routing, so each partition's full stream lands on exactly one consumer instance, or
- An external shared-state store (e.g. Redis) holding the rolling window, so multiple consumer instances can share one source of truth.

Neither of these approaches is implemented yet (YAGNI) — this section is just here as a heads-up so the detection is not accidentally broken by scaling it blindly.

## Added features – not in [requirements](docs/requirements.md)

### Observability

Using Spring actuator, micrometer, Prometheus, and Grafana.

See [docs/observability.md](docs/observability.md)

### Infrastructure

Using Terraform and AWS EKS.

See [docs/infrastructure.md](docs/infrastructure.md)

### Anomalies notifications

Anomalies are posted to `rtdad_anomalies_outbound` RabbitMQ exchange.


## What's Next? (Future Considerations)

This POC currently handles a single, steady event stream. The scenarios below sketch out how the design could evolve under more demanding conditions. These are not built yet (YAGNI), but the intended direction is documented here so the trade-offs are clear.

### Variable event velocity

Incoming message rate is not constant. Track a moving average of the arrival
rate and resize the rolling window to match it: grow the window when traffic is
sparse, shrink it when traffic is dense. This keeps alert sensitivity high while
using the fewest samples that still yield a valid mean/stddev.

### High burstiness

Reallocating the window array on every rate change is wasteful under bursty
traffic. Instead, allocate once for the worst-case burst in view and back it
with a capped ring buffer: the detection logic then reads either the whole
buffer or only a recent slice, depending on current load, with no reallocation.
Pair this with a bounded RabbitMQ prefetch so the consumer doesn't pull more
in-flight messages than its JVM can hold.

### Multiple event and notification streams

- One virtual thread per queue keeps per-stream processing isolated and cheap.
- RabbitMQ throughput becomes a capacity concern worth measuring.
- Prefer vertical scaling first — more CPU and memory for the consumer.
- Horizontal scaling is possible but needs work: either a load-distribution
  layer that assigns queues across pods, or RabbitMQ Streams with a Stream
  Coordinator to partition consumption. (See [Consumer scaling](#consumer-scaling)
  for why naive replica scaling breaks the current algorithm.)

### Multiple producers, single consumer (discuss if needed)

- Events may reach the exchange in a different order than they were emitted.
  Since ordering can determine whether an alert fires, detection behaviour can
  drift.
- A single consumer becomes a bottleneck: its queue floods and alerts are
  emitted long after the events that triggered them.

### Rolling-window state recovery (if required)

Rebuild window state on pod startup so a restart doesn't blind the detector:

- **RabbitMQ:** a history queue with `max-length = N` replays the last `N`
  messages to reconstruct the window.
- **RabbitMQ Streams / Kafka:** rewind the offset by `N`, replay silently (no
  alerts), then resume live processing.

### Out-of-order events and clock skew

Events are currently processed in arrival order. If processing must honour
event time instead, stream events into a time-series database and run anomaly
detection over data pulled from it. This decouples detection from arrival order
but sharply increases capacity requirements.

### Seasonality

Z-score assumes roughly normally distributed data, so it will alert on
legitimate regime changes — e.g. daytime vs. night-time traffic for a service
backing a business application. Handling seasonality would require a
baseline that adapts to the expected periodic pattern.

## Disclaimer

AI was used during this POC development, during analysis, design, and implementation phases.
