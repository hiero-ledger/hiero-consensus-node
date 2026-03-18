#!/usr/bin/env bash
set -eo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_URL="https://github.com/hashgraph/hedera-services.git"
REMOTE_REPO_DIR="hedera-services"
REMOTE_RESULTS_DIR="/tmp/benchmark-results"
LOCAL_RESULTS_DIR="$HOME/benchmark-results/remote"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

usage() {
    echo "Usage: $0 <user@server> <branch> [experiment] [num_runs] [options]"
    echo ""
    echo "Runs consensus-sloth benchmark experiments on a remote server via SSH."
    echo ""
    echo "Arguments:"
    echo "  user@server  SSH destination (e.g. ubuntu@10.0.1.5)"
    echo "  branch       Git branch to checkout and benchmark"
    echo "  experiment   Which experiment to run (default: all)"
    echo "    - maxotherparents, antiselfishness, maxcreationrate"
    echo "    - signature, combined, benchmark, all"
    echo "  num_runs     Number of times to run each experiment (default: 1)"
    echo ""
    echo "Benchmark parameter overrides (optional, apply to ConsensusLayerBenchmark):"
    echo "  --tps N                   Transactions per second (default: 20)"
    echo "  --nodes N                 Number of nodes (default: 4)"
    echo "  --stabilization-time Xs   Stabilization duration, e.g. 5s (default: 3s)"
    echo "  --warmup-time Xs          Warmup duration, e.g. 30s (default: 5s)"
    echo "  --benchmark-time Xs       Benchmark measurement duration, e.g. 120s (default: 50s)"
    echo "  --collection-time Xs      Result collection duration, e.g. 15s (default: 10s)"
    echo ""
    echo "The script will:"
    echo "  1. Verify SSH connectivity (key-based auth required)"
    echo "  2. Check no Gradle tasks or other active users are on the server"
    echo "  3. Clone the repo (or update if it already exists)"
    echo "  4. Checkout the specified branch and run benchmarks"
    echo "  5. Tar the results and transfer them to the local machine"
    exit 1
}

# Parse arguments
SSH_DEST=""
BRANCH=""
EXPERIMENT="all"
NUM_RUNS="1"
SLOTH_JVM_PROPS=""

POSITIONAL=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tps)                SLOTH_JVM_PROPS="${SLOTH_JVM_PROPS} -Dsloth.tps=$2";                   shift 2 ;;
        --nodes)              SLOTH_JVM_PROPS="${SLOTH_JVM_PROPS} -Dsloth.numberOfNodes=$2";          shift 2 ;;
        --stabilization-time) SLOTH_JVM_PROPS="${SLOTH_JVM_PROPS} -Dsloth.stabilizationTime=$2";     shift 2 ;;
        --warmup-time)        SLOTH_JVM_PROPS="${SLOTH_JVM_PROPS} -Dsloth.warmupTime=$2";             shift 2 ;;
        --benchmark-time)     SLOTH_JVM_PROPS="${SLOTH_JVM_PROPS} -Dsloth.benchmarkTime=$2";         shift 2 ;;
        --collection-time)    SLOTH_JVM_PROPS="${SLOTH_JVM_PROPS} -Dsloth.collectionTime=$2";        shift 2 ;;
        --*)                  echo -e "${RED}Error: Unknown option: $1${NC}"; usage ;;
        *)
            POSITIONAL=$((POSITIONAL + 1))
            case "$POSITIONAL" in
                1) SSH_DEST="$1" ;;
                2) BRANCH="$1" ;;
                3) EXPERIMENT="$1" ;;
                4) NUM_RUNS="$1" ;;
                *) echo -e "${RED}Error: Unexpected argument: $1${NC}"; usage ;;
            esac
            shift ;;
    esac
done

# Trim leading space from accumulated JVM props
SLOTH_JVM_PROPS="${SLOTH_JVM_PROPS# }"

