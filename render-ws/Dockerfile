FROM jetty:9.4.6-jre8-alpine
MAINTAINER Forrest Collman (forrestc@alleninstitute.org)

# see https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md

#RUN apt-get update
ENV LOGBACK_VERSION="1.1.5"
ENV SLF4J_VERSION="1.7.16"
ENV SWAGGER_UI_VERSION="2.1.4"
ENV SLF4J_URL="https://www.slf4j.org/dist/slf4j-${SLF4J_VERSION}.tar.gz"
ENV LOGBACK_URL="https://logback.qos.ch/dist/logback-${LOGBACK_VERSION}.tar.gz"
ENV SWAGGER_UI_URL="https://github.com/swagger-api/swagger-ui/archive/v${SWAGGER_UI_VERSION}.tar.gz"
#RUN echo y | java -jar "$JETTY_HOME/start.jar" --add-to-startd=slf4j-logback
#RUN echo y | java -jar "$JETTY_HOME/start.jar" --add-to-startd=logging-slf4j 
WORKDIR /root
ENV JETTY_LIB_LOGGING="${JETTY_BASE}/lib/ext"
RUN mkdir -p $JETTY_LIB_LOGGING
ENV SWAGGER_UI_DEPLOY_DIR="${JETTY_BASE}/webapps/swagger-ui"
RUN apk add --update curl && \
    rm -rf /var/cache/apk/*
RUN  curl ${SLF4J_URL} | tar xz && \
 curl ${LOGBACK_URL} | tar xz && \
 curl -L ${SWAGGER_UI_URL} | tar xz && \
 cp logback-${LOGBACK_VERSION}/logback-access-${LOGBACK_VERSION}.jar ${JETTY_LIB_LOGGING} &&\
 cp logback-${LOGBACK_VERSION}/logback-classic-${LOGBACK_VERSION}.jar ${JETTY_LIB_LOGGING} &&\
 cp logback-${LOGBACK_VERSION}/logback-core-${LOGBACK_VERSION}.jar ${JETTY_LIB_LOGGING} &&\
 cp slf4j-${SLF4J_VERSION}/jcl-over-slf4j-${SLF4J_VERSION}.jar ${JETTY_LIB_LOGGING} &&\
 cp slf4j-${SLF4J_VERSION}/jul-to-slf4j-${SLF4J_VERSION}.jar ${JETTY_LIB_LOGGING} &&\
 cp slf4j-${SLF4J_VERSION}/log4j-over-slf4j-${SLF4J_VERSION}.jar ${JETTY_LIB_LOGGING} &&\
 cp slf4j-${SLF4J_VERSION}/slf4j-api-${SLF4J_VERSION}.jar ${JETTY_LIB_LOGGING} &&\
 cp -r swagger-ui-${SWAGGER_UI_VERSION}/dist ${SWAGGER_UI_DEPLOY_DIR} &&\
 rm -rf slf4j-${SLF4J_VERSION} &&\
 rm -rf logback-${LOGBACK_VERSION} &&\
 rm -rf v${SWAGGER_UI_VERSION} 

COPY src/main/scripts/fix_swagger.sh ${SWAGGER_UI_DEPLOY_DIR}
RUN cd ${SWAGGER_UI_DEPLOY_DIR} && ./fix_swagger.sh
EXPOSE 8080

#ENV PATH $JAVA_HOME/bin:$PATH
#ENV JAVA $JAVA_HOME/bin/java

# small 4GB server:

ENV JAVA_OPTIONS="-Xms3g -Xmx3g -server -Djava.awt.headless=true"

# larger 16GB server
#ENV JAVA_OPTIONS="-Xms5g -Xmx5g -server -Djava.awt.headless=true"

# super 500GB server
#ENV JAVA_OPTIONS="-Xms400g -Xmx400g -server -Djava.awt.headless=true"

# setup supervisor
#RUN mkdir -p /var/log/supervisor

#COPY supervisord.conf /etc/supervisor/conf.d/supervisord.conf
#CMD ["/usr/bin/supervisord"]

# clone render repo
WORKDIR /var/lib/jetty
COPY docker_jetty/ .
COPY docker_jetty/lib/logging/*.jar $JETTY_LIB_LOGGING
COPY target/render-ws-*.war webapps/render-ws.war
RUN mkdir -p logs && chgrp jetty logs && chmod 777 logs
#RUN echo y | java -jar "$JETTY_HOME/start.jar" --add-to-startd=logging-log4j 

