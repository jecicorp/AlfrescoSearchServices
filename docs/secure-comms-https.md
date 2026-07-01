# Secure Communications: `https` / mTLS mode

This document covers the `secureComms=https` (mutual TLS) operating mode for the
Pristy fork of Alfresco Search Services.  The `secret` mode (shared HTTP header) is
documented inline in the dev-stack (`docker-compose.dev.yml`).

---

## 1. The four TLS links

```
┌────────────┐  L1: search REST  ┌──────────┐
│  Alfresco  │ ────────────────► │   Solr   │ :8983 (HTTPS, client cert)
│  (ALF)     │                   │          │
│            │  L2: SolrJ index  │          │
│            │ ◄──── Trackers ── │          │
└────────────┘        │          └──────────┘
     ▲                │ L3: repo REST (tracker → ALF)
     └────────────────┘
                      │ L4: admin
                      ▼
                  :8085 (HTTPS, client cert)
```

| Link | Direction | Channel | Config owner |
|------|-----------|---------|--------------|
| **L1** | ALF → Solr | search REST via Jetty `8983` | Alfresco (`-Dsolr.secureComms=https`) |
| **L2** | Trackers → Solr | SolrJ indexing via Jetty `8983` | Trackers (`alfresco.tracker.solr.*`) |
| **L3** | Trackers → ALF | repo REST API | Trackers (`alfresco.tracker.repository.*`) |
| **L4** | Admin clients → Trackers | Spring Boot `8085` | Trackers (`server.ssl.*` / `TRACKER_SERVER_SSL_*`) |

All four links use mutual TLS (both sides present a certificate).

---

## 2. Generating certificates

Use the bundled script to create a dev CA and per-role PKCS12 keystores:

```bash
STORE_PASS=changeit ./keystore/generate-keystores.sh ./keystore/out/
```

The script creates:

| File | Used by |
|------|---------|
| `solr.p12` | Solr (keystore) |
| `trackers.p12` | Trackers (keystore, both sides) |
| `alfresco.p12` | Alfresco (keystore) |
| `truststore.p12` | All services (shared truststore, contains the CA cert) |

**SANs**: each leaf cert's `subjectAltName` is set to the service DNS name
(`DNS:solr`, `DNS:trackers`, `DNS:alfresco`).  Adjust for your naming.

### Production (BYO certificates)

For production, replace the self-signed CA with certificates signed by your
organisation's CA or a public CA:

- Generate one key pair per service role (`solr`, `trackers`, `alfresco`).
- Package each key + cert chain into a PKCS12 file.
- Build a truststore containing the issuing CA(s) only.
- **Mount keystores at runtime** — never bake them into Docker images.
- Rotate keystores by updating the mounted files and restarting services.
- Keep `$STORE_PASS` in a secrets manager; never hard-code it.

---

## 3. Solr configuration (L1 + L2 server side)

Solr uses Jetty-native TLS.  Set these environment variables on the `solr` container:

```bash
# Enable TLS on Jetty :8983
SOLR_SSL_ENABLED=true
ALFRESCO_SECURE_COMMS=https          # tells the Alfresco plugin which channel to use

# Solr's own keystore (presented to L1 and L2 clients)
SOLR_SSL_KEY_STORE=/run/secrets/solr.p12
SOLR_SSL_KEY_STORE_PASSWORD=changeit
SOLR_SSL_KEY_STORE_TYPE=PKCS12

# Truststore (must contain the CA that signed trackers.p12 and alfresco.p12)
SOLR_SSL_TRUST_STORE=/run/secrets/truststore.p12
SOLR_SSL_TRUST_STORE_PASSWORD=changeit
SOLR_SSL_TRUST_STORE_TYPE=PKCS12

# Require a client certificate on every inbound connection
SOLR_SSL_NEED_CLIENT_AUTH=true
```

The Solr URL seen by trackers becomes `https://solr:8983/solr` accordingly.

---

## 4. Trackers configuration (L2, L3, L4)

### L2 — Tracker → Solr (SolrJ indexing)

```bash
ALFRESCO_TRACKER_SOLR_URL=https://solr:8983/solr
ALFRESCO_TRACKER_SOLR_SECURECOMMS=https

ALFRESCO_TRACKER_SOLR_SSL_KEY_STORE=/run/secrets/trackers.p12
ALFRESCO_TRACKER_SOLR_SSL_KEY_STORE_PASSWORD=changeit
ALFRESCO_TRACKER_SOLR_SSL_KEY_STORE_TYPE=PKCS12

ALFRESCO_TRACKER_SOLR_SSL_TRUST_STORE=/run/secrets/truststore.p12
ALFRESCO_TRACKER_SOLR_SSL_TRUST_STORE_PASSWORD=changeit
ALFRESCO_TRACKER_SOLR_SSL_TRUST_STORE_TYPE=PKCS12
```

