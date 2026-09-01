# Pristy Search Services ${project.version} Docker Image

# More infos about this image: https://github.com/Alfresco/alfresco-docker-base-java
FROM alfresco/alfresco-base-java:jre17-rockylinux9@sha256:16692e52ef8c3d60606d5c27d95eada9266f9fad8b70f38ad5674a7cbe24e49e
LABEL org.opencontainers.image.title="Pristy Search Services"
LABEL org.opencontainers.image.description="Solr 9 search engine for Pristy ECM - community fork of Alfresco Search Services"
LABEL org.opencontainers.image.vendor="Jeci SARL"
LABEL org.opencontainers.image.licenses="LGPL-3.0-or-later"
LABEL org.opencontainers.image.source="https://gitlab.com/pristy-oss/pristy-search-services"

ENV DIST_DIR=/opt/pristy-search-services
ENV SOLR_ZIP=${project.build.finalName}.zip
ENV LANG=C.UTF-8

# Get values from ENV VARS or use default values if ENV VARS are not specified
ENV SOLR_DATA_DIR_ROOT=$DIST_DIR/data
ENV SOLR_SOLR_MODEL_DIR=$DIST_DIR/data/alfrescoModels
# Dedicated backup location, kept SEPARATE from the index data dir so a full or
# lost data volume does not take the backups with it. Mount a dedicated volume
# here at deploy time. Must be inside solr.allowPaths (set below) for the
# trackers-driven ReplicationHandler backup/restore to be allowed by Solr 9.
ENV SOLR_BACKUP_DIR=/backup/solr

ARG USERNAME=solr
ARG USERID=33007

COPY "$SOLR_ZIP" .

RUN set -x \
   && useradd \
        -c "Alfresco ${USERNAME}" \
        -M \
        -s "/bin/bash" \
        -u "${USERID}" \
        -o \
        "${USERNAME}" \
   && yum install -y unzip \
   && yum install -y lsof ca-certificates procps-ng \
   && yum install -y wget \
   && yum clean all \
   && unzip "$SOLR_ZIP" -d /opt/ && rm "$SOLR_ZIP" \
   && mkdir -p $DIST_DIR/data \
   && mkdir -p $SOLR_BACKUP_DIR \
   && mv $DIST_DIR/solrhome/alfrescoModels $DIST_DIR/data/ \
   && chown -R ${USERNAME}:${USERNAME} $DIST_DIR $SOLR_BACKUP_DIR \
   && echo '#Docker Setup' >> $DIST_DIR/solr.in.sh \
   && echo "SOLR_OPTS=\"\$SOLR_OPTS -Dsolr.data.dir.root=\$SOLR_DATA_DIR_ROOT -Dsolr.solr.model.dir=\$SOLR_SOLR_MODEL_DIR -Dsolr.allowPaths=\$DIST_DIR/solrhome,\$SOLR_BACKUP_DIR\"" >> $DIST_DIR/solr.in.sh \
   && echo '# Solr 9 ships ICU analysis (used by the Alfresco schema) in the analysis-extras module, not on the default classpath' >> $DIST_DIR/solr.in.sh \
   && echo 'SOLR_MODULES=analysis-extras' >> $DIST_DIR/solr.in.sh \
   && echo '# Solr 9 binds to 127.0.0.1 by default; bind to all interfaces so the container is reachable' >> $DIST_DIR/solr.in.sh \
   && echo 'SOLR_JETTY_HOST=0.0.0.0' >> $DIST_DIR/solr.in.sh \
   && echo '# Solr 9 enables the Java SecurityManager by default; the Alfresco plugins need unrestricted file/network access' >> $DIST_DIR/solr.in.sh \
   && echo 'SOLR_SECURITY_MANAGER_ENABLED=false' >> $DIST_DIR/solr.in.sh

COPY search_config_setup.sh $DIST_DIR/solr/bin/
RUN chmod +x $DIST_DIR/solr/bin/search_config_setup.sh

COPY solr-init-core.sh $DIST_DIR/
RUN chmod +x $DIST_DIR/solr-init-core.sh

# Add the licenses to a root directory.
RUN mv $DIST_DIR/licenses /licenses

WORKDIR $DIST_DIR

VOLUME $DIST_DIR/data
VOLUME $DIST_DIR/solrhome
# Backup target — mount a DEDICATED volume (separate physical disk) here so
# index backups survive a lost or saturated data volume.
VOLUME $SOLR_BACKUP_DIR
# TLS / mTLS (secureComms=https). Solr's launcher reads these natively.
# Keystores are NOT baked into the image — mount them at runtime into this dir
# (bind mount in dev/compose, orchestrator secret in prod) and set at runtime:
#   SOLR_SSL_KEY_STORE=/opt/pristy-search-services/keystore/solr.p12
#   SOLR_SSL_KEY_STORE_PASSWORD=..., SOLR_SSL_KEY_STORE_TYPE=PKCS12
#   SOLR_SSL_TRUST_STORE=/opt/pristy-search-services/keystore/truststore.p12
#   SOLR_SSL_TRUST_STORE_PASSWORD=..., SOLR_SSL_TRUST_STORE_TYPE=PKCS12
#   SOLR_SSL_NEED_CLIENT_AUTH=true
RUN mkdir -p /opt/pristy-search-services/keystore
VOLUME ["/opt/pristy-search-services/keystore"]
# Only the non-secret store TYPE is defaulted here; paths/passwords come at runtime.
ENV SOLR_SSL_KEY_STORE_TYPE=PKCS12 \
    SOLR_SSL_TRUST_STORE_TYPE=PKCS12

# SOLR Service Port
EXPOSE 8983

USER ${USERNAME}
CMD $DIST_DIR/solr/bin/search_config_setup.sh "$DIST_DIR/solr/bin/solr start -f"
