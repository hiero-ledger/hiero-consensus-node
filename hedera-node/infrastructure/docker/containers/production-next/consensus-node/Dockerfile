########################################################################################################################
#
# Define Global Build Arguments
#
########################################################################################################################
ARG UBUNTU_TAG="bookworm-20250630-slim"
ARG S6_OVERLAY_VERSION="3.1.6.2"
ARG SOURCE_DATE_EPOCH="0"

########################################################################################################################
#
# Setup Ephemeral Java Downloader Layer
#
########################################################################################################################
FROM debian:${UBUNTU_TAG} AS java-builder-interim
# Define Build Arguments
ARG SOURCE_DATE_EPOCH

# Define Standard Environment Variables
ENV DEBIAN_FRONTEND noninteractive
ENV LANG C.UTF-8
ENV LC_ALL C.UTF-8

# Install basic OS utilities for building
RUN --mount=type=bind,source=./repro-sources-list.sh,target=/usr/local/bin/repro-sources-list.sh \
    repro-sources-list.sh && \
    apt-get update && \
	apt-get install --yes --no-install-recommends tar gzip curl ca-certificates && \
    apt-get autoclean --yes && \
    apt-get clean all --yes && \
    rm -rf /var/log/ && \
    rm -rf /var/cache/

##########################
####    Java Setup    ####
##########################
RUN set -eux; \
        ARCH="$(dpkg --print-architecture)"; \
        case "${ARCH}" in \
           aarch64|arm64) \
            ESUM='04fe1273f624187d927f1b466e8cdb630d70786db07bee7599bfa5153060afd3'; \
            BINARY_URL='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.6%2B7/OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.6_7.tar.gz'; \
            ;; \
          amd64|i386:x86-64) \
            ESUM='a2650fba422283fbed20d936ce5d2a52906a5414ec17b2f7676dddb87201dbae'; \
            BINARY_URL='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.6%2B7/OpenJDK21U-jdk_x64_linux_hotspot_21.0.6_7.tar.gz'; \
            ;; \
           ppc64el|powerpc:common64) \
            ESUM='163724b70b86d5a8461f85092165a9cc5a098ed900fee90d1b0c0be9607ae3d2'; \
            BINARY_URL='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.6%2B7/OpenJDK21U-jdk_ppc64le_linux_hotspot_21.0.6_7.tar.gz'; \
            ;; \
           *) \
            echo "Unsupported arch: ${ARCH}"; \
            exit 1; \
            ;; \
        esac; \
    curl -LfsSo /tmp/openjdk.tar.gz ${BINARY_URL}; \
    echo "${ESUM} /tmp/openjdk.tar.gz" | sha256sum -c -; \
    mkdir -p /usr/local/java; \
    tar --extract \
    	      --file /tmp/openjdk.tar.gz \
    	      --directory "/usr/local/java" \
    	      --strip-components 1 \
    	      --no-same-owner \
    	  ; \
    rm -f /tmp/openjdk.tar.gz /usr/local/java/lib/src.zip;

########################################
####    Deterministic Build Hack    ####
########################################

# === Workarounds below will not be needed when https://github.com/moby/buildkit/pull/4057 is merged ===
# NOTE: PR #4057 has been merged but will not be available until the v0.13.x series of releases.
# Limit the timestamp upper bound to SOURCE_DATE_EPOCH.
# Workaround for https://github.com/moby/buildkit/issues/3180
RUN find $( ls / | grep -E -v "^(dev|mnt|proc|sys)$" ) \
  -newermt "@${SOURCE_DATE_EPOCH}" -writable -xdev \
  | xargs touch --date="@${SOURCE_DATE_EPOCH}" --no-dereference

FROM scratch AS java-builder
COPY --from=java-builder-interim / /

########################################################################################################################
#
# Setup S6 Overlay Base Layer
#
########################################################################################################################
FROM debian:${UBUNTU_TAG} AS s6-overlay-interim
# Define Build Arguments
ARG SOURCE_DATE_EPOCH
ARG S6_OVERLAY_VERSION

# Install basic OS utilities
RUN --mount=type=bind,source=./repro-sources-list.sh,target=/usr/local/bin/repro-sources-list.sh \
    repro-sources-list.sh && \
    apt-get update && \
    apt-get install --yes --no-install-recommends tar gzip openssl zlib1g libsodium23 libreadline8 sudo netcat-traditional net-tools xz-utils curl ca-certificates

