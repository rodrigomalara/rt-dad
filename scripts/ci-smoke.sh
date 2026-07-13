#!/usr/bin/env bash
# Compose smoke test for RT-DAD. Assumes rtdad-producer:ci and rtdad-consumer:ci
# are already built and loaded into the local Docker engine.
set -euo pipefail

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.ci.yml)
ACTUATOR="http://localhost:8080/actuator/prometheus"

cleanup() {
  echo "--- docker compose logs ---"
  "${COMPOSE[@]}" logs || true
  "${COMPOSE[@]}" down -v || true
}
trap cleanup EXIT

echo "Starting stack (detached, no rebuild)..."
"${COMPOSE[@]}" up -d --no-build

echo "Waiting for consumer actuator to answer..."
for i in $(seq 1 60); do
  if curl -sf "$ACTUATOR" >/dev/null; then break; fi
  sleep 2
  if [ "$i" -eq 60 ]; then echo "consumer never became ready"; exit 1; fi
done

echo "Waiting for the bounded producer to finish..."
"${COMPOSE[@]}" wait producer

# Sum every series matching the prefix. Prefix (not full-label) match is
# deliberate: Micrometer emits sorted labels, e.g.
#   anomaly_detector_messages_total{application="rtdad-consumer",status="ok"} 551
# so anchoring on a specific label order is brittle. Summing across statuses
# gives total messages the consumer processed.
metric() { curl -sf "$ACTUATOR" | awk -v m="$1" '$1 ~ m {v+=$2} END {printf "%d", v+0}'; }

# This is a transport/wiring smoke test: producer -> RabbitMQ -> consumer ->
# metrics exposed, with no dead-letters. It does NOT assert anomaly-detection
# correctness (stochastic, z-score tuned) -- that is covered by the consumer
# unit tests (AnomalyDetectorTest, RollingWindowTest, DetectorMetricsTest).
echo "Polling for processed messages..."
processed=0
for i in $(seq 1 30); do
  processed=$(metric '^anomaly_detector_messages_total')
  if [ "$processed" -gt 0 ]; then break; fi
  sleep 2
done

dead=$(metric '^anomaly_detector_dead_letters_total')

echo "processed=$processed dead_letters=$dead"
if [ "$processed" -le 0 ]; then echo "FAIL: consumer processed no messages"; exit 1; fi
if [ "$dead" -ne 0 ]; then echo "FAIL: dead-letter count is $dead (expected 0)"; exit 1; fi
echo "SMOKE PASS"
trap - EXIT
"${COMPOSE[@]}" down -v
