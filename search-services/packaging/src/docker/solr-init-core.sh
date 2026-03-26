#!/bin/sh
# Script to create Solr cores if they don't exist
# Used as a Docker entrypoint wrapper
# Supports comma-separated core names in SOLR_CREATE_ALFRESCO_DEFAULTS (e.g. "alfresco,archive")

SOLR_HOME="/opt/alfresco-search-services/solrhome"
CORE_LIST="${SOLR_CREATE_ALFRESCO_DEFAULTS:-alfresco}"
TEMPLATE="${SOLR_TEMPLATE:-rerank}"
DATA_DIR="/opt/alfresco-search-services/data"

# Split comma-separated core names and create each one
IFS=','
for CORE_NAME in $CORE_LIST; do
    if [ ! -d "${SOLR_HOME}/${CORE_NAME}/conf" ]; then
        echo "Creating core '${CORE_NAME}' from template '${TEMPLATE}'..."
        mkdir -p "${SOLR_HOME}/${CORE_NAME}"
        cp -r "${SOLR_HOME}/templates/${TEMPLATE}/conf" "${SOLR_HOME}/${CORE_NAME}/conf"

        # Configure solrcore.properties
        PROPS="${SOLR_HOME}/${CORE_NAME}/conf/solrcore.properties"
        sed -i "s|#data.dir.root=DATA_DIR|data.dir.root=${DATA_DIR}|" "$PROPS"

        # Configure store based on core name
        case "${CORE_NAME}" in
            archive)
                sed -i 's|#data.dir.store=workspace/SpacesStore|data.dir.store=archive/SpacesStore|' "$PROPS"
                sed -i 's|#alfresco.stores=workspace://SpacesStore|alfresco.stores=archive://SpacesStore|' "$PROPS"
                ;;
            *)
                sed -i 's|#data.dir.store=workspace/SpacesStore|data.dir.store=workspace/SpacesStore|' "$PROPS"
                sed -i 's|#alfresco.stores=workspace://SpacesStore|alfresco.stores=workspace://SpacesStore|' "$PROPS"
                ;;
        esac

        # Configure secure comms
        case "${ALFRESCO_SECURE_COMMS}" in
            secret)
                sed -i 's|alfresco.secureComms=https|alfresco.secureComms=secret|' "$PROPS"
                ;;
            none)
                sed -i 's|alfresco.secureComms=https|alfresco.secureComms=none|' "$PROPS"
                ;;
        esac

        # Configure Alfresco host
        if [ -n "${SOLR_ALFRESCO_HOST}" ]; then
            sed -i "s|alfresco.host=localhost|alfresco.host=${SOLR_ALFRESCO_HOST}|" "$PROPS"
        fi
        if [ -n "${SOLR_ALFRESCO_PORT}" ]; then
            sed -i "s|alfresco.port=8080|alfresco.port=${SOLR_ALFRESCO_PORT}|" "$PROPS"
        fi

        # Create core.properties so Solr auto-discovers the core on startup
        cat > "${SOLR_HOME}/${CORE_NAME}/core.properties" <<EOF
name=${CORE_NAME}
EOF

        echo "Core '${CORE_NAME}' created."
    else
        echo "Core '${CORE_NAME}' already exists."
    fi
done
unset IFS

# Start Solr (exec replaces the shell with solr process)
exec /opt/alfresco-search-services/solr/bin/solr start -f
