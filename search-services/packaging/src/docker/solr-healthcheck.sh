#!/bin/sh
# Docker healthcheck for the Solr container — docs/solr9-admin-guide.md §Container health.

PORT="${SOLR_PORT:-8983}"
PATH_INFO="/solr/admin/info/health?wt=json"
if [ "${SOLR_HEALTHCHECK_REQUIRE_HEALTHY_CORES}" = "true" ]; then
    PATH_INFO="${PATH_INFO}&requireHealthyCores=true"
fi
TIMEOUT="${SOLR_HEALTHCHECK_TIMEOUT:-3}"

# 403 is alive: no secureComms mode exempts a path, and the probe holds no secret.
alive() {
    case "$1" in
        200|403) return 0;;
        *) return 1;;
    esac
}

probe() {
    curl -s -o /dev/null -w '%{http_code}' --max-time "${TIMEOUT}" "$@" 2>/dev/null
}

if alive "$(probe "http://localhost:${PORT}${PATH_INFO}")"; then
    exit 0
fi

if [ -z "${SOLR_SSL_KEY_STORE}" ]; then
    exit 1
fi

if alive "$(probe -k "https://localhost:${PORT}${PATH_INFO}")"; then
    exit 0
fi

# clientAuth=need: the node's keystore must carry the clientAuth usage.
if [ -n "${SOLR_SSL_KEY_STORE_PASSWORD}" ] && alive "$(probe -k \
        --cert-type P12 --cert "${SOLR_SSL_KEY_STORE}:${SOLR_SSL_KEY_STORE_PASSWORD}" \
        "https://localhost:${PORT}${PATH_INFO}")"; then
    exit 0
fi

exit 1
