ARG ACS_TAG=25.3.0
FROM alfresco/alfresco-content-repository-community:${ACS_TAG}

ENV TOMCAT_HOME="/usr/local/tomcat"

USER root

RUN dnf update -y java-17-openjdk-headless

# Copy keystore files (JCEKS format from pristy-demo)
COPY ./assets/alfresco/keystore/keystore ${TOMCAT_HOME}/webapps/alfresco/WEB-INF/classes/alfresco/keystore/keystore
COPY ./assets/alfresco/keystore/keystore-passwords.properties ${TOMCAT_HOME}/webapps/alfresco/WEB-INF/classes/alfresco/keystore/keystore-passwords.properties

RUN chown -R alfresco:alfresco ${TOMCAT_HOME}/webapps/alfresco/WEB-INF/classes/alfresco/keystore/

USER alfresco

# Keystore JAR + Spring context that disables key validation (dev only)
COPY ./assets/alfresco-keystore.jar ${TOMCAT_HOME}/webapps/alfresco/WEB-INF/lib/
COPY ./assets/keystore-context.xml ${TOMCAT_HOME}/shared/classes/alfresco/extension/

# Reduce log noise (healthcheck probes, repo admin)
COPY ./assets/dev-log4j2.properties ${TOMCAT_HOME}/shared/classes/alfresco/extension/
