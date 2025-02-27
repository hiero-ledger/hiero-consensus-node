########################################################################################################################
#
# Define Global Build Arguments
#
########################################################################################################################
ARG UBUNTU_SHA="sha256:80dd3c3b9c6cecb9f1667e9290b3bc61b78c2678c02cbdae5f0fea92cc6734ab"

ARG OPENJDK_VERSION=21.0.1
ARG OPENJDK_DL_KEY="415e3f918a1f4062a0074a2794853d0d/12"

########################################################################################################################
#
# Setup Builder Image
#
########################################################################################################################
FROM ubuntu@${UBUNTU_SHA} AS openjdk-builder

# Define Global Argument Refs
ARG OPENJDK_DL_KEY
ARG OPENJDK_VERSION

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

# Download Java Archive
WORKDIR "/tmp/java"
RUN wget --quiet https://download.java.net/java/GA/jdk${OPENJDK_VERSION}/${OPENJDK_DL_KEY}/GPL/openjdk-${OPENJDK_VERSION}_linux-x64_bin.tar.gz

# Validate Java Archive
RUN sha256sum -c /tmp/checksums/openjdk-${OPENJDK_VERSION}_linux-x64_bin.tar.gz.sha256

# Unpack Java Archive
RUN mkdir -p /usr/local/java && \
	tar -zxf openjdk-${OPENJDK_VERSION}_linux-x64_bin.tar.gz -C /usr/local/java

WORKDIR /usr/local/java/jdk-${OPENJDK_VERSION}
# Validate Java Files
RUN	hashdeep -rlx -k /tmp/checksums/openjdk-${OPENJDK_VERSION}_linux-x64_bin.chkd *


########################################################################################################################
#
# Build Final Image
#
########################################################################################################################
FROM ubuntu@${UBUNTU_SHA} AS openjdk-base

# Define Global Argument Refs
ARG OPENJDK_VERSION

# Define Standard Environment Variables
ENV LC_ALL=C.UTF-8
ENV DEBIAN_FRONTEND=noninteractive

# Import Wait-For Scripting
ADD wait-for /usr/local/bin/

# Fetch Validated Java Binaries
COPY --from=openjdk-builder /usr/local/java/ /usr/local/java/

# Install Basic OS Requirements
RUN apt-get update && \
	apt-get install -y tar gzip openssl zlib1g libsodium23 libreadline8t64 sudo netcat-traditional && \
	apt-get autoremove && \
	apt-get autoclean && \
	apt-get clean all && \
	rm -rf /var/cache/apt

# Install Java Alternatives
RUN update-alternatives --install "/usr/bin/java" "java" "/usr/local/java/jdk-${OPENJDK_VERSION}/bin/java" 1500 && \
    update-alternatives --install "/usr/bin/javac" "javac" "/usr/local/java/jdk-${OPENJDK_VERSION}/bin/javac" 1500 && \
    update-alternatives --install "/usr/bin/javadoc" "javadoc" "/usr/local/java/jdk-${OPENJDK_VERSION}/bin/javadoc" 1500

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

# Ensure Wait-For Script Permissions
RUN chmod +rx /usr/local/bin/wait-for

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

# Set Final Working Directory and Command/Entrypoint
WORKDIR "/opt/hgcapp"
