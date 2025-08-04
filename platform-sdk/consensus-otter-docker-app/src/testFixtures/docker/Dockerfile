FROM eclipse-temurin:21
RUN useradd -m appuser || true && \
    mkdir -p /opt/DockerApp/apps /opt/DockerApp/lib
COPY apps/* /opt/DockerApp/apps/
COPY lib/* /opt/DockerApp/lib/
USER appuser
EXPOSE 8080
CMD ["java", "-jar", "/opt/DockerApp/apps/DockerApp.jar"]
