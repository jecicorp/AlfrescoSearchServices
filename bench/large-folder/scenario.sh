#!/usr/bin/env bash
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${BENCH_DIR}/../.." && pwd)"

LABEL=""
PROFILE="flat"
TOTAL=30000
WORKERS=8
PHASES="a,b"
REPO_URL="http://localhost:8080/alfresco"
SOLR_URL="http://localhost:8984/solr"
TRACKERS_URL="http://localhost:8085"
CORE="alfresco"
USER_NAME="admin"
PASSWORD="admin"
INTERVAL=5
TIMEOUT=7200
STABLE_FOR=300
COMPOSE_FILE="docker-compose.dev.yml"
COMPOSE_PROJECT="searchservices"
CONTAINERS=""

usage() {
  cat <<'USAGE'
Usage: scenario.sh --label <name> [options]

  --label NAME          run directory under bench/large-folder/runs (required)
  --profile NAME        flat | deep | mixed            (default: flat)
  --total N             nodes to create                (default: 30000)
  --workers N           parallel creation workers      (default: 8)
  --phases LIST         a=initial indexing, b=purge + full reindex (default: a,b)
  --repo-url URL        (default: http://localhost:8080/alfresco)
  --solr-url URL        (default: http://localhost:8984/solr, Caddy injects the secret)
  --trackers-url URL    (default: http://localhost:8085)
  --core NAME           (default: alfresco)
  --user / --password   repository credentials         (default: admin/admin)
  --interval S          sampling interval              (default: 5)
  --timeout S           per-phase sampling timeout     (default: 7200)
  --stable-for S        give up when the index stops moving for this long (default: 300)
  --containers LIST     comma-separated container names to sample
  --compose-project NAME  used to derive default container names (default: searchservices)
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --label) LABEL="$2"; shift 2;;
    --profile) PROFILE="$2"; shift 2;;
    --total) TOTAL="$2"; shift 2;;
    --workers) WORKERS="$2"; shift 2;;
    --phases) PHASES="$2"; shift 2;;
    --repo-url) REPO_URL="$2"; shift 2;;
    --solr-url) SOLR_URL="$2"; shift 2;;
    --trackers-url) TRACKERS_URL="$2"; shift 2;;
    --core) CORE="$2"; shift 2;;
    --user) USER_NAME="$2"; shift 2;;
    --password) PASSWORD="$2"; shift 2;;
    --interval) INTERVAL="$2"; shift 2;;
    --timeout) TIMEOUT="$2"; shift 2;;
    --stable-for) STABLE_FOR="$2"; shift 2;;
    --containers) CONTAINERS="$2"; shift 2;;
    --compose-project) COMPOSE_PROJECT="$2"; shift 2;;
    -h|--help) usage; exit 0;;
    *) echo "unknown option: $1" >&2; usage; exit 2;;
  esac
done

if [ -z "${LABEL}" ]; then
  echo "--label is required" >&2
  usage
  exit 2
fi

if [ -z "${CONTAINERS}" ]; then
  CONTAINERS="${COMPOSE_PROJECT}-solr-1,${COMPOSE_PROJECT}-trackers-1,${COMPOSE_PROJECT}-alfresco-1"
fi

RUN_DIR="${BENCH_DIR}/runs/${LABEL}"
mkdir -p "${RUN_DIR}"

sampler_args() {
  echo "--solr-url ${SOLR_URL} --trackers-url ${TRACKERS_URL} --core ${CORE}" \
       "--interval ${INTERVAL} --timeout ${TIMEOUT} --containers ${CONTAINERS}"
}

json_field() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2]) or 0)' "$1" "$2"
}

echo "==> baseline"
python3 "${BENCH_DIR}/sample_index.py" $(sampler_args) --once > "${RUN_DIR}/baseline.json"
python3 - "${RUN_DIR}/baseline.json" <<'CHECK' || exit 1
import json, sys
data = json.load(open(sys.argv[1]))
if data.get("solr_error") or data.get("trackers_error") or data.get("Node") is None:
    sys.stderr.write("baseline sample failed, aborting:\n")
    json.dump(data, sys.stderr, indent=2, sort_keys=True)
    sys.stderr.write("\n")
    sys.exit(1)
remaining = data.get("tx_remaining")
if remaining:
    sys.stderr.write("    WARNING: {} transactions still pending — the index is not "
                     "caught up, the baseline is a moving target\n".format(remaining))
CHECK

BASELINE_NODES="$(json_field "${RUN_DIR}/baseline.json" Node)"
TARGET=$(( BASELINE_NODES + TOTAL + 1 ))
echo "    baseline Node docs: ${BASELINE_NODES}; target after creation: ${TARGET}"

case ",${PHASES}," in
  *,a,*)
    echo "==> phase A — create the tree while the trackers run"
    python3 "${BENCH_DIR}/sample_index.py" $(sampler_args) \
      --until-nodes "${TARGET}" --stable-for "${STABLE_FOR}" \
      --out "${RUN_DIR}/phase-a.csv" &
    SAMPLER_PID=$!
    trap 'kill ${SAMPLER_PID} 2>/dev/null || true' EXIT

    python3 "${BENCH_DIR}/generate_tree.py" \
      --repo-url "${REPO_URL}" --user "${USER_NAME}" --password "${PASSWORD}" \
      --profile "${PROFILE}" --total "${TOTAL}" --workers "${WORKERS}" \
      --root-name "bench-${LABEL}" --resume \
      --manifest "${RUN_DIR}/manifest.json"

    echo "    tree created; waiting for the index to catch up"
    wait ${SAMPLER_PID} || echo "    sampler exited with status $? (see phase-a.csv)"
    trap - EXIT
    ;;
esac

case ",${PHASES}," in
  *,b,*)
    echo "==> phase B — purge the core and re-index everything"
    ( cd "${REPO_ROOT}" && ./scripts/purgeIndex.sh "${SOLR_URL}" "${COMPOSE_FILE}" )

    python3 "${BENCH_DIR}/sample_index.py" $(sampler_args) \
      --until-nodes "${TARGET}" --stable-for "${STABLE_FOR}" \
      --out "${RUN_DIR}/phase-b.csv" || echo "    sampler exited with status $? (see phase-b.csv)"
    ;;
esac

echo "==> report"
python3 "${BENCH_DIR}/report.py" "${RUN_DIR}" --out "${RUN_DIR}/summary.md"
cat "${RUN_DIR}/summary.md"
