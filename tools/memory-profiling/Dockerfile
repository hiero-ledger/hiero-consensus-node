FROM eclipse-temurin:25-jdk-noble

RUN apt-get update && apt-get install -y \
    bash \
    git \
    sudo \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* \
    && if id -u 1000 >/dev/null 2>&1; then \
      existing=$(getent passwd 1000 | cut -d: -f1) && \
      usermod -l builder -d /home/builder -m "$existing" && \
      groupmod -n builder "$(id -gn 1000)" 2>/dev/null || true; \
    else \
      useradd -m -s /bin/bash -u 1000 builder; \
    fi \
    && echo "builder ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

WORKDIR /workspace

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY measure-cgroup-peak.sh /usr/local/bin/measure-cgroup-peak.sh
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/measure-cgroup-peak.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["sleep", "infinity"]
