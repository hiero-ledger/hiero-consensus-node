#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Build and run a GraalVM native-image of the Hedera node — locally or in Docker.
#
# Usage:
#   ./native-image.sh build               # assemble + build native image
#   ./native-image.sh run                  # run the native binary
#   ./native-image.sh build-run            # build then run
#   ./native-image.sh clean                # remove old node state (database, output, etc.)
#   ./native-image.sh profile [name]       # switch config profile (default: small-memory)
#   ./native-image.sh trace                # run with tracing agent to regenerate configs
#   ./native-image.sh trace-docker         # run tracing agent in Docker (captures Linux-specific configs)
#   ./native-image.sh docker [profile]     # build Docker image
#   ./native-image.sh docker-run           # run the Docker container
#   ./native-image.sh docker-stop          # stop the Docker container
#   ./native-image.sh docker-stats         # show container memory usage
#
# Available profiles (from hedera-node/configuration/):
#   small-memory, dev, compose, mainnet, preprod, previewnet, testnet, update
#
# Prerequisites (local build):
#   - GraalVM JDK 25+ with native-image (sdk install java 25.0.2-graal)
#   - libsodium (brew install libsodium on macOS)
#
# Prerequisites (Docker build):
#   - Docker with BuildKit support
#   - Pre-built assembly: ./gradlew -p hedera-node/hedera-app :app:assemble -x test -x javadoc
#   - Tracing agent configs: ./native-image.sh trace

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
NODE_DIR="$SCRIPT_DIR/build/node"
CONFIG_BASE_DIR="$SCRIPT_DIR/../configuration"
PERSISTENT_CONFIG_DIR="$SCRIPT_DIR/native-image-config"

DOCKER_IMAGE="hedera-node-native"
DOCKER_CONTAINER="hedera-native"

# Packages safe for build-time initialization.
# NOTE: com.google.protobuf must NOT be at build time — it uses
# Unsafe.objectFieldOffset() during class init; offsets computed at build time
# don't match native-image runtime object layout, causing data corruption.
# NOTE: org.apache.logging (Log4j) is excluded — it starts timer threads.
# NOTE: com.sun.jna must be at run time — it loads platform-specific native
# libraries (libjnidispatch.so) which differ between build and run environments.
BUILD_TIME_INIT="org.slf4j,com.fasterxml.jackson,com.google.common,org.apache.commons,com.google.gson,com.google.errorprone,javax.annotation,io.perfmark,org.yaml.snakeyaml"
RUN_TIME_INIT="com.sun.jna,io.netty,org.hiero.base.utility.MemoryUtils,com.hedera.pbj.runtime.io.UnsafeUtils"

die() { echo "ERROR: $*" >&2; exit 1; }

check_native_image() {
    if ! command -v native-image &>/dev/null; then
        die "native-image not found. Install GraalVM: sdk install java 25.0.2-graal"
    fi
}

kill_old_processes() {
    local killed=0
    for pid in $(pgrep -f "hedera-node.*-local" 2>/dev/null) $(pgrep -f "ServicesMain" 2>/dev/null); do
        kill "$pid" 2>/dev/null && killed=1
    done
    if [[ $killed -eq 1 ]]; then
        echo "Killed lingering processes, waiting for ports to free..."
        sleep 2
    fi
}

strip_netty_jars() {
    echo "Stripping Netty native-image.properties..."
    cd "$NODE_DIR"
    for jar in data/lib/netty-*.jar data/lib/grpc-netty*.jar; do
        [[ -f "$jar" ]] && zip -dq "$jar" 'META-INF/native-image/*' 2>/dev/null || true
    done
}