> **Note (Task 2.4 decoupling):** the Solr auth channel uses `ALFRESCO_TRACKER_SOLR_*`
> keys, **not** `ALFRESCO_TRACKER_REPOSITORY_*`.  The two channels are configured
> independently so you can, for example, run `https` on L2 while keeping `secret`
> on L3, or vice-versa.

### L3 — Tracker → Alfresco repository

```bash
ALFRESCO_TRACKER_REPOSITORY_URL=https://alfresco:8080/alfresco
ALFRESCO_TRACKER_REPOSITORY_SECURECOMMS=https

ALFRESCO_TRACKER_REPOSITORY_SSL_KEY_STORE=/run/secrets/trackers.p12
ALFRESCO_TRACKER_REPOSITORY_SSL_KEY_STORE_PASSWORD=changeit
ALFRESCO_TRACKER_REPOSITORY_SSL_KEY_STORE_TYPE=PKCS12

ALFRESCO_TRACKER_REPOSITORY_SSL_TRUST_STORE=/run/secrets/truststore.p12
ALFRESCO_TRACKER_REPOSITORY_SSL_TRUST_STORE_PASSWORD=changeit
ALFRESCO_TRACKER_REPOSITORY_SSL_TRUST_STORE_TYPE=PKCS12
```

### L4 — Admin server (`:8085`)

```bash
TRACKER_SERVER_SSL_ENABLED=true
TRACKER_SERVER_SSL_KEY_STORE=/run/secrets/trackers.p12
TRACKER_SERVER_SSL_KEY_STORE_PASSWORD=changeit
TRACKER_SERVER_SSL_TRUST_STORE=/run/secrets/truststore.p12
TRACKER_SERVER_SSL_TRUST_STORE_PASSWORD=changeit
```

The admin server always requires a client certificate (`client-auth: need`).

---

## 5. Alfresco repository configuration (L1, out of scope)

Alfresco's TLS configuration is standard Alfresco — not in our codebase.
Add these JVM properties to `JAVA_TOOL_OPTIONS` on the `alfresco` container:

```
-Dsolr.secureComms=https
-Dsolr.host=solr
-Dsolr.port=8983
-Dencryption.ssl.keystore.location=/run/secrets/alfresco.p12
-Dencryption.ssl.keystore.password=changeit
-Dencryption.ssl.keystore.type=PKCS12
-Dencryption.ssl.truststore.location=/run/secrets/truststore.p12
-Dencryption.ssl.truststore.password=changeit
-Dencryption.ssl.truststore.type=PKCS12
```

---

## 6. Caddy `:8984` dev proxy — known limitation

The `Caddyfile` in this repo exposes a convenience reverse proxy on `:8984` that
injects the `X-Alfresco-Search-Secret` header so operators can browse the Solr admin
UI without credentials.

**This proxy does not work in `https` mode.**  Solr's Jetty is configured with
`SOLR_SSL_NEED_CLIENT_AUTH=true`, so every connection — including the Caddy
reverse proxy — must present a valid client certificate.  Caddy would need a
`tls_client_auth` block pointing to the `trackers.p12` (or a dedicated `caddy.p12`)
certificate before it can reach Solr.

In `https` mode, either:

- Disable the `:8984` block in `Caddyfile`, and access the Solr UI directly with a
  browser that has the client cert installed, or
- Configure Caddy with `tls_client_auth` pointing to a dedicated client certificate
  signed by the same CA.

---

## 7. Quick-start checklist

1. Generate keystores: `STORE_PASS=changeit ./keystore/generate-keystores.sh ./keystore/out/`
2. Mount the resulting `.p12` files into each container at runtime (Docker secrets
   or volume mounts).
3. Set `SOLR_SSL_ENABLED=true` + `ALFRESCO_SECURE_COMMS=https` + the `SOLR_SSL_*`
   vars on `solr`.
4. Set `ALFRESCO_TRACKER_SOLR_SECURECOMMS=https` + `ALFRESCO_TRACKER_SOLR_SSL_*` on
   the `trackers` container.
5. Set `ALFRESCO_TRACKER_REPOSITORY_SECURECOMMS=https` +
   `ALFRESCO_TRACKER_REPOSITORY_SSL_*` on `trackers`.
6. Set `TRACKER_SERVER_SSL_ENABLED=true` + `TRACKER_SERVER_SSL_*` on `trackers`.
7. Add the Alfresco `-D` props (§5) for L1.
8. Update `ALFRESCO_TRACKER_SOLR_URL` to `https://solr:8983/solr`.
9. Disable or reconfigure the Caddy `:8984` block (§6).
