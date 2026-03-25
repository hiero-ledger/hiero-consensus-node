FROM eclipse-temurin:25

# Install libsodium for Ed25519 signature operations (Panama FFM binding)
RUN apt-get update && apt-get install -y --no-install-recommends libsodium23 \
    && rm -rf /var/lib/apt/lists/*

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
