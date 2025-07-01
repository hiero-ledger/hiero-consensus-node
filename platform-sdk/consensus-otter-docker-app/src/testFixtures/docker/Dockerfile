FROM eclipse-temurin:21
RUN mkdir /opt/DockerApp && \
    mkdir /opt/DockerApp/apps && \
    mkdir /opt/DockerApp/lib
COPY apps/* /opt/DockerApp/apps/
COPY lib/* /opt/DockerApp/lib/
EXPOSE 8080
CMD ["java", "-jar", "/opt/DockerApp/apps/DockerApp.jar"]