if [[ -z "$SSH_DEST" || -z "$BRANCH" ]]; then
    usage
fi

# Validate num_runs
if ! [[ "$NUM_RUNS" =~ ^[0-9]+$ ]] || [ "$NUM_RUNS" -lt 1 ]; then
    echo -e "${RED}Error: num_runs must be a positive integer${NC}"
    usage
fi

# Extract server hostname for display
SERVER="${SSH_DEST#*@}"

echo -e "${BLUE}=== Remote Benchmark Configuration ===${NC}"
echo "SSH destination:  $SSH_DEST"
echo "Branch:           $BRANCH"
echo "Experiment(s):    $EXPERIMENT"
echo "Runs per exp:     $NUM_RUNS"
if [[ -n "$SLOTH_JVM_PROPS" ]]; then
    echo "Benchmark params: $SLOTH_JVM_PROPS"
else
    echo "Benchmark params: (defaults)"
fi
echo ""

# ── Step 1: Verify SSH connectivity with key-based auth ──────────────────────
echo -e "${BLUE}=== Step 1: Verifying SSH connectivity ===${NC}"

if ! ssh -o BatchMode=yes -o ConnectTimeout=10 "$SSH_DEST" "echo ok" >/dev/null 2>&1; then
    echo -e "${RED}ERROR: Cannot connect to $SSH_DEST using key-based authentication.${NC}"
    echo "Ensure that:"
    echo "  - The server is reachable"
    echo "  - Your SSH key is authorized on the remote host"
    echo "  - ssh-agent is running and your key is loaded (ssh-add -l)"
    exit 1
fi
echo -e "${GREEN}SSH connection verified (key-based auth).${NC}"
echo ""

# ── Step 2: Verify no Gradle tasks or active users ──────────────────────────
echo -e "${BLUE}=== Step 2: Checking remote server is idle ===${NC}"

REMOTE_CHECK=$(ssh -o BatchMode=yes "$SSH_DEST" -t bash <<'REMOTE_EOF'
ISSUES=""

# Check for active Gradle builds (ignore idle daemons and worker processes)
GRADLE_PROCS=$(pgrep -af "gradlew|org.gradle.launcher.GradleMain|org.gradle.wrapper.GradleWrapperMain" 2>/dev/null | head -5 || true)
if [[ -n "$GRADLE_PROCS" ]]; then
    ISSUES="${ISSUES}GRADLE_RUNNING\n${GRADLE_PROCS}\n"
fi

# Check for other active interactive users (exclude our own session)
CURRENT_TTY=$(tty 2>/dev/null | sed 's|/dev/||' || echo "notty")
ACTIVE_USERS=$(who | grep -v "$CURRENT_TTY" || true)
if [[ -n "$ACTIVE_USERS" ]]; then
    ISSUES="${ISSUES}ACTIVE_USERS\n${ACTIVE_USERS}\n"
fi

if [[ -n "$ISSUES" ]]; then
    echo "BUSY"
    echo -e "$ISSUES"
else
    echo "IDLE"
fi
REMOTE_EOF
)

FIRST_LINE=$(echo "$REMOTE_CHECK" | head -1)

if [[ "$FIRST_LINE" == "BUSY" ]]; then
    echo -e "${RED}ERROR: Remote server is not idle.${NC}"
    echo "$REMOTE_CHECK" | tail -n +2 | while IFS= read -r line; do
        if [[ "$line" == "GRADLE_RUNNING" ]]; then
            echo -e "${YELLOW}  Gradle processes detected:${NC}"
        elif [[ "$line" == "ACTIVE_USERS" ]]; then
            echo -e "${YELLOW}  Active users detected:${NC}"
        elif [[ -n "$line" ]]; then
            echo "    $line"
        fi
    done
    echo ""
    echo "Wait for the server to be idle or terminate the above processes before retrying."
    exit 1
fi

