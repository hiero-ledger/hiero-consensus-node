FROM eclipse-temurin:21
RUN mkdir -p /opt/DockerApp/apps /opt/DockerApp/lib
COPY apps/* /opt/DockerApp/apps/
COPY lib/* /opt/DockerApp/lib/
# maybe ports not needed
EXPOSE 8080
EXPOSE 8081
CMD ["java", "-jar", "/opt/DockerApp/apps/DockerApp.jar"]