copy_native_image_config() {
    if [[ ! -d "$PERSISTENT_CONFIG_DIR" ]] || [[ -z "$(ls "$PERSISTENT_CONFIG_DIR"/*.json 2>/dev/null)" ]]; then
        die "No native-image configs found in $PERSISTENT_CONFIG_DIR. Run '$0 trace' first."
    fi
    echo "Copying native-image configs..."
    mkdir -p "$NODE_DIR/native-image-config"
    cp "$PERSISTENT_CONFIG_DIR"/*.json "$NODE_DIR/native-image-config/"
}

cmd_clean() {
    kill_old_processes
    if [[ ! -d "$NODE_DIR" ]]; then
        echo "Nothing to clean — $NODE_DIR does not exist."
        return
    fi
    echo "Cleaning old node state..."
    cd "$NODE_DIR"
    rm -rf database output settingsUsed.txt data/saved data/recordstreams data/accountBalances
    echo "Done."
}

cmd_profile() {
    local profile="${1:-small-memory}"
    local profile_dir="$CONFIG_BASE_DIR/$profile"

    if [[ ! -d "$profile_dir" ]]; then
        echo "Available profiles:"
        for d in "$CONFIG_BASE_DIR"/*/; do
            [[ -d "$d" ]] && echo "  $(basename "$d")"
        done
        die "Profile '$profile' not found in $CONFIG_BASE_DIR/"
    fi

    if [[ ! -d "$NODE_DIR" ]]; then
        die "No build directory. Run '$0 build' first."
    fi

    echo "Switching to profile: $profile"
    rm -rf "$NODE_DIR/data/config"
    mkdir -p "$NODE_DIR/data/config"
    cp -R "$profile_dir"/* "$NODE_DIR/data/config/"

    # Copy settings.txt and config.txt to the node working directory if they exist
    [[ -f "$profile_dir/settings.txt" ]] && cp "$profile_dir/settings.txt" "$NODE_DIR/"
    [[ -f "$SCRIPT_DIR/../config.txt" ]] && cp "$SCRIPT_DIR/../config.txt" "$NODE_DIR/"
    [[ -f "$SCRIPT_DIR/../log4j2.xml" ]] && cp "$SCRIPT_DIR/../log4j2.xml" "$NODE_DIR/"

    echo "Config files copied from: $profile_dir"
    ls "$NODE_DIR/data/config/"
}

cmd_build() {
    check_native_image

    echo "=== Assembling ==="
    cd "$REPO_ROOT"
    ./gradlew -p hedera-node/hedera-app :app:assemble -x test -x javadoc

    [[ -d "$NODE_DIR/data/apps" ]] || die "Assembly failed — $NODE_DIR/data/apps not found"

    strip_netty_jars
    copy_native_image_config

    echo "=== Building native image ==="
    cd "$NODE_DIR"
    native-image \
        -cp "data/lib/*:data/apps/*" \
        -H:ConfigurationFileDirectories=native-image-config \
        -o hedera-node \
        --no-fallback \
        "--initialize-at-build-time=$BUILD_TIME_INIT" \
        "--initialize-at-run-time=$RUN_TIME_INIT" \
        -J-Xmx8g \
        com.hedera.node.app.ServicesMain

    echo ""
    echo "Binary: $NODE_DIR/hedera-node ($(du -h hedera-node | cut -f1))"
}

cmd_run() {
    cd "$NODE_DIR"
    [[ -x hedera-node ]] || die "No binary found. Run '$0 build' first."

    kill_old_processes

    echo "=== Starting native Hedera node ==="
    export DYLD_LIBRARY_PATH="${DYLD_LIBRARY_PATH:-/opt/homebrew/lib}"
    exec ./hedera-node -Xmx128m -Xss512k -local 0
}

cmd_trace() {
    cd "$NODE_DIR"
    [[ -d data/apps ]] || die "No assembly found. Run '$0 build' or assemble first."

    kill_old_processes
    cmd_clean

    mkdir -p "$PERSISTENT_CONFIG_DIR"
    echo "=== Running with tracing agent ==="
    echo "Configs will be written to: $PERSISTENT_CONFIG_DIR"
    echo "Let the node reach ACTIVE, wait ~30s, then Ctrl-C."
    echo ""

    export DYLD_LIBRARY_PATH="${DYLD_LIBRARY_PATH:-/opt/homebrew/lib}"
    java \
        "-agentlib:native-image-agent=config-output-dir=$PERSISTENT_CONFIG_DIR,config-write-period-secs=5,config-write-initial-delay-secs=0" \
        -cp "data/lib/*:data/apps/*" \
        -XX:+UseSerialGC -Xms128M -Xmx512M \
        com.hedera.node.app.ServicesMain -local 0
}

cmd_trace_docker() {
    local profile="${1:-small-memory}"

    [[ -d "$NODE_DIR/data/apps" ]] || die "No assembly found. Run '$0 build' or assemble first."

    mkdir -p "$PERSISTENT_CONFIG_DIR"

    echo "=== Building trace container ==="
    cd "$REPO_ROOT"
    docker build \
        -f hedera-node/docker/Dockerfile.trace \
        --build-context node-data="$NODE_DIR" \
        --build-arg "CONFIGURATION_PROFILE=$profile" \
        -t hedera-node-trace \
        .

    echo ""
    echo "=== Running tracing agent in Docker (Linux) ==="
    echo "Configs will be merged into: $PERSISTENT_CONFIG_DIR"
    echo "Let the node reach ACTIVE, wait ~30s, then Ctrl-C."
    echo ""

    docker run --rm -it \
        -v "$PERSISTENT_CONFIG_DIR:/app/native-image-config" \
        hedera-node-trace
}

cmd_docker() {
    local profile="${1:-small-memory}"

    echo "=== Assembling ==="
    cd "$REPO_ROOT"
    ./gradlew -p hedera-node/hedera-app :app:assemble -x test -x javadoc

    [[ -d "$NODE_DIR/data/apps" ]] || die "Assembly failed — $NODE_DIR/data/apps not found"
    [[ -d "$PERSISTENT_CONFIG_DIR" ]] || die "No native-image configs. Run '$0 trace' first."

    echo "=== Building Docker image (profile: $profile) ==="
    cd "$REPO_ROOT"
    docker build \
        -f hedera-node/docker/Dockerfile.native \
        --build-context node-data="$NODE_DIR" \
        --build-context native-config="$PERSISTENT_CONFIG_DIR" \
        --build-arg "CONFIGURATION_PROFILE=$profile" \
        -t "$DOCKER_IMAGE" \
        .

    echo ""
    docker images "$DOCKER_IMAGE" --format "Image: {{.Repository}}:{{.Tag}} ({{.Size}})"
}

cmd_docker_run() {
    if ! docker image inspect "$DOCKER_IMAGE" &>/dev/null; then
        die "Docker image '$DOCKER_IMAGE' not found. Run '$0 docker' first."
    fi

    docker rm -f "$DOCKER_CONTAINER" 2>/dev/null || true

    echo "=== Starting Docker container ==="
    docker run \
        --name "$DOCKER_CONTAINER" \
        -d \
        --memory=512m \
        --memory-swap=512m \
        -p 50211:50211 \
        -p 50212:50212 \
        -e CONTAINER_TSR_ENABLED=true \
        "$DOCKER_IMAGE"

    echo "Container: $DOCKER_CONTAINER"
    echo ""
    echo "Logs:    docker logs -f $DOCKER_CONTAINER"
    echo "Stop:    $0 docker-stop"
    echo "Stats:   $0 docker-stats"
}

cmd_docker_stop() {
    echo "Stopping container..."
    docker rm -f "$DOCKER_CONTAINER" 2>/dev/null && echo "Stopped." || echo "Container not running."
}

cmd_docker_stats() {
    if ! docker inspect "$DOCKER_CONTAINER" &>/dev/null 2>&1; then
        die "Container '$DOCKER_CONTAINER' is not running. Run '$0 docker-run' first."
    fi

    echo "=== Container Memory ==="
    docker stats "$DOCKER_CONTAINER" --no-stream \
        --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.PIDs}}"

    echo ""
    echo "=== Process Memory (hedera-node) ==="
    # Find the hedera-node PID inside the container
    local node_pid
    node_pid=$(docker exec "$DOCKER_CONTAINER" ls /proc/ 2>/dev/null \
        | grep -E '^[0-9]+$' | sort -n | while read -r pid; do
            local cmd
            cmd=$(docker exec "$DOCKER_CONTAINER" cat "/proc/$pid/cmdline" 2>/dev/null | tr '\0' ' ')
            if [[ "$cmd" == *hedera-node* ]]; then
                echo "$pid"
                break
            fi
        done)

    if [[ -n "$node_pid" ]]; then
        docker exec "$DOCKER_CONTAINER" cat "/proc/$node_pid/status" 2>/dev/null \
            | grep -E "VmRSS|VmHWM|VmData|VmSize" \
            | while read -r line; do echo "  $line"; done
    else
        echo "  (hedera-node process not found)"
    fi
}

cmd_docker_logs() {
    docker logs -f "$DOCKER_CONTAINER" 2>/dev/null || die "Container '$DOCKER_CONTAINER' is not running."
}

case "${1:-help}" in
    build)        cmd_build ;;
    run)          cmd_run ;;
    build-run)    cmd_build && cmd_clean && cmd_run ;;
    clean)        cmd_clean ;;
    profile)      cmd_profile "${2:-}" ;;
    trace)        cmd_trace ;;
    trace-docker) cmd_trace_docker "${2:-small-memory}" ;;
    docker)       cmd_docker "${2:-small-memory}" ;;
    docker-run)   cmd_docker_run ;;
    docker-stop)  cmd_docker_stop ;;
    docker-stats) cmd_docker_stats ;;
    docker-logs)  cmd_docker_logs ;;
    *)
        cat <<EOF
Usage: $0 <command> [args]

Local commands:
  build              Assemble JARs and compile native image (~1.5 min)
  run                Run the native binary
  build-run          Build, clean state, then run
  clean              Remove old node state (database, output, etc.)
  profile [name]     Switch config profile (default: small-memory)
  trace              Run with tracing agent to regenerate native-image configs
  trace-docker       Run tracing agent in Docker (captures Linux-specific configs)

Docker commands:
  docker [profile]   Build Docker image (default profile: small-memory)
  docker-run         Run the Docker container (ports 50211, 50212)
  docker-stop        Stop and remove the Docker container
  docker-stats       Show container memory usage
  docker-logs        Tail container logs

Available profiles:
EOF
        for d in "$CONFIG_BASE_DIR"/*/; do
            [[ -d "$d" ]] && echo "  $(basename "$d")"
        done
        exit 1
        ;;
esac
