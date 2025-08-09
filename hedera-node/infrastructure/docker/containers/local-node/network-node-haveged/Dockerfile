########################################################################################################################
#
# Define Global Build Arguments
#
########################################################################################################################
#ARG UBUNTU_TAG="focal-20220426"
#ARG UBUNTU_TAG="focal-20220531"
#ARG UBUNTU_TAG="focal-20220801"
#ARG UBUNTU_TAG="focal-20220922"
#ARG UBUNTU_TAG="focal-20221019"
#ARG UBUNTU_TAG="focal-20221130"
ARG UBUNTU_TAG="bookworm-20250630-slim"

ARG HAVEGED_VERSION="1.9.1-6ubuntu1"

########################################################################################################################
#
# Setup Builder Image
#
########################################################################################################################
FROM debian:${UBUNTU_TAG} AS haveged-builder

# Define Global Argument Refs
ARG HAVEGED_VERSION

# Import Checksum Files from the Build Context
ADD checksums/* /tmp/checksums/

# Install basic OS utilities for building
RUN apt-get update && \
	apt-get install -y tar gzip

# Download Haveged deb archive
WORKDIR "/tmp/deb"
RUN	apt-get update && \
	apt-get download --yes haveged=${HAVEGED_VERSION} libhavege1=${HAVEGED_VERSION} && \
	apt-get clean all

# Validate Haveged deb archive
RUN sha256sum -c /tmp/checksums/haveged_${HAVEGED_VERSION}_$(dpkg --print-architecture).deb.sha256 && \
    sha256sum -c /tmp/checksums/libhavege1_${HAVEGED_VERSION}_$(dpkg --print-architecture).deb.sha256

########################################################################################################################
#
# Build Final Image
#
########################################################################################################################
FROM debian:${UBUNTU_TAG} AS network-node-haveged

# Define Standard Environment Variables
ENV LC_ALL=C.UTF-8
ENV DEBIAN_FRONTEND=noninteractive

# Fetch Validated Debian Packages
COPY --from=haveged-builder /tmp/deb/*.deb /tmp/deb/

# Install Validated Local Debian Packages
RUN apt-get --allow-unauthenticated install --yes /tmp/deb/*.deb && \
    apt-get autoremove --yes && \
    apt-get autoclean --yes && \
    apt-get clean all --yes && \
    rm -rf /tmp/deb && \
    rm -rf /var/lib/{apt,dpkg,cache,log}/

# Set Final Working Directory and Command/Entrypoint
WORKDIR "/"
ENTRYPOINT ["/usr/bin/env", "haveged", "-v", "3", "-F"]
