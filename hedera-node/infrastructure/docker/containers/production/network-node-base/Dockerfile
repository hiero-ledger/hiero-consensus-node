########################################################################################################################
#
# Define Global Build Arguments
#
########################################################################################################################
ARG UBUNTU_SHA="sha256:80dd3c3b9c6cecb9f1667e9290b3bc61b78c2678c02cbdae5f0fea92cc6734ab"

# Eclipse Temurin build to install, e.g. 25.0.5.1 + 2 => jdk-25.0.5.1+2. Needs matching files in checksums/.
ARG JDK_VERSION=25.0.5.1
ARG JDK_BUILD=2

########################################################################################################################
#
# Setup Builder Image
#
########################################################################################################################
FROM ubuntu@${UBUNTU_SHA} AS jdk-builder

# Define Global Argument Refs
ARG JDK_BUILD
ARG JDK_VERSION

# Define Standard Environment Variables
ENV LC_ALL=C.UTF-8
ENV DEBIAN_FRONTEND=noninteractive

# Import Checksum Files from the Build Context
ADD checksums/* /tmp/checksums/

# Install basic OS utilities for building
RUN apt-get update && \
	apt-get install -y tar gzip wget dos2unix gnupg2 hashdeep && \
    apt-get autoclean && \
    apt-get clean all && \
    rm -rf /var/cache/apt

##########################
####    Java Setup    ####
##########################

# Download, validate and unpack the Temurin archive into /usr/local/java/jdk-${JDK_VERSION}.
WORKDIR "/tmp/java"
RUN set -eux; \
    JDK_MAJOR="${JDK_VERSION%%.*}"; \
    JDK_TAR="OpenJDK${JDK_MAJOR}U-jdk_x64_linux_hotspot_${JDK_VERSION}_${JDK_BUILD}.tar.gz"; \
    JDK_CHECKSUMS="/tmp/checksums/temurin-${JDK_VERSION}_${JDK_BUILD}_linux-x64"; \
    wget --quiet "https://github.com/adoptium/temurin${JDK_MAJOR}-binaries/releases/download/jdk-${JDK_VERSION}%2B${JDK_BUILD}/${JDK_TAR}"; \
    sha256sum -c "${JDK_CHECKSUMS}.tar.gz.sha256"; \
    mkdir -p /usr/local/java; \
    tar -zxf "${JDK_TAR}" -C /usr/local/java; \
    mv "/usr/local/java/jdk-${JDK_VERSION}+${JDK_BUILD}" "/usr/local/java/jdk-${JDK_VERSION}"; \
    cd "/usr/local/java/jdk-${JDK_VERSION}"; \
    hashdeep -rlx -k "${JDK_CHECKSUMS}.chkd" *


########################################################################################################################
#
# Build Final Image
#
########################################################################################################################
FROM ubuntu@${UBUNTU_SHA} AS jdk-base

# Define Global Argument Refs
ARG JDK_VERSION

# Define Standard Environment Variables
ENV LC_ALL=C.UTF-8
ENV DEBIAN_FRONTEND=noninteractive

# Import Wait-For Scripting
ADD wait-for /usr/local/bin/

# Fetch Validated Java Binaries
COPY --from=jdk-builder /usr/local/java/ /usr/local/java/

# Install Basic OS Requirements
RUN apt-get update && \
	apt-get install -y tar gzip openssl zlib1g libsodium23 libreadline8t64 sudo netcat-traditional && \
	apt-get autoremove && \
	apt-get autoclean && \
	apt-get clean all && \
	rm -rf /var/cache/apt

# Install Java Alternatives
RUN update-alternatives --install "/usr/bin/java" "java" "/usr/local/java/jdk-${JDK_VERSION}/bin/java" 1500 && \
    update-alternatives --install "/usr/bin/javac" "javac" "/usr/local/java/jdk-${JDK_VERSION}/bin/javac" 1500 && \
    update-alternatives --install "/usr/bin/javadoc" "javadoc" "/usr/local/java/jdk-${JDK_VERSION}/bin/javadoc" 1500

# Create Application Folders
RUN mkdir -p "/opt/hgcapp" && \
    mkdir -p "/opt/hgcapp/accountBalances" && \
    mkdir -p "/opt/hgcapp/eventsStreams" && \
    mkdir -p "/opt/hgcapp/recordStreams" && \
    mkdir -p "/opt/hgcapp/blockStreams" && \
    mkdir -p "/opt/hgcapp/wrappedRecordHashes" && \
    mkdir -p "/opt/hgcapp/issBlocks" && \
    mkdir -p "/opt/hgcapp/services-hedera" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data/apps" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data/config" && \
    mkdir -p "/opt/hgcapp/services-hedera/HapiApp2.0/data/cutover" && \
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

# Ensure Wait-For Script Permissions
RUN chmod +rx /usr/local/bin/wait-for

# Define Volume Bindpoints
VOLUME "/opt/hgcapp/accountBalances"
VOLUME "/opt/hgcapp/eventsStreams"
VOLUME "/opt/hgcapp/recordStreams"
VOLUME "/opt/hgcapp/blockStreams"
VOLUME "/opt/hgcapp/wrappedRecordHashes"
VOLUME "/opt/hgcapp/issBlocks"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/config"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/cutover"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/diskFs"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/keys"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/onboard"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/stats"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/saved"
VOLUME "/opt/hgcapp/services-hedera/HapiApp2.0/data/upgrade"

# Set Final Working Directory and Command/Entrypoint
WORKDIR "/opt/hgcapp"
