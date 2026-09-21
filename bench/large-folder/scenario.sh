#!/usr/bin/env bash
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${BENCH_DIR}/../.." && pwd)"

LABEL=""
PROFILE="flat"
TOTAL=30000
WORKERS=8
PHASES="a,b"
TUNING=""
ACL_COUNT=500
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
  --phases LIST         a=initial indexing, b=purge + full reindex,
                        c=ACL churn, d=folder rename (cascade)   (default: a,b)
  --tuning LIST         comma-separated tracker tuning overrides applied to the trackers
                        container for this run, e.g.
                        --tuning acl-parallelism=8,cascade-parallelism=8
  --acl-count N         phase c: nodes given a distinct local permission (default: 500)
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
    --tuning) TUNING="$2"; shift 2;;
    --acl-count) ACL_COUNT="$2"; shift 2;;
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

# Tracker tuning is bound from the environment by Spring's relaxed binding, so a run can
# set it without rebuilding the image: alfresco.tracker.tuning.acl-parallelism becomes
# ALFRESCO_TRACKER_TUNING_ACL_PARALLELISM. The values land in a compose override so the
# report records what was actually measured.
if [ -n "${TUNING}" ]; then
  OVERRIDE="${RUN_DIR}/compose-override.yml"
  {
    echo "services:"
    echo "  trackers:"
    echo "    environment:"
  } > "${OVERRIDE}"
  echo "{" > "${RUN_DIR}/tuning.json"
  FIRST=1
  IFS=',' read -r -a SETTINGS <<< "${TUNING}"
  for setting in "${SETTINGS[@]}"; do
    key="${setting%%=*}"
    value="${setting#*=}"
    if [ "${key}" = "${setting}" ] || [ -z "${value}" ]; then
      echo "--tuning expects key=value, got '${setting}'" >&2
      exit 2
    fi
    env_key="ALFRESCO_TRACKER_TUNING_$(echo "${key}" | tr 'a-z-' 'A-Z_')"
    echo "      ${env_key}: \"${value}\"" >> "${OVERRIDE}"
    if [ ${FIRST} -eq 0 ]; then
      echo "," >> "${RUN_DIR}/tuning.json"
    fi
    printf '  "%s": "%s"' "${key}" "${value}" >> "${RUN_DIR}/tuning.json"
    FIRST=0
  done
  printf '\n}\n' >> "${RUN_DIR}/tuning.json"

  echo "==> applying tuning: ${TUNING}"
  cat "${OVERRIDE}"
  ( cd "${REPO_ROOT}" && docker compose -f "${COMPOSE_FILE}" -f "${OVERRIDE}" up -d trackers )
  echo "    trackers recreated; check the startup 'tuning:' line to confirm it took effect:"
  echo "    docker compose -f ${COMPOSE_FILE} logs trackers | grep -m1 'tuning:'"
  sleep 5
fi

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
case ",${PHASES}," in
  *,a,*)
    TARGET=$(( BASELINE_NODES + TOTAL + 1 ))
    echo "    baseline Node docs: ${BASELINE_NODES}; target after creation: ${TARGET}"
    ;;
  *)
    TARGET=${BASELINE_NODES}
    echo "    baseline Node docs: ${BASELINE_NODES}; re-indexing an existing tree, target: ${TARGET}"
    if [ "${TARGET}" -le 1 ]; then
      echo "    the index is empty and no tree is being created — nothing to re-index" >&2
      exit 1
    fi
    ;;
esac

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

case ",${PHASES}," in
  *,c,*)
    echo "==> phase C — ACL churn on ${ACL_COUNT} nodes"
    BASELINE_ACLS="$(json_field "${RUN_DIR}/baseline.json" Acl)"
    TARGET_ACLS=$(( BASELINE_ACLS + ACL_COUNT ))
    echo "    baseline Acl docs: ${BASELINE_ACLS}; target: ${TARGET_ACLS}"

    python3 "${BENCH_DIR}/sample_index.py" $(sampler_args) \
      --until-acls "${TARGET_ACLS}" --stable-for "${STABLE_FOR}" \
      --out "${RUN_DIR}/phase-c.csv" &
    SAMPLER_PID=$!
    trap 'kill ${SAMPLER_PID} 2>/dev/null || true' EXIT

    python3 "${BENCH_DIR}/churn.py" acl \
      --manifest "${RUN_DIR}/manifest.json" --repo-url "${REPO_URL}" \
      --user "${USER_NAME}" --password "${PASSWORD}" \
      --count "${ACL_COUNT}" --workers "${WORKERS}" \
      --out "${RUN_DIR}/acl-churn.json"

    echo "    permissions applied; waiting for the AclTracker to catch up"
    wait ${SAMPLER_PID} || echo "    sampler exited with status $? (see phase-c.csv)"
    trap - EXIT
    ;;
esac

case ",${PHASES}," in
  *,d,*)
    echo "==> phase D — rename the benchmark root (cascade)"
    python3 "${BENCH_DIR}/churn.py" rename \
      --manifest "${RUN_DIR}/manifest.json" --repo-url "${REPO_URL}" \
      --user "${USER_NAME}" --password "${PASSWORD}" \
      --out "${RUN_DIR}/rename.json"
    NEW_NAME="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["new_name"])' "${RUN_DIR}/rename.json")"
    echo "    renamed to ${NEW_NAME}; waiting for the CascadeTracker to rewrite the paths"

    python3 "${BENCH_DIR}/sample_index.py" $(sampler_args) \
      --stable-for "${STABLE_FOR}" --out "${RUN_DIR}/phase-d.csv" \
      || echo "    sampler exited with status $? (see phase-d.csv)"

    echo "    verifying the cascade actually propagated"
    python3 "${BENCH_DIR}/churn.py" verify \
      --manifest "${RUN_DIR}/manifest.json" --name "${NEW_NAME}" \
      --solr-url "${SOLR_URL}" --core "${CORE}" \
      --out "${RUN_DIR}/cascade-verify.json" \
      || echo "    WARNING: the cascade did not reach every descendant (see cascade-verify.json)"
    ;;
esac

echo "==> report"
python3 "${BENCH_DIR}/report.py" "${RUN_DIR}" --out "${RUN_DIR}/summary.md"
cat "${RUN_DIR}/summary.md"
