########################################################################################################################
#
# Define Global Build Arguments
#
########################################################################################################################
ARG UBUNTU_TAG="bookworm-20250630-slim"
ARG SOURCE_DATE_EPOCH="0"

########################################################################################################################
#
# Setup Ephemeral Java Downloader Layer
#
########################################################################################################################
FROM debian:${UBUNTU_TAG} AS circuit-downloader
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

# Download and unpack the groth16 image
RUN mkdir -p /circuit && \
    curl -LsSo /circuit/v5.0.0-groth16.tar.gz https://builds.hedera.com/tss/sp1/groth16/v5.0/v5.0.0-groth16.tar.gz && \
    tar -C "/circuit" -xzvf v5.0.0-groth16.tar.gz && \
    rm -f /circuit/v5.0.0-groth16.tar.gz \

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

# Move the circuit folder
FROM scratch AS final-image
COPY --from=circuit-downloader /circuit .
