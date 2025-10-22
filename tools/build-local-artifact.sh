#!/usr/bin/env bash
set -euo pipefail

# Build the node artifact similarly to .github/workflows/node-zxc-build-release-artifact.yaml
# Then list all files in the produced zip, and list only JARs contained in it.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

echo "[info] Repository root: ${REPO_ROOT}"

# Ensure gradle wrapper exists
if [[ ! -x "${REPO_ROOT}/gradlew" ]]; then
  echo "[error] ./gradlew not found or not executable. Are you at the repo root?" >&2
  exit 1
fi

# Optional flags
USE_ZIP_FALLBACK=${USE_ZIP_FALLBACK:-false}
SKIP_BUILD=${SKIP_BUILD:-false}

BUILD_BASE_DIR="${REPO_ROOT}/build/artifact-build"
RELEASE_BASE_DIR="${REPO_ROOT}/build/artifact-release"
BIN_DIR="${REPO_ROOT}/build/bin"

rm -rf "${BUILD_BASE_DIR}" "${RELEASE_BASE_DIR}"
mkdir -p "${BUILD_BASE_DIR}/data/lib" "${BUILD_BASE_DIR}/data/apps" "${RELEASE_BASE_DIR}" "${BIN_DIR}"

if [[ "${SKIP_BUILD}" != "true" ]]; then
  echo "[info] Running Gradle assemble..."
  ./gradlew assemble
else
  echo "[info] Skipping Gradle assemble due to SKIP_BUILD=true"
fi

echo "[info] Determining effective version..."
EFF_VERSION="$(./gradlew showVersion --quiet 2>/dev/null | tr -d '[:space:]' || true)"
if [[ -z "${EFF_VERSION}" ]]; then
  EFF_VERSION="0.0.0-local"
fi
COMMIT_SHORT="$(git rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
DATE_UTC="$(date -u)"

echo "[info] Effective version: ${EFF_VERSION}"
echo "[info] Commit: ${COMMIT_SHORT}"

echo "[info] Staging artifact contents..."

