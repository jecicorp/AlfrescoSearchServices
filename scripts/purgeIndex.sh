#!/bin/sh
# DEV helper: purge the Solr alfresco core index and restart the trackers to
# force a full re-index. Targets the docker-compose dev stack by default.
#
# Usage: ./scripts/purgeIndex.sh [solr_url] [compose_file]
# Default: http://localhost:8984/solr, docker-compose.dev.yml
#
# The dev stack runs Solr with secureComms=secret, so every request must carry
# the X-Alfresco-Search-Secret header. Override the secret with SEARCH_SECRET.
set -e

SOLR_URL="${1:-http://localhost:8984/solr}"
COMPOSE_FILE="${2:-docker-compose.dev.yml}"
SEARCH_SECRET="${SEARCH_SECRET:-secret}"

echo "Purging index on ${SOLR_URL}/alfresco ..."
HTTP_CODE=$(curl -s -o /tmp/purgeIndex.out -w '%{http_code}' \
  "${SOLR_URL}/alfresco/update?commit=true" \
  -H "X-Alfresco-Search-Secret: ${SEARCH_SECRET}" \
  -H 'Content-Type: text/xml' \
  -d '<delete><query>*:*</query></delete>')

if [ "$HTTP_CODE" != "200" ]; then
  echo "Purge FAILED (HTTP ${HTTP_CODE}); not restarting the trackers." >&2
  cat /tmp/purgeIndex.out >&2
  exit 1
fi
echo "Index purged."

echo "Restarting trackers to reset in-memory state ..."
docker compose -f "${COMPOSE_FILE}" restart trackers

echo "Done. Trackers will re-index from the beginning."
