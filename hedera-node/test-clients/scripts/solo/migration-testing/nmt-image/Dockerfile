# Migration-testing consensus-node image with NMT v1.3.4 pre-baked.
#
# Derived from Solo's stock root-container so the StatefulSet pod looks
# identical to a default Solo deploy — only difference is that NMT, its
# prereqs, and the canonical /opt/hgcapp layout are already in place. This
# lets us avoid every runtime `sudo apt-get ...` / `sudo mkdir ...` / `sudo
# usermod ...` call that currently runs through `kubectl exec`. Those calls
# work locally (privileged container under Lima) but fail in ARC GitHub
# runners with `sudo: PAM account management error` even when the container
# is runAsUser=0 (PAM still consulted; no shadow entries → bail).
#
# Issue: hiero-ledger/hiero-consensus-node#25736
ARG SOLO_BASE_IMAGE=ghcr.io/hashgraph/solo-containers/ubi8-s6-java25:0.43.0
ARG NMT_VERSION=v1.3.4
ARG NMT_INSTALLER_BASENAME=node-mgmt-tools-installer-v1.3.4-efea0dd4.run
ARG NMT_INSTALLER_URL=https://builds.hedera.com/nmt/${NMT_INSTALLER_BASENAME}
ARG HGCAPP_DIR=/opt/hgcapp
ARG NMT_DIR=/opt/hgcapp/node-mgmt-tools
ARG HAPI_PATH=/opt/hgcapp/services-hedera/HapiApp2.0

FROM ${SOLO_BASE_IMAGE}

ARG NMT_INSTALLER_BASENAME
ARG NMT_INSTALLER_URL
ARG HGCAPP_DIR
ARG NMT_DIR
ARG HAPI_PATH

USER root

# --- NMT preflight prereqs + docker-ce (DinD) ---------------------------------
# NMT v1.3.4 preflight checks for: awk, chmod, chown, comm, curl, cut, date,
# find, gunzip, gzip, head, jq, ln, readlink, rsync, sha256sum, sort, stat,
# systemctl, tar, unzip, hashdeep, docker, docker-compose. The Solo base image
# ships most coreutils + java but not jq/rsync/hashdeep/docker. The docker-ce
# package is required by name (preflight rejects the distro `docker.io`/`podman`
# substitutes). procps-ng + iproute are needed by NMT's start/stop scripts.
RUN dnf -y install --setopt=install_weak_deps=False \
        ca-certificates \
        curl \
        dnf-plugins-core \
        gettext \
        hashdeep \
        iproute \
        jq \
        procps-ng \
        rsync \
        sudo \
        systemd \
        tar \
        unzip \
    && dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo \
    && dnf -y install --setopt=install_weak_deps=False \
        containerd.io \
        docker-ce \
        docker-ce-cli \
        docker-compose-plugin \
    && dnf clean all \
    && rm -rf /var/cache/dnf

# --- NMT v1.3.4 OS-flavor spoof -----------------------------------------------
# NMT preflight's detect_os_osr_support.inc.sh only recognises Ubuntu / CentOS
# / RHEL / SUSE / Oracle and exits EX_UNAVAILABLE on anything else. UBI 8 is
# RHEL-derived so this is usually OK — but in case Solo ever switches the base
# image to Debian (it has before) we ship an Ubuntu /etc/os-release as a
# defensive spoof. NMT only reads ID / VERSION_ID from it.
COPY os-release /etc/os-release

# --- Canonical /opt/hgcapp folder tree ----------------------------------------
# Source: extracted NMT installer's tools/config/system_folders.lst and
# system_folders.jrs.lst. Production puts these in place via systemd unit
# launches; in our container we have to materialise them at image-build time
# so NMT preflight's mkdir/chown probes pass.
RUN install -d -o hedera -g hedera \
        ${NMT_DIR}/state \
        ${NMT_DIR}/logs \
        ${NMT_DIR}/cache \
        ${HGCAPP_DIR}/accountBalances \
        ${HGCAPP_DIR}/accountBalances/archive \
        ${HGCAPP_DIR}/accountBalancesOriginal \
        ${HGCAPP_DIR}/eventsStreams \
        ${HGCAPP_DIR}/eventsStreams/archive \
        ${HGCAPP_DIR}/eventStreamRecover \
        ${HGCAPP_DIR}/recordStreams \
        ${HGCAPP_DIR}/recordStreams/archive \
        ${HGCAPP_DIR}/blockStreams \
        ${HGCAPP_DIR}/blockStreams/archive \
        ${HAPI_PATH}/data/config \
        ${HAPI_PATH}/data/diskFs \
        ${HAPI_PATH}/data/jdb \
        ${HAPI_PATH}/data/keys \
        ${HAPI_PATH}/data/lifecycle \
        ${HAPI_PATH}/data/onboard \
        ${HAPI_PATH}/data/saved \
        ${HAPI_PATH}/data/stats \
        ${HAPI_PATH}/data/upgrade \
        ${HAPI_PATH}/data/upgrade/current \
        ${HAPI_PATH}/data/upgrade/previous \
        ${HAPI_PATH}/data/upgrade/pending \
        ${HAPI_PATH}/logs \
        ${HAPI_PATH}/output

# --- hedera user → docker group + passwordless sudo ---------------------------
# NMT runs all compose calls via `sudo -u hedera`. docker.sock is 0660
# root:docker. Without group membership, every hedera-side `docker compose`
# silently fails and NMT misreports success. We also ship a passwordless
# sudoers entry so that any kubectl-exec invocation that lands as uid 2000
# (Solo's non-root pod template) can still escalate without PAM:
RUN usermod -aG docker hedera \
    && install -m 0440 /dev/null /etc/sudoers.d/90-hedera-nopasswd \
    && printf 'hedera ALL=(ALL) NOPASSWD: ALL\n' > /etc/sudoers.d/90-hedera-nopasswd

# --- NMT v1.3.4 installer (run at image-build time) ---------------------------
# The installer is a Makeself self-extracting bash archive. It registers a
# systemd nmt-ics.service unit (no-op in a container — we replace it with a
# manual `nmt watch` from start_nmt_watcher() in the test script) and lays
# down the bash modules + Go binary under ${NMT_DIR}. Running this at build
# time burns it into the image layer instead of via runtime kubectl-exec.
ADD --chown=hedera:hedera --chmod=0755 ${NMT_INSTALLER_URL} /tmp/${NMT_INSTALLER_BASENAME}
RUN /tmp/${NMT_INSTALLER_BASENAME} --accept -- -fg \
    && rm /tmp/${NMT_INSTALLER_BASENAME}

# Re-assert ownership: the installer occasionally chowns paths back to root.
RUN chown -R hedera:hedera ${NMT_DIR}/state ${NMT_DIR}/logs ${HGCAPP_DIR}/services-hedera

# Preserve the base image's entrypoint / cmd / user — Solo's chart expects
# the stock s6 init to run as PID 1 so its readiness probes work. We just
# add files; we don't change the runtime contract.