###########################
####    S6 Install     ####
###########################
RUN set -eux; \
        NOARCH_PKG_ESUM="05af2536ec4fb23f087a43ce305f8962512890d7c71572ed88852ab91d1434e3" \
        NOARCH_BINARY_URL="https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-noarch.tar.xz" \
        ARCH="$(dpkg --print-architecture)"; \
        case "${ARCH}" in \
           aarch64|arm64) \
             ARCH_PKG_ESUM='3fc0bae418a0e3811b3deeadfca9cc2f0869fb2f4787ab8a53f6944067d140ee'; \
             ARCH_BINARY_URL="https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-aarch64.tar.xz"; \
             ;; \
          amd64|i386:x86-64) \
            ARCH_PKG_ESUM='95081f11c56e5a351e9ccab4e70c2b1c3d7d056d82b72502b942762112c03d1c'; \
            ARCH_BINARY_URL="https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-x86_64.tar.xz"; \
            ;; \
           ppc64el|powerpc:common64) \
             ARCH_PKG_ESUM='6747f7415d5d8f1b4f51a180af81435de3d0b99762e16bd42c7688dc5dbd089f'; \
             ARCH_BINARY_URL="https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-powerpc64le.tar.xz"; \
             ;; \
           *) \
             echo "Unsupported arch: ${ARCH}"; \
             exit 1; \
             ;; \
        esac; \
    curl -sSLo /tmp/s6-overlay-noarch.tar.xz ${NOARCH_BINARY_URL}; \
    curl -sSLo /tmp/s6-overlay-arch.tar.xz ${ARCH_BINARY_URL}; \
    echo "${NOARCH_PKG_ESUM} /tmp/s6-overlay-noarch.tar.xz" | sha256sum -c -; \
    echo "${ARCH_PKG_ESUM} /tmp/s6-overlay-arch.tar.xz" | sha256sum -c -; \
    tar -C / -Jxpf /tmp/s6-overlay-noarch.tar.xz; \
    tar -C / -Jxpf /tmp/s6-overlay-arch.tar.xz; \
    rm -f /tmp/s6-overlay-noarch.tar.xz; \
    rm -f /tmp/s6-overlay-arch.tar.xz

# Remove Unneeded Utilities
RUN --mount=type=bind,source=./repro-sources-list.sh,target=/usr/local/bin/repro-sources-list.sh \
    repro-sources-list.sh && \
    apt-get remove --yes xz-utils curl && \
    apt-get autoremove --yes && \
    apt-get autoclean --yes && \
    apt-get clean all --yes && \
    rm -rf /var/log/ && \
    rm -rf /var/cache/


########################################
####    Deterministic Build Hack    ####
########################################

# === Workarounds below will not be needed when https://github.com/moby/buildkit/pull/4057 is merged ===
# NOTE: PR #4057 has been merged but will not be available until the v0.13.x series of releases.
# Limit the timestamp upper bound to SOURCE_DATE_EPOCH.
# Workaround for https://github.com/moby/buildkit/issues/3180
RUN find $( ls / | grep -E -v "^(dev|mnt|proc|sys)$" ) \
  -newermt "@${SOURCE_DATE_EPOCH}" -writable -xdev \
  | xargs touch --date="@${SOURCE_DATE_EPOCH}" --no-dereference

FROM scratch AS s6-overlay
COPY --from=s6-overlay-interim / /

########################################################################################################################
#
# Setup OS Base Layer
#
########################################################################################################################
FROM s6-overlay AS operating-system-base-interim
# Define Build Arguments
ARG SOURCE_DATE_EPOCH

# Define Standard Environment Variables
ENV LANG C.UTF-8
ENV LC_ALL C.UTF-8
ENV DEBIAN_FRONTEND noninteractive

# Create Application Folders
RUN mkdir -p "/opt/hgcapp" && \
    mkdir -p "/opt/hgcapp/accountBalances" && \
    mkdir -p "/opt/hgcapp/eventsStreams" && \
    mkdir -p "/opt/hgcapp/recordStreams" && \
    mkdir -p "/opt/hgcapp/blockStreams" && \
    mkdir -p "/opt/hgcapp/services-hedera" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data/apps" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data/config" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data/diskFs" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data/keys" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data/lib" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data/onboard" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data/stats" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data/saved" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data/upgrade"

# Configure the standard user account
RUN groupadd --gid 2000 hedera && \
    useradd --no-user-group --create-home --uid 2000 --gid 2000 --shell /bin/bash hedera && \
    chown -R hedera:hedera /opt/hgcapp

# Configure SUDO support
RUN echo >> /etc/sudoers && \
    echo "%hedera ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

########################################
####    Deterministic Build Hack    ####
########################################