# Copy jars and update scripts per workflow
cp -f hedera-node/data/lib/*.jar "${BUILD_BASE_DIR}/data/lib" 2>/dev/null || {
  echo "[error] No jars found at hedera-node/data/lib/*.jar. Did assemble succeed?" >&2
  exit 1
}
cp -f hedera-node/data/apps/*.jar "${BUILD_BASE_DIR}/data/apps" 2>/dev/null || {
  echo "[error] No jars found at hedera-node/data/apps/*.jar. Did assemble succeed?" >&2
  exit 1
}
cp -f hedera-node/configuration/update/immediate.sh "${BUILD_BASE_DIR}"
cp -f hedera-node/configuration/update/during-freeze.sh "${BUILD_BASE_DIR}"

printf "VERSION=%s\nCOMMIT=%s\nDATE=%s\n" "${EFF_VERSION}" "${COMMIT_SHORT}" "${DATE_UTC}" > "${BUILD_BASE_DIR}/VERSION"

ARTIFACT_NAME="build-v${EFF_VERSION}"
ARTIFACT_FILE="${RELEASE_BASE_DIR}/${ARTIFACT_NAME}.zip"

echo "[info] Creating artifact archive at ${ARTIFACT_FILE}"

detzip_path=""
if [[ "${USE_ZIP_FALLBACK}" != "true" ]]; then
  if command -v deterministic-zip >/dev/null 2>&1; then
    detzip_path="$(command -v deterministic-zip)"
  else
    # Download local copy to avoid requiring sudo
    DETZIP_URL="https://github.com/timo-reymann/deterministic-zip/releases/download/1.2.0/deterministic-zip_linux-amd64"
    detzip_path="${BIN_DIR}/deterministic-zip"
    echo "[info] deterministic-zip not found in PATH; downloading to ${detzip_path}"
    curl -sSfL -o "${detzip_path}" "${DETZIP_URL}" || detzip_path=""
    if [[ -n "${detzip_path}" && -f "${detzip_path}" ]]; then
      chmod +x "${detzip_path}"
    fi
  fi
fi

(
  cd "${BUILD_BASE_DIR}"
  if [[ -n "${detzip_path}" && -x "${detzip_path}" ]]; then
    echo "[info] Using deterministic-zip: ${detzip_path}"
    "${detzip_path}" -D -vr "${ARTIFACT_FILE}" *
  else
    echo "[warn] Falling back to system zip (non-deterministic)"
    if command -v zip >/dev/null 2>&1; then
      # -X to eXclude extra file attributes for slightly more reproducible zips
      # Use * (not .) so paths don't get a leading ./
      zip -X -r "${ARTIFACT_FILE}" *
    else
      echo "[error] Neither deterministic-zip nor zip are available. Please install one of them." >&2
      exit 1
    fi
  fi
)

echo "[info] Artifact created: ${ARTIFACT_FILE}"

echo
echo "=== All files in artifact (relative paths) ==="
if command -v zipinfo >/dev/null 2>&1; then
  zipinfo -1 "${ARTIFACT_FILE}"
elif command -v unzip >/dev/null 2>&1; then
  unzip -Z -1 "${ARTIFACT_FILE}"
else
  # Python fallback
  python3 - "$ARTIFACT_FILE" << 'PY'
import sys, zipfile
zf = zipfile.ZipFile(sys.argv[1])
for n in zf.namelist():
    print(n)
PY
fi

echo
echo "=== JAR files in artifact ==="
if command -v zipinfo >/dev/null 2>&1; then
  zipinfo -1 "${ARTIFACT_FILE}" | grep -E '\\.jar$' || true
elif command -v unzip >/dev/null 2>&1; then
  unzip -Z -1 "${ARTIFACT_FILE}" | grep -E '\\.jar$' || true
else
  python3 - "$ARTIFACT_FILE" << 'PY'
import sys, zipfile
zf = zipfile.ZipFile(sys.argv[1])
for n in zf.namelist():
    if n.endswith('.jar'):
        print(n)
PY
fi

echo
echo "=== Contents of every JAR in artifact ==="
jar_paths=""
if command -v zipinfo >/dev/null 2>&1; then
  jar_paths="$(zipinfo -1 "${ARTIFACT_FILE}" | grep -E '\\.jar$' || true)"
elif command -v unzip >/dev/null 2>&1; then
  jar_paths="$(unzip -Z -1 "${ARTIFACT_FILE}" | grep -E '\\.jar$' || true)"
else
  jar_paths="$(python3 - "$ARTIFACT_FILE" << 'PY'
import sys, zipfile
zf = zipfile.ZipFile(sys.argv[1])
print("\n".join([n for n in zf.namelist() if n.endswith('.jar')]))
PY
)"
fi

if [[ -z "${jar_paths}" ]]; then
  echo "[info] No JAR files found in artifact."
else
  IFS=$'\n'
  for jar_path in ${jar_paths}; do
    [[ -z "${jar_path}" ]] && continue
    echo "----- ${jar_path} -----"
    tmp_jar="$(mktemp "${TMPDIR:-/tmp}/artifact-jar.XXXXXX.jar")"
    # Extract the single JAR from the artifact zip into a temp file
    if command -v unzip >/dev/null 2>&1; then
      unzip -p "${ARTIFACT_FILE}" "${jar_path}" > "${tmp_jar}"
    else
      # python fallback to extract the jar bytes
      python3 - "$ARTIFACT_FILE" "$jar_path" "$tmp_jar" << 'PY'
import sys, zipfile
artifact, member, out = sys.argv[1:4]
with zipfile.ZipFile(artifact) as zf:
    with open(out, 'wb') as f:
        f.write(zf.read(member))
PY
    fi

    # List the contents of the extracted JAR
    if command -v jar >/dev/null 2>&1; then
      jar tf "${tmp_jar}" || true
    elif command -v zipinfo >/dev/null 2>&1; then
      zipinfo -1 "${tmp_jar}" || true
    elif command -v unzip >/dev/null 2>&1; then
      unzip -Z -1 "${tmp_jar}" || true
    else
      python3 - "${tmp_jar}" << 'PY'
import sys, zipfile
zf = zipfile.ZipFile(sys.argv[1])
for n in zf.namelist():
    print(n)
PY
    fi

    rm -f "${tmp_jar}"
  done
  unset IFS
fi

echo
echo "[done] ${ARTIFACT_FILE}"


