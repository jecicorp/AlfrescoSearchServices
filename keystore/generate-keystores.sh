#!/usr/bin/env bash
# Generates a dev CA and PKCS12 keystores/truststore for secureComms=https (mTLS).
# Each leaf cert carries SANs for every hostname it may be reached under: the
# compose service name in the e2e stack (solr is "search") AND in pristy-demo
# (repo is "acs"), plus localhost for host-side access via mapped ports.
# Usage: STORE_PASS=changeit ./generate-keystores.sh out/
set -eu
OUT="${1:-./out}"
PASS="${STORE_PASS:-changeit}"
mkdir -p "$OUT"
cd "$OUT"

# Idempotency: start from a clean set so re-runs don't fail on an already
# existing truststore alias, and never mix a stale CA with freshly signed
# leaf certs (which would break the trust chain).
rm -f ca.key ca.crt ca.srl truststore.p12 solr.* trackers.* alfresco.*

# 1. Root CA
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
  -keyout ca.key -out ca.crt -subj "/CN=Pristy-Dev-CA"

# 2. Shared truststore (everyone trusts the CA)
keytool -importcert -noprompt -alias pristy-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass "$PASS"

# 3. Per-role leaf certs signed by the CA. SANs cover the DNS names the service
#    is reached under across stacks, plus localhost.
for ROLE in solr trackers alfresco; do
  case "$ROLE" in
    solr)     SAN="DNS:solr,DNS:search,DNS:localhost" ;;
    alfresco) SAN="DNS:alfresco,DNS:acs,DNS:localhost" ;;
    *)        SAN="DNS:$ROLE,DNS:localhost" ;;
  esac
  openssl req -newkey rsa:2048 -nodes -keyout "$ROLE.key" -out "$ROLE.csr" \
    -subj "/CN=$ROLE"
  openssl x509 -req -in "$ROLE.csr" -CA ca.crt -CAkey ca.key -CAcreateserial \
    -days 3650 -sha256 -extfile <(printf "subjectAltName=%s" "$SAN") -out "$ROLE.crt"
  openssl pkcs12 -export -inkey "$ROLE.key" -in "$ROLE.crt" -certfile ca.crt \
    -name "$ROLE" -out "$ROLE.p12" -passout "pass:$PASS"
done
echo "Generated keystores in $OUT (password: $PASS)"
