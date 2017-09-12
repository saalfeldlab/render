FROM openjdk:8-jdk
MAINTAINER Forrest Collman (forrestc@alleninstitute.org)

RUN apt-get update && apt-get install -y maven

WORKDIR /var/www/render/
ADD pom.xml .
ADD render-ws/pom.xml render-ws/pom.xml
ADD render-ws-java-client/pom.xml render-ws-java-client/pom.xml
ADD render-ws-spark-client/pom.xml render-ws-spark-client/pom.xml
ADD render-app/pom.xml render-app/pom.xml
ADD trakem2-scripts/pom.xml trakem2-scripts/pom.xml
ADD docs/pom.xml docs/pom.xml
RUN mvn verify clean --fail-never
COPY . /var/www/render/
RUN mvn clean
RUN mvn -Dproject.build.sourceEncoding=UTF-8 package && \
 rm -rf /tmp/* && \
 rm -rf render-ws/target/test-classes && \
 rm -rf render-app/target/test-classes && \
 rm -rf render-ws/target/test-classes && \
 rm -rf render-ws-java-client/target/test-classes && \
 rm -rf render-ws-spark-client/target/test-classes && \
 rm -rf /root/.embedmongo