echo -e "${GREEN}Server is idle — no Gradle tasks or active users.${NC}"
echo ""

# ── Step 3: Clone or update repo and checkout branch ─────────────────────────
echo -e "${BLUE}=== Step 3: Preparing repository on remote (branch: $BRANCH) ===${NC}"

ssh -o BatchMode=yes "$SSH_DEST" -t bash <<REMOTE_EOF
set -eo pipefail

if [[ ! -d "$REMOTE_REPO_DIR/.git" ]]; then
    echo "Cloning repository..."
    git clone "$REPO_URL" "$REMOTE_REPO_DIR"
    cd "$REMOTE_REPO_DIR"
else
    echo "Repository already exists, fetching latest..."
    cd "$REMOTE_REPO_DIR"
    git fetch --all --prune
fi

echo "Checking out branch: $BRANCH"
git checkout "$BRANCH" 2>/dev/null || git checkout -b "$BRANCH" "origin/$BRANCH"
git pull --ff-only origin "$BRANCH" || true

echo "Branch is at: \$(git log --oneline -1)"
REMOTE_EOF

echo -e "${GREEN}Repository ready on remote.${NC}"
echo ""

# ── Step 4: Run the benchmark remotely ───────────────────────────────────────
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
REMOTE_TMP_RESULTS="/tmp/benchmark-remote-${TIMESTAMP}"

echo -e "${BLUE}=== Step 4: Running benchmark on remote server ===${NC}"
echo "Remote results will be stored at: $REMOTE_TMP_RESULTS"
echo ""

BENCHMARK_EXIT=0
ssh -o BatchMode=yes "$SSH_DEST" -t bash <<REMOTE_EOF || BENCHMARK_EXIT=$?
set -eo pipefail

# Source profile to pick up JAVA_HOME and PATH in non-interactive SSH sessions
# User profiles are sourced last so they can override system-wide settings (e.g. /etc/profile.d/)
for f in "/etc/profile" "\$HOME/.profile" "\$HOME/.bash_profile" "\$HOME/.bashrc"; do
    [[ -f "\$f" ]] && source "\$f" 2>/dev/null || true
done

# Verify Java is available
if [[ -z "\$JAVA_HOME" ]] && ! command -v java &>/dev/null; then
    echo "ERROR: JAVA_HOME is not set and 'java' is not in PATH on the remote server."
    echo "Install a JDK (25+) and set JAVA_HOME in ~/.profile or ~/.bashrc."
    exit 1
fi
echo "Using Java: \${JAVA_HOME:-\$(dirname \$(dirname \$(readlink -f \$(which java))))}"

cd "$REMOTE_REPO_DIR"

# Override RESULTS_BASE_DIR so run-benchmark.sh writes to our tmp location
export RESULTS_BASE_DIR="$REMOTE_TMP_RESULTS"
mkdir -p "$REMOTE_TMP_RESULTS"

SCRIPT_PATH="platform-sdk/consensus-sloth/src/testPerformance/scripts/run-benchmark.sh"
if [[ ! -f "\$SCRIPT_PATH" ]]; then
    echo "ERROR: run-benchmark.sh not found at \$SCRIPT_PATH"
    exit 1
fi

chmod +x "\$SCRIPT_PATH"
export SLOTH_JVM_PROPS="$SLOTH_JVM_PROPS"
bash "\$SCRIPT_PATH" "$EXPERIMENT" "$NUM_RUNS"
REMOTE_EOF

echo ""
if [[ "$BENCHMARK_EXIT" -ne 0 ]]; then
    echo -e "${YELLOW}⚠️  Benchmark exited with code $BENCHMARK_EXIT — results (if any) will still be downloaded.${NC}"
else
    echo -e "${GREEN}Remote benchmark completed.${NC}"
fi
echo ""

# ── Step 5: Tar results and transfer to local machine ────────────────────────
echo -e "${BLUE}=== Step 5: Transferring results to local machine ===${NC}"

