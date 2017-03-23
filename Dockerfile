FROM ubuntu:16.04
MAINTAINER Forrest Collman (forrestc@alleninstitute.org)

RUN apt-get update
RUN apt-get -y upgrade
# dependencies for render
RUN apt-get install -y git \
  maven \
  curl \
  supervisor

# see https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md

# clone render repo
WORKDIR /var/www/

RUN git clone https://github.com/saalfeldlab/render.git

WORKDIR /var/www/render/

# install JDK and Jetty
RUN ./render-ws/src/main/scripts/install.sh

# set java home
RUN { echo 'JAVA_HOME="$(readlink -m ./deploy/jdk*)"'; } >> ~/.mavenrc

RUN mvn -Dproject.build.sourceEncoding=UTF-8 package -DskipTests
COPY . /var/www/render/

# set java home
RUN { echo 'JAVA_HOME="$(readlink -m ./deploy/jdk*)"'; } >> ~/.mavenrc

# build render modules
#RUN mvn package
RUN mvn -Dproject.build.sourceEncoding=UTF-8 package

# deploy the web service
RUN cp render-ws/target/render-ws-*.war deploy/jetty_base/webapps/render-ws.war

# expose the render port
EXPOSE 8080

# setup jetty (copy of render/deploy/jetty_base/jetty_wrapper.sh)
ENV JETTY_HOME /var/www/render/deploy/jetty-distribution-9.3.7.v20160115
ENV JETTY_BASE /var/www/render/deploy/jetty_base
ENV JETTY_RUN $JETTY_BASE/logs
ENV JETTY_PID $JETTY_RUN/jetty.pid
ENV JETTY_STATE $JETTY_RUN/jetty.state

ENV JAVA_HOME /var/www/render/deploy/jdk1.8.0_73
ENV PATH $JAVA_HOME/bin:$PATH
ENV JAVA $JAVA_HOME/bin/java

# small 4GB server:
#ENV JAVA_OPTIONS="-Xms3g -Xmx3g -server -Djava.awt.headless=true"

# larger 16GB server
ENV JAVA_OPTIONS="-Xms15g -Xmx15g -server -Djava.awt.headless=true"

# super 500GB server
#ENV JAVA_OPTIONS="-Xms400g -Xmx400g -server -Djava.awt.headless=true"

# setup supervisor
#RUN mkdir -p /var/log/supervisor

#COPY supervisord.conf /etc/supervisor/conf.d/supervisord.conf
#CMD ["/usr/bin/supervisord"]

CMD ["/var/www/render/deploy/jetty-distribution-9.3.7.v20160115/bin/jetty.sh","run"]
