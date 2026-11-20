#!/usr/bin/env bash
# Run one scenario RUNS times against the current kube context; write results/<date>-<scenario>/.
# Each run restarts callee and caller so connection pools start fresh.
set -euo pipefail

SCENARIO=${SCENARIO:-s0}
RUNS=${RUNS:-1}
RATE=${RATE:-600}
DURATION=${DURATION:-30s}
OUT=results/$(date -u +%Y%m%d)-$SCENARIO
mkdir -p "$OUT"

prom() { curl -sf --data-urlencode "query=$1" --data-urlencode "time=$2" localhost:9090/api/v1/query; }

kubectl create configmap k6-script --from-file=load/ --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl port-forward svc/prometheus 9090:9090 >/dev/null 2>&1 &
PF=$!
trap 'kill $PF 2>/dev/null || true' EXIT

echo "| run | requests per callee pod | skew (max-min)/mean | open connections per pod | handler p99 per pod (ms) | k6 p99 |" > "$OUT/summary.md"
echo "|---|---|---:|---|---|---|" >> "$OUT/summary.md"

for i in $(seq 1 "$RUNS"); do
  echo "== $SCENARIO run $i/$RUNS (rate=$RATE duration=$DURATION)"
  kubectl rollout restart deploy/callee deploy/caller >/dev/null
  kubectl rollout status deploy/callee --timeout=180s >/dev/null
  kubectl rollout status deploy/caller --timeout=180s >/dev/null
  # Terminating pods can linger; wait until only the new ones exist.
  kubectl wait --for=delete pod -l app=callee --field-selector=status.phase!=Running --timeout=120s >/dev/null 2>&1 || true
  sleep 15 # let Prometheus discover and scrape the new pods

  pods=$(kubectl get pods -l app=callee -o json \
    | jq -c '[.items[] | select(.metadata.deletionTimestamp == null and .status.phase == "Running") | .metadata.name]')

  kubectl delete job k6 --ignore-not-found >/dev/null
  kubectl create -f - >/dev/null <<YAML
apiVersion: batch/v1
kind: Job
metadata:
  name: k6
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:0.52.0
          args: [run, --quiet, /scripts/constant.js]
          env:
            - { name: RATE, value: "$RATE" }
            - { name: DURATION, value: "$DURATION" }
          resources:
            limits: { cpu: "1", memory: 512Mi }
          volumeMounts:
            - { name: scripts, mountPath: /scripts }
      volumes:
        - name: scripts
          configMap: { name: k6-script }
YAML
  kubectl wait --for=condition=Ready pod -l job-name=k6 --timeout=120s >/dev/null
  start=$(date +%s)
  kubectl wait --for=condition=complete job/k6 --timeout=600s >/dev/null
  end=$(date +%s)
  sleep 10 # one more scrape after the load stops
  kubectl logs job/k6 > "$OUT/run-$i-k6.txt"
  window=$((end - start + 10))
  at=$((end + 10))

  prom "sum by (pod) (increase(callee_request_seconds_count[${window}s]))" "$at" > "$OUT/run-$i-requests.json"
  # Peak during the run, so connections closed at the end still count.
  prom "max by (pod) (max_over_time(callee_open_connections[${window}s]))" "$at" > "$OUT/run-$i-connections.json"
  prom "histogram_quantile(0.99, sum by (pod, le) (rate(callee_request_seconds_bucket[${window}s])))" "$at" > "$OUT/run-$i-p99.json"

  per_pod() { jq -r --argjson pods "$pods" --arg scale "$2" \
    '[.data.result[] | select(.metric.pod as $p | $pods | index($p)) | (.value[1] | tonumber * ($scale | tonumber))] | map(round) | join(" / ")' "$1"; }
  counts=$(per_pod "$OUT/run-$i-requests.json" 1)
  conns=$(per_pod "$OUT/run-$i-connections.json" 1)
  p99s=$(per_pod "$OUT/run-$i-p99.json" 1000)
  skew=$(jq -r --argjson pods "$pods" \
    '[.data.result[] | select(.metric.pod as $p | $pods | index($p)) | .value[1] | tonumber]
     | if length == 0 then "n/a" else ((max - min) / (add / length) * 100 | round | tostring + "%") end' \
    "$OUT/run-$i-requests.json")
  k6p99=$(grep -m1 'http_req_duration' "$OUT/run-$i-k6.txt" | grep -o 'p(99)=[^ ]*' | cut -d= -f2 || echo n/a)

  echo "| $i | $counts | $skew | $conns | $p99s | ${k6p99:-n/a} |" | tee -a "$OUT/summary.md"
done

echo "Results: $OUT"
