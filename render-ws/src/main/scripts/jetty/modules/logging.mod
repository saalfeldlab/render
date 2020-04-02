# Jetty Logging Module
#   Capture all Logging Events From:
#     Jetty Log
#     Slf4j
#     Commons Logging
#     Log4j
#     Logback
#     java.util.logging
#   Routing/Filtering/Output Managed by Logback
#

[name]
logging

[depend]
resources

[lib]
lib/logging/*.jar

[files]
logs/
https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.16/slf4j-api-1.7.16.jar|lib/logging/slf4j-api-1.7.16.jar
https://repo1.maven.org/maven2/org/slf4j/log4j-over-slf4j/1.7.16/log4j-over-slf4j-1.7.16.jar|lib/logging/log4j-over-slf4j-1.7.16.jar
https://repo1.maven.org/maven2/org/slf4j/jul-to-slf4j/1.7.16/jul-to-slf4j-1.7.16.jar|lib/logging/jul-to-slf4j-1.7.16.jar
https://repo1.maven.org/maven2/org/slf4j/jcl-over-slf4j/1.7.16/jcl-over-slf4j-1.7.16.jar|lib/logging/jcl-over-slf4j-1.7.16.jar
https://repo1.maven.org/maven2/ch/qos/logback/logback-core/1.1.5/logback-core-1.1.5.jar|lib/logging/logback-core-1.1.5.jar
https://repo1.maven.org/maven2/ch/qos/logback/logback-classic/1.1.5/logback-classic-1.1.5.jar|lib/logging/logback-classic-1.1.5.jar
https://repo1.maven.org/maven2/ch/qos/logback/logback-access/1.1.5/logback-access-1.1.5.jar|lib/logging/logback-access-1.1.5.jar
https://raw.githubusercontent.com/jetty-project/logging-modules/master/capture-all/logback.xml|resources/logback.xml
https://raw.githubusercontent.com/jetty-project/logging-modules/master/capture-all/jetty-logging.properties|resources/jetty-logging.properties
https://raw.githubusercontent.com/jetty-project/logging-modules/master/capture-all/jetty-logging.xml|etc/jetty-logging.xml