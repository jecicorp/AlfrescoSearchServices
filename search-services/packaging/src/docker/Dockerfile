# Alfresco Search Services ${project.version} Docker Image

# More infos about this image: https://github.com/Alfresco/alfresco-docker-base-java
FROM alfresco/alfresco-base-java:jre17-rockylinux9@sha256:16692e52ef8c3d60606d5c27d95eada9266f9fad8b70f38ad5674a7cbe24e49e
LABEL creator="Alfresco" maintainer="Alfresco"

ENV DIST_DIR=/opt/alfresco-search-services
ENV SOLR_ZIP=${project.build.finalName}.zip
ENV LANG=C.UTF-8

# Get values from ENV VARS or use default values if ENV VARS are not specified
ENV SOLR_DATA_DIR_ROOT=$DIST_DIR/data
ENV SOLR_SOLR_MODEL_DIR=$DIST_DIR/data/alfrescoModels

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
   && mv $DIST_DIR/solrhome/alfrescoModels $DIST_DIR/data/ \
   && chown -R ${USERNAME}:${USERNAME} $DIST_DIR \
   && echo '#Docker Setup' >> $DIST_DIR/solr.in.sh \
   && echo 'SOLR_OPTS="$SOLR_OPTS -Dsolr.data.dir.root=$SOLR_DATA_DIR_ROOT -Dsolr.solr.model.dir=$SOLR_SOLR_MODEL_DIR -Dsolr.allowPaths=$DIST_DIR/solrhome"' >> $DIST_DIR/solr.in.sh \
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
# Expose a folder to mount keystores in the host (required for Mutual TLS Auth)
VOLUME $DIST_DIR/keystores

# SOLR Service Port
EXPOSE 8983

USER ${USERNAME}
CMD $DIST_DIR/solr/bin/search_config_setup.sh "$DIST_DIR/solr/bin/solr start -f"