REMOTE_TAR="/tmp/benchmark-results-${TIMESTAMP}.tar.gz"
LOCAL_TAR_DIR="$LOCAL_RESULTS_DIR"
mkdir -p "$LOCAL_TAR_DIR"
LOCAL_TAR="${LOCAL_TAR_DIR}/benchmark-${SERVER}-${BRANCH//\//-}-${TIMESTAMP}.tar.gz"

# Create tar on remote (best-effort: archive whatever results exist)
TRANSFER_EXIT=0
ssh -o BatchMode=yes "$SSH_DEST" -t bash <<REMOTE_EOF || TRANSFER_EXIT=$?
set -eo pipefail
if [[ ! -d "$REMOTE_TMP_RESULTS" ]] || [[ -z "\$(ls -A "$REMOTE_TMP_RESULTS")" ]]; then
    echo "WARNING: No results found at $REMOTE_TMP_RESULTS — nothing to archive."
    exit 2
fi
echo "Compressing results..."
tar -czf "$REMOTE_TAR" -C "$REMOTE_TMP_RESULTS" .
echo "Archive size: \$(du -h "$REMOTE_TAR" | cut -f1)"
REMOTE_EOF

# Transfer to local (skip if there was nothing to archive)
if [[ "$TRANSFER_EXIT" -eq 2 ]]; then
    echo -e "${YELLOW}⚠️  No results were produced — skipping download.${NC}"
elif [[ "$TRANSFER_EXIT" -ne 0 ]]; then
    echo -e "${RED}ERROR: Failed to create remote archive (exit $TRANSFER_EXIT) — skipping download.${NC}"
else
    echo "Downloading results to $LOCAL_TAR ..."
    scp -o BatchMode=yes "$SSH_DEST:$REMOTE_TAR" "$LOCAL_TAR"
fi

# Clean up remote temp files and stop Gradle daemons (always)
echo "Cleaning up remote temporary files..."
ssh -o BatchMode=yes "$SSH_DEST" "rm -rf '$REMOTE_TMP_RESULTS' '$REMOTE_TAR'" || true
echo "Stopping Gradle daemons on remote..."
ssh -o BatchMode=yes "$SSH_DEST" -t bash <<'STOP_EOF'
for f in "$HOME/.profile" "$HOME/.bash_profile" "$HOME/.bashrc" "/etc/profile"; do
    [[ -f "$f" ]] && source "$f" 2>/dev/null || true
done
cd hedera-services && ./gradlew --stop 2>/dev/null || true
STOP_EOF

echo ""
if [[ "$BENCHMARK_EXIT" -ne 0 ]]; then
    echo -e "${YELLOW}######################################################################${NC}"
    echo -e "${YELLOW}=== Remote benchmark FAILED (exit code: $BENCHMARK_EXIT) ===${NC}"
    echo -e "${YELLOW}######################################################################${NC}"
else
    echo -e "${GREEN}######################################################################${NC}"
    echo -e "${GREEN}=== Remote benchmark complete ===${NC}"
    echo -e "${GREEN}######################################################################${NC}"
fi
echo ""

# Show results summary only if we have a local archive
if [[ -f "$LOCAL_TAR" ]]; then
    echo "Results archive: $LOCAL_TAR"
    echo ""
    echo -e "${BLUE}Archive contents:${NC}"
    tar -tzf "$LOCAL_TAR" | head -30
    RESULT_COUNT=$(tar -tzf "$LOCAL_TAR" | grep -c '/$' || true)
    echo "  ($RESULT_COUNT directories)"
    echo ""
    echo "To extract:  tar -xzf $LOCAL_TAR -C <destination>"
fi

# Propagate benchmark failure to the caller
if [[ "$BENCHMARK_EXIT" -ne 0 ]]; then
    exit "$BENCHMARK_EXIT"
fi