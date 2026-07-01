#!/usr/bin/env bash
# Generates a dev CA and PKCS12 keystores/truststore for secureComms=https (mTLS).
# Usage: STORE_PASS=changeit ./generate-keystores.sh out/
set -eu
OUT="${1:-./out}"
PASS="${STORE_PASS:-changeit}"
mkdir -p "$OUT"
cd "$OUT"

# 1. Root CA
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
  -keyout ca.key -out ca.crt -subj "/CN=Pristy-Dev-CA"

# 2. Shared truststore (everyone trusts the CA)
keytool -importcert -noprompt -alias pristy-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass "$PASS"

# 3. Per-role leaf certs signed by the CA
for ROLE in solr trackers alfresco; do
  openssl req -newkey rsa:2048 -nodes -keyout "$ROLE.key" -out "$ROLE.csr" \
    -subj "/CN=$ROLE"
  openssl x509 -req -in "$ROLE.csr" -CA ca.crt -CAkey ca.key -CAcreateserial \
    -days 3650 -sha256 -extfile <(printf "subjectAltName=DNS:%s" "$ROLE") -out "$ROLE.crt"
  openssl pkcs12 -export -inkey "$ROLE.key" -in "$ROLE.crt" -certfile ca.crt \
    -name "$ROLE" -out "$ROLE.p12" -passout "pass:$PASS"
done
echo "Generated keystores in $OUT (password: $PASS)"