# === Workarounds below will not be needed when https://github.com/moby/buildkit/pull/4057 is merged ===
# NOTE: PR #4057 has been merged but will not be available until the v0.13.x series of releases.
# Limit the timestamp upper bound to SOURCE_DATE_EPOCH.
# Workaround for https://github.com/moby/buildkit/issues/3180
RUN find $( ls / | grep -E -v "^(dev|mnt|proc|sys)$" ) \
  -newermt "@${SOURCE_DATE_EPOCH}" -writable -xdev \
  | xargs touch --date="@${SOURCE_DATE_EPOCH}" --no-dereference

FROM scratch AS operating-system-base
COPY --from=operating-system-base-interim / /

########################################################################################################################
#
# Setup Production Container Image
#
########################################################################################################################
FROM operating-system-base AS production-image-interim
# Define Build Arguments
ARG SOURCE_DATE_EPOCH

# Define Standard Environment Variables
ENV JAVA_VERSION "jdk-21.0.6+7"
ENV JAVA_HOME /usr/local/java
ENV PATH ${JAVA_HOME}/bin:${PATH}

# Install Java
COPY --from=java-builder ${JAVA_HOME}/ ${JAVA_HOME}/

# Add SDK components
COPY sdk/data/apps/* /opt/hgcapp/services-hedera/HapiApp2.0/data/apps/
COPY sdk/data/lib/* /opt/hgcapp/services-hedera/HapiApp2.0/data/lib/
COPY sdk/VERSION /opt/hgcapp/services-hedera/HapiApp2.0/

# Add the entrypoint script
ADD entrypoint.sh /opt/hgcapp/services-hedera/HapiApp2.0/

# Add s6-rc configuration
COPY etc/s6-overlay/s6-rc.d /etc/s6-overlay/s6-rc.d/

# Ensure proper file permissions
RUN chmod -R +x /opt/hgcapp/services-hedera/HapiApp2.0/entrypoint.sh && \
    chown -R 2000:2000 /opt/hgcapp/services-hedera/HapiApp2.0

# Patch the Java DNS Cache settings
RUN sed -e '/networkaddress.cache.ttl/c networkaddress.cache.ttl=10' -i "${JAVA_HOME}/conf/security/java.security" && \
    sed -e '/networkaddress.cache.stale.ttl/c networkaddress.cache.stale.ttl=0' -i "${JAVA_HOME}/conf/security/java.security" && \
    sed -e '/networkaddress.cache.negative.ttl/c networkaddress.cache.negative.ttl=0' -i "${JAVA_HOME}/conf/security/java.security"

########################################
####    Deterministic Build Hack    ####
########################################

# === Workarounds below will not be needed when https://github.com/moby/buildkit/pull/4057 is merged ===
# NOTE: PR #4057 has been merged but will not be available until the v0.13.x series of releases.
# Limit the timestamp upper bound to SOURCE_DATE_EPOCH.
# Workaround for https://github.com/moby/buildkit/issues/3180
RUN find $( ls / | grep -E -v "^(dev|mnt|proc|sys)$" ) \
  -newermt "@${SOURCE_DATE_EPOCH}" -writable -xdev \
  | xargs touch --date="@${SOURCE_DATE_EPOCH}" --no-dereference

FROM scratch AS production-image
COPY --from=production-image-interim / /

# Define Standard Environment Variables
ENV JAVA_VERSION "jdk-21.0.6+7"
ENV JAVA_HOME /usr/local/java
ENV PATH ${JAVA_HOME}/bin:${PATH}

# Define Application Specific Variables
ENV JAVA_HEAP_MIN ""
ENV JAVA_HEAP_MAX ""
ENV JAVA_OPTS ""
ENV JAVA_MAIN_CLASS "com.hedera.node.app.ServicesMain"
ENV JAVA_CLASS_PATH "data/lib/*:data/apps/*"

## Consensus Node Configuration
ENV CONSENSUS_NODE_ID=""

# Performance Tuning for Malloc
ENV MALLOC_ARENA_MAX 4

# Log Folder Name Override
ENV LOG_DIR_NAME ""

# If "true" or an integer between 1 & 2^63-1 after running consensus entrypoint.sh will block indefinitely.
# Otherwise entrypoint.sh will return the exit code from consensus.
ENV CONTAINER_TSR_ENABLED "false"

# Define Volume Bindpoints
VOLUME "/opt/hgcapp/accountBalances"
VOLUME "/opt/hgcapp/eventsStreams"
VOLUME "/opt/hgcapp/recordStreams"
VOLUME "/opt/hgcapp/blockStreams"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/config"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/diskFs"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/keys"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/onboard"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/stats"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/saved"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/upgrade"

# Expose TCP/UDP Port Definitions
EXPOSE 50111/tcp 50211/tcp 50212/tcp

# Set Final Working Directory, User, and Entrypoint
USER 2000
WORKDIR "/opt/hgcapp"
ENTRYPOINT ["/init"]
