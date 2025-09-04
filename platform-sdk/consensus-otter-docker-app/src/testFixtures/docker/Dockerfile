FROM eclipse-temurin:21

# Create non-root user and group
RUN groupadd -r appuser && useradd -r -g appuser appuser

# Create app directories and set ownership
RUN mkdir -p /opt/DockerApp/apps /opt/DockerApp/lib \
    && chown -R appuser:appuser /opt/DockerApp

# Copy app artifacts with proper ownership
COPY --chown=appuser:appuser apps/* /opt/DockerApp/apps/
COPY --chown=appuser:appuser lib/* /opt/DockerApp/lib/

# Run as non-root user
USER appuser

CMD ["java", "-jar", "/opt/DockerApp/apps/DockerApp.jar"]
