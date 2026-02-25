FROM eclipse-temurin:21-jdk

RUN mkdir -p /launch /opt/bin

# Use ADD instead of copy to preserve file permissions
ADD assets/yahcli.jar /opt/bin
ADD assets/screened-launch.sh /opt/bin

WORKDIR /launch

ENTRYPOINT ["/opt/bin/screened-launch.sh"]
