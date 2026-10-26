#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Shared offline wrapping, jumpstart parsing, and replay validation for Solo cutover scenarios.
# Callers supply the artifact paths and run_step/log/require_cmd/validate_block_node_repo helpers.

# Use the same tools revision locally as in CI. A newer BN checkout can compile against
# different HAPI wire definitions (including BlockHashAlgorithm enum numbers), changing
# every wrapped-block hash. Never switch or reset the caller's BN working tree.
prepare_block_node_tools_checkout() {
  local tools_ref="${BLOCK_NODE_TOOLS_REF:-v0.45.0-sha256.1}"
  local source_repo="${BLOCK_NODE_REPO_PATH}" revision current checkout
  require_cmd git
  revision="$(git -C "${source_repo}" rev-parse --verify "${tools_ref}^{commit}")" || {
    echo "Missing Block Node tools ref ${tools_ref}; fetch it in ${source_repo} before running cutover" >&2
    return 1
  }
  current="$(git -C "${source_repo}" rev-parse HEAD)"
  if [[ "${current}" == "${revision}" && -z "$(git -C "${source_repo}" status --porcelain --untracked-files=no)" ]]; then
    log "Using Block Node tools ${tools_ref} (${revision}) from ${source_repo}"
    return 0
  fi

  checkout="${BLOCK_NODE_TOOLS_CACHE_DIR:-${HOME}/.cache/hiero-solo/block-node-tools}/${revision}"
  if [[ ! -e "${checkout}" ]]; then
    mkdir -p "$(dirname "${checkout}")"
    git -C "${source_repo}" worktree add --detach "${checkout}" "${revision}" || return 1
  fi
  if [[ "$(git -C "${checkout}" rev-parse HEAD)" != "${revision}" ||
        -n "$(git -C "${checkout}" status --porcelain --untracked-files=no)" ]]; then
    echo "Cached Block Node tools checkout is modified or has the wrong revision: ${checkout}" >&2
    return 1
  fi
  BLOCK_NODE_REPO_PATH="${checkout}"
  log "Using isolated Block Node tools ${tools_ref} (${revision}) at ${checkout}"
}

ensure_zstd_command_for_block_node() {
  local zstd_jar
  if command -v zstd >/dev/null 2>&1; then
    log "Using system zstd: $(command -v zstd)"
    return 0
  fi

  require_cmd java

  # `|| true`: under `set -euo pipefail`, `var="$(find <missing-dir> ... | head)"` aborts the
  # whole script silently (find exits non-zero on a missing path, pipefail propagates it, and a
  # failing command substitution trips set -e). Guard it so a missing cache just yields an empty
  # jar and the explicit check below reports it.
  zstd_jar="$(find "${HOME}/.gradle/caches/modules-2/files-2.1/com.github.luben/zstd-jni" -name 'zstd-jni-*.jar' 2>/dev/null | head -n 1)" || true
  if [[ -z "${zstd_jar}" || ! -f "${zstd_jar}" ]]; then
    echo "zstd command not found and zstd-jni jar was not found in ~/.gradle cache." >&2
    echo "Install zstd (for example: brew install zstd) or run one block-node tools task once to download zstd-jni, then retry." >&2
    return 1
  fi

  mkdir -p "${ZSTD_WRAPPER_DIR}"
  cat > "${ZSTD_WRAPPER_SRC}" <<'EOF'
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import com.github.luben.zstd.ZstdInputStream;

public class ZstdCat {
  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: ZstdCat <input.zstd>");
      System.exit(2);
    }
    try (InputStream in = new BufferedInputStream(new FileInputStream(args[0]));
         ZstdInputStream zin = new ZstdInputStream(in);
         OutputStream out = new BufferedOutputStream(System.out)) {
      zin.transferTo(out);
      out.flush();
    }
  }
}
EOF

  cat > "${ZSTD_WRAPPER_BIN}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
input=""
for arg in "$@"; do
  case "$arg" in
    --decompress|-d|--stdout|-c) ;;
    -T*|--threads=*) ;;
    --) ;;
    -*) ;;
    *) input="$arg" ;;
  esac
done
if [[ -z "${input}" ]]; then
  echo "zstd wrapper error: missing input file argument" >&2
  exit 2
fi
if [[ -z "${ZSTD_JNI_JAR:-}" || -z "${ZSTD_WRAPPER_SRC:-}" ]]; then
  echo "zstd wrapper error: ZSTD_JNI_JAR or ZSTD_WRAPPER_SRC is not set" >&2
  exit 2
fi
exec java --class-path "${ZSTD_JNI_JAR}" "${ZSTD_WRAPPER_SRC}" "${input}"
EOF
  chmod +x "${ZSTD_WRAPPER_BIN}"

  export ZSTD_JNI_JAR="${zstd_jar}"
  export ZSTD_WRAPPER_SRC
  export PATH="${ZSTD_WRAPPER_DIR}:${PATH}"
}


minio_discover_service() {
  local ns="$1"
  local svc
  if [[ -n "${MINIO_SERVICE_NAME}" ]]; then
    echo "${MINIO_SERVICE_NAME}"
    return 0
  fi
  if kubectl -n "${ns}" get svc minio >/dev/null 2>&1; then
    echo "minio"
    return 0
  fi
  if kubectl -n "${ns}" get svc minio-hl >/dev/null 2>&1; then
    echo "minio-hl"
    return 0
  fi
  svc="$(kubectl -n "${ns}" get svc -o json 2>/dev/null | jq -r '
    .items[].metadata.name
    | select(test("minio"; "i"))
    | select(test("console"; "i") | not)
    | select(test("headless"; "i") | not)
  ' | head -n 1)"
  if [[ -z "${svc}" ]]; then
    return 1
  fi
  echo "${svc}"
}

minio_discover_service_port() {
  local ns="$1"
  local svc="$2"
  local port
  # Prefer the service port that targets container port 9000.
  port="$(kubectl -n "${ns}" get svc "${svc}" -o json 2>/dev/null | jq -r '
    first(.spec.ports[] | select((.targetPort|tostring) == "9000") | .port // empty)
  ')"
  if [[ -z "${port}" || "${port}" == "null" ]]; then
    port="$(kubectl -n "${ns}" get svc "${svc}" -o json 2>/dev/null | jq -r '.spec.ports[0].port // empty')"
  fi
  [[ -n "${port}" && "${port}" != "null" ]] || return 1
  echo "${port}"
}

minio_discover_pod_credentials() {
  local ns="$1"
  local pod u="" p="" cfg
  pod="$(kubectl -n "${ns}" get pods -o json 2>/dev/null | jq -r '
    .items[].metadata.name
    | select(test("^minio-"))
  ' | head -n 1)"
  [[ -n "${pod}" ]] || return 1

  # ${MINIO_CONFIG_ENV_FILE} is intentionally expanded inside the minio container (not by the host shell),
  # since the env var is set by the minio chart on the pod. Single quotes around the sh -lc payload are required.
  # shellcheck disable=SC2016
  cfg="$(kubectl -n "${ns}" exec "${pod}" -c minio -- sh -c 'cat "${MINIO_CONFIG_ENV_FILE:-/tmp/minio/config.env}" 2>/dev/null || true' 2>/dev/null || true)"
  if [[ -n "${cfg}" ]]; then
    u="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_USER=//p' | head -1 | tr -d '\r')"
    p="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_PASSWORD=//p' | head -1 | tr -d '\r')"
    if [[ -z "${u}" || -z "${p}" ]]; then
      u="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ACCESS_KEY=//p' | head -1 | tr -d '\r')"
      p="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_SECRET_KEY=//p' | head -1 | tr -d '\r')"
    fi
  fi
  u="${u//$'\r'/}"
  p="${p//$'\r'/}"
  u="${u%\"}"
  u="${u#\"}"
  p="${p%\"}"
  p="${p#\"}"
  if [[ -n "${u}" && -n "${p}" ]]; then
    printf '%s\n' "${u}" "${p}"
    return 0
  fi
  return 1
}

minio_discover_secret_env_credentials() {
  local ns="$1"
  local secret="$2"
  local cfg u="" p=""
  cfg="$(kubectl -n "${ns}" get secret "${secret}" -o jsonpath='{.data.config\.env}' 2>/dev/null | base64 -d 2>/dev/null || true)"
  [[ -n "${cfg}" ]] || return 1
  u="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_USER=//p' | head -1 | tr -d '\r')"
  p="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_PASSWORD=//p' | head -1 | tr -d '\r')"
  u="${u%\"}"
  u="${u#\"}"
  p="${p%\"}"
  p="${p#\"}"
  if [[ -n "${u}" && -n "${p}" ]]; then
    printf '%s\n' "${u}" "${p}"
    return 0
  fi
  return 1
}

download_solo_record_streams_via_pod_mc() {
  local names_file="$1"
  local svc="$2"
  local svc_port="$3"
  local pod endpoint creds_tmp all_objects creds_file
  local wanted_timestamps selected_objects
  local u p selected_u selected_p remote subpath dest
  local server_url cfg_full
  local list_ok=0 endpoint_try
  local wanted_count
  local found=0 sig_found=0 failed=0

  pod="$(kubectl -n "${MINIO_NAMESPACE}" get pods -o json 2>/dev/null | jq -r '
    .items[].metadata.name
    | select(test("^minio-"))
  ' | head -n 1)"
  [[ -n "${pod}" ]] || {
    echo "Could not find MinIO pod in namespace ${MINIO_NAMESPACE}" >&2
    return 1
  }

  creds_file="$(mktemp)"
  creds_tmp="$(mktemp)"
  if minio_discover_pod_credentials "${MINIO_NAMESPACE}" >"${creds_tmp}"; then
    paste -sd '\t' "${creds_tmp}" >>"${creds_file}"
  fi
  : >"${creds_tmp}"
  if minio_discover_secret_env_credentials "${MINIO_NAMESPACE}" "minio-secrets" >"${creds_tmp}"; then
    paste -sd '\t' "${creds_tmp}" >>"${creds_file}"
  fi
  : >"${creds_tmp}"
  if minio_discover_secret_env_credentials "${MINIO_NAMESPACE}" "myminio-env-configuration" >"${creds_tmp}"; then
    paste -sd '\t' "${creds_tmp}" >>"${creds_file}"
  fi
  rm -f "${creds_tmp}"
  if [[ ! -s "${creds_file}" ]]; then
    rm -f "${creds_file}"
    echo "Could not discover any MinIO root credentials in namespace ${MINIO_NAMESPACE}" >&2
    return 1
  fi

  cfg_full="$(kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -c \
    "cat \"\${MINIO_CONFIG_ENV_FILE:-/tmp/minio/config.env}\" 2>/dev/null || true" 2>/dev/null || true)"
  server_url="$(echo "${cfg_full}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_SERVER_URL=//p' | head -1 | tr -d '"\r')"

  all_objects="$(mktemp)"
  # Retries plus alternate in-cluster endpoints avoid transient DNS/service hiccups during upgrade.
  for _ in 1 2 3 4 5 6; do
    for endpoint_try in \
      "${server_url}" \
      "http://${svc}.${MINIO_NAMESPACE}.svc.cluster.local:${svc_port}" \
      "http://minio-hl.${MINIO_NAMESPACE}.svc.cluster.local:9000"; do
      [[ -n "${endpoint_try}" ]] || continue
      endpoint="${endpoint_try}"
      while IFS=$'\t' read -r u p; do
        [[ -n "${u}" && -n "${p}" ]] || continue
        if kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -c \
          "mc alias set local '${endpoint}' '${u}' '${p}' >/dev/null 2>&1; mc find local/${MINIO_BUCKET}/recordstreams --name '*.rcd*'" \
          >"${all_objects}" 2>/tmp/inpod-mc-list.err; then
          selected_u="${u}"
          selected_p="${p}"
          list_ok=1
          break
        fi
      done < "${creds_file}"
      (( list_ok == 1 )) && break
    done
    (( list_ok == 1 )) && break
    sleep 2
  done
  rm -f "${creds_file}" >/dev/null 2>&1 || true
  if (( list_ok == 0 )); then
    rm -f "${all_objects}"
    echo "Failed to list MinIO objects via in-pod mc" >&2
    return 1
  fi

  wanted_timestamps="$(mktemp)"
  selected_objects="$(mktemp)"
  awk '{
    f = $0;
    sub(/^.*\//, "", f);
    if (match(f, /Z/)) {
      print substr(f, 1, RSTART);
    }
  }' "${names_file}" | sort -u > "${wanted_timestamps}"
  wanted_count="$(wc -l < "${wanted_timestamps}" | tr -d ' ')"
  if [[ "${wanted_count}" == "0" ]]; then
    rm -f "${wanted_timestamps}" "${selected_objects}" "${all_objects}" >/dev/null 2>&1 || true
    echo "Could not derive wanted timestamps from mirror names file" >&2
    return 1
  fi

  awk 'NR == FNR { wanted[$1] = 1; next }
    {
      bn = $0;
      sub(/^.*\//, "", bn);
      if (match(bn, /Z/)) {
        ts = substr(bn, 1, RSTART);
        if (wanted[ts]) {
          print $0;
        }
      }
    }' "${wanted_timestamps}" "${all_objects}" | sort -u > "${selected_objects}"

  while IFS= read -r remote; do
    [[ -z "${remote}" ]] && continue

    subpath="${remote#local/"${MINIO_BUCKET}"/recordstreams/}"
    if [[ "${subpath}" == "${remote}" ]]; then
      subpath="$(basename "${remote}")"
    fi
    dest="${RECORD_STREAMS_DIR}/${subpath}"
    mkdir -p "$(dirname "${dest}")"

    local copied=0
    for _ in 1 2 3; do
      if kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -c \
        "mc alias set local '${endpoint}' '${selected_u}' '${selected_p}' >/dev/null 2>&1; mc cat '${remote}'" \
        >"${dest}" 2>/dev/null; then
        copied=1
        break
      fi
      sleep 1
    done
    if (( copied == 1 )); then
      found=$((found + 1))
      if [[ "${remote}" == *.rcd_sig || "${remote}" == *.rcs_sig ]]; then
        sig_found=$((sig_found + 1))
      fi
    else
      rm -f "${dest}" >/dev/null 2>&1 || true
      failed=$((failed + 1))
    fi
  done < "${selected_objects}"

  rm -f "${wanted_timestamps}" >/dev/null 2>&1 || true
  rm -f "${selected_objects}" >/dev/null 2>&1 || true
  rm -f "${all_objects}" >/dev/null 2>&1 || true

  if (( found == 0 )); then
    return 1
  fi
  if (( sig_found == 0 )); then
    echo "No signature files were downloaded from MinIO for selected timestamps" >&2
    return 1
  fi
  return 0
}

# Mirror may return an absolute URL or a path-only next link.
mirror_resolve_next_url() {
  local base="$1"
  local next="$2"
  if [[ -z "${next}" ]]; then
    echo ""
    return 0
  fi
  if [[ "${next}" == http://* || "${next}" == https://* ]]; then
    echo "${next}"
    return 0
  fi
  if [[ "${next}" == /* ]]; then
    local origin
    origin="$(echo "${base}" | sed -E 's|(https?://[^/]+).*|\1|')"
    echo "${origin}${next}"
    return 0
  fi
  echo "${base%/}/${next}"
}

# Paginate mirror /api/v1/blocks (ascending), collect unique record file basenames for blocks with number <= max_block.
collect_record_filenames_up_to_block() {
  local mirror_base="$1"
  local max_block="$2"
  local out_file="$3"
  local next_url="${mirror_base%/}/api/v1/blocks?order=asc&limit=100"
  local j last_num count
  : >"${out_file}"
  while [[ -n "${next_url}" ]]; do
    j="$(curl -sf "${next_url}")" || return 1
    count="$(echo "${j}" | jq '.blocks | length')"
    if [[ "${count}" == "0" || "${count}" == "null" ]]; then
      break
    fi
    echo "${j}" | jq -r --argjson max "${max_block}" '
      .blocks[]
      | select(.number <= $max)
      | (.name // empty)
      | split("/")
      | last
      | select(length > 0)
    ' >>"${out_file}"
    last_num="$(echo "${j}" | jq -r '.blocks[-1].number')"
    if [[ "${last_num}" == "null" ]]; then
      break
    fi
    if (( last_num >= max_block )); then
      break
    fi
    next_url="$(mirror_resolve_next_url "${mirror_base}" "$(echo "${j}" | jq -r '.links.next // empty')")"
  done
  sort -u "${out_file}" -o "${out_file}"
}

# Download record stream objects from the Solo MinIO bucket (default solo-streams) whose basenames appear
# on blocks <= max_block in the mirror (same names as /api/v1/blocks[].name).
download_solo_minio_record_streams() {
  local max_block="$1"
  local mirror_base="$2"
  local names_file svc svc_port nfiles

  mkdir -p "${RECORD_STREAMS_DIR}"
  names_file="$(mktemp)"
  log "Collecting record stream file names from mirror for blocks <= ${max_block}"
  collect_record_filenames_up_to_block "${mirror_base}" "${max_block}" "${names_file}" || {
    echo "Failed to list blocks from mirror for record file discovery" >&2
    rm -f "${names_file}"
    return 1
  }
  if [[ ! -s "${names_file}" ]]; then
    log "No record file names from mirror (empty result); skipping MinIO download"
    rm -f "${names_file}"
    return 0
  fi
  nfiles="$(wc -l < "${names_file}" | tr -d ' ')"
  log "Found ${nfiles} unique record stream file name(s) to resolve in MinIO"

  svc="$(minio_discover_service "${MINIO_NAMESPACE}")" || {
    echo "Could not find a MinIO Service in namespace ${MINIO_NAMESPACE}" >&2
    rm -f "${names_file}"
    return 1
  }
  svc_port="$(minio_discover_service_port "${MINIO_NAMESPACE}" "${svc}")" || {
    echo "Could not resolve service port for MinIO service ${svc}" >&2
    rm -f "${names_file}"
    return 1
  }

  if ! download_solo_record_streams_via_pod_mc "${names_file}" "${svc}" "${svc_port}"; then
    echo "Unable to download from in-pod MinIO fallback in namespace ${MINIO_NAMESPACE}" >&2
    rm -f "${names_file}"
    return 1
  fi
  rm -f "${names_file}"
}


write_jumpstart_parser() {
  cat > "${JUMPSTART_PARSE_SCRIPT}" <<'EOF'
const fs = require("fs");

function fail(msg) {
  console.error(`FAIL: ${msg}`);
  process.exit(1);
}

const file = process.argv[2];
if (!file) fail("Missing jumpstart.bin path argument");

let b;
try {
  b = fs.readFileSync(file);
} catch (e) {
  fail(`Unable to read jumpstart file '${file}': ${e.message}`);
}

// Layout:
//   [0..7]    blockNumber                (long, 8 bytes)
//   [8..55]   previousWrappedBlockHash   (SHA-384, 48 bytes)
//   [56..103] consensusTimestampHash     (SHA-384, 48 bytes)
//   [104..151] outputItemsTreeRootHash   (SHA-384, 48 bytes)
//   [152..159] streamingHasherLeafCount  (long, 8 bytes)
//   [160..163] streamingHasherHashCount  (int, 4 bytes)
//   [164..]   streamingHasher subtree hashes (48 bytes each)
const HEADER_SIZE = 164;
const HASH_BYTES = 48;

if (b.length < HEADER_SIZE) {
  fail(`jumpstart.bin too small: ${b.length} bytes (expected at least ${HEADER_SIZE})`);
}

const blockNum = b.readBigInt64BE(0);
const prevHash = b.subarray(8, 56).toString("hex");
const consensusTimestampHash = b.subarray(56, 104).toString("hex");
const outputItemsTreeRootHash = b.subarray(104, 152).toString("hex");
const leafCount = b.readBigInt64BE(152);
const hashCount = b.readInt32BE(160);
if (blockNum < 0n || leafCount <= 0n) fail("Invalid block number or leaf count");
if (hashCount < 0) {
  fail(`Invalid negative hashCount ${hashCount}`);
}

const expected = HEADER_SIZE + (hashCount * HASH_BYTES);
if (b.length !== expected) {
  fail(`jumpstart.bin size mismatch: got ${b.length}, expected ${expected} (hashCount=${hashCount})`);
}

const subtreeHashes = [];
let offset = HEADER_SIZE;
for (let i = 0; i < hashCount; i += 1) {
  subtreeHashes.push(b.subarray(offset, offset + HASH_BYTES).toString("hex"));
  offset += HASH_BYTES;
}

console.log(`JUMPSTART_BLOCK_NUMBER=${blockNum.toString()}`);
console.log(`JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH=${prevHash}`);
console.log(`JUMPSTART_CONSENSUS_TIMESTAMP_HASH=${consensusTimestampHash}`);
console.log(`JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH=${outputItemsTreeRootHash}`);
console.log(`JUMPSTART_STREAMING_HASHER_LEAF_COUNT=${leafCount.toString()}`);
console.log(`JUMPSTART_STREAMING_HASHER_HASH_COUNT=${hashCount}`);
console.log(`JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES=${subtreeHashes.join(",")}`);
EOF
}

write_mirror_metadata_generator() {
  cat > "${MIRROR_METADATA_SCRIPT}" <<'EOF'
const fs = require("fs");
const path = require("path");

const FIRST_BLOCK_TIME = "2019-09-13T21:53:51.396440Z";

function fail(msg) {
  console.error(`FAIL: ${msg}`);
  process.exit(1);
}

function parseTimestampToEpochNanos(tsLike) {
  const ts = String(tsLike).replace(/_/g, ":");
  const m = ts.match(
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/
  );
  if (!m) {
    throw new Error(`Invalid timestamp format: ${tsLike}`);
  }
  const [
    ,
    y,
    mo,
    d,
    h,
    mi,
    s,
    fracRaw = "",
  ] = m;
  const ms = Date.UTC(
    Number(y),
    Number(mo) - 1,
    Number(d),
    Number(h),
    Number(mi),
    Number(s)
  );
  const epochSeconds = BigInt(Math.floor(ms / 1000));
  const fracNanos = BigInt((fracRaw + "000000000").slice(0, 9));
  return (epochSeconds * 1_000_000_000n) + fracNanos;
}

function recordNameToEpochNanos(recordName) {
  const base = path.basename(String(recordName));
  const z = base.indexOf("Z");
  if (z < 0) {
    throw new Error(`Record file name does not include Z timestamp: ${recordName}`);
  }
  const ts = base.slice(0, z + 1);
  return parseTimestampToEpochNanos(ts);
}

function resolveNextUrl(base, next) {
  if (!next) {
    return "";
  }
  if (next.startsWith("http://") || next.startsWith("https://")) {
    return next;
  }
  if (next.startsWith("/")) {
    return `${base}${next}`;
  }
  return `${base}/${next}`;
}

async function fetchAllBlocksUpTo(mirrorBase, maxBlock) {
  const blocks = [];
  let nextUrl = `${mirrorBase}/api/v1/blocks?order=asc&limit=100`;
  while (nextUrl) {
    const response = await fetch(nextUrl);
    if (!response.ok) {
      throw new Error(`HTTP ${response.status} from ${nextUrl}`);
    }
    const body = await response.json();
    const page = Array.isArray(body.blocks) ? body.blocks : [];
    if (page.length === 0) {
      break;
    }

    for (const b of page) {
      const n = Number(b.number);
      if (!Number.isFinite(n)) {
        continue;
      }
      if (n > maxBlock) {
        return blocks;
      }
      blocks.push({
        number: n,
        name: b.name || "",
        hash: String(b.hash || "").replace(/^0x/i, ""),
      });
    }

    const lastNumber = Number(page[page.length - 1].number);
    if (Number.isFinite(lastNumber) && lastNumber >= maxBlock) {
      break;
    }
    nextUrl = resolveNextUrl(mirrorBase, body.links && body.links.next);
  }
  return blocks;
}

function ensureNoBlockGaps(sortedBlocks) {
  if (sortedBlocks.length < 2) {
    return;
  }
  for (let i = 1; i < sortedBlocks.length; i += 1) {
    const expected = sortedBlocks[i - 1].number + 1;
    const actual = sortedBlocks[i].number;
    if (actual !== expected) {
      throw new Error(`Gap in mirror blocks: expected ${expected}, got ${actual}`);
    }
  }
}

function dayFromRecordName(recordName) {
  const base = path.basename(String(recordName));
  const z = base.indexOf("Z");
  if (z < 0) {
    throw new Error(`Record file name does not include Z timestamp: ${recordName}`);
  }
  const ts = base.slice(0, z + 1).replace(/_/g, ":");
  return ts.slice(0, 10);
}

async function main() {
  const mirrorBase = String(process.env.MIRROR_REST_URL || "http://127.0.0.1:5551").replace(/\/$/, "");
  const maxBlockRaw = process.env.MIRROR_BLOCK_NUMBER;
  const blockTimesFile = process.env.BLOCK_TIMES_FILE;
  const dayBlocksFile = process.env.DAY_BLOCKS_FILE;
  if (!maxBlockRaw) fail("MIRROR_BLOCK_NUMBER is required");
  if (!blockTimesFile) fail("BLOCK_TIMES_FILE is required");
  if (!dayBlocksFile) fail("DAY_BLOCKS_FILE is required");

  const maxBlock = Number(maxBlockRaw);
  if (!Number.isInteger(maxBlock) || maxBlock < 0) {
    fail(`Invalid MIRROR_BLOCK_NUMBER: ${maxBlockRaw}`);
  }

  const blocks = await fetchAllBlocksUpTo(mirrorBase, maxBlock);
  if (blocks.length === 0) {
    fail("Mirror returned no blocks for metadata generation");
  }
  blocks.sort((a, b) => a.number - b.number);
  ensureNoBlockGaps(blocks);
  const highest = blocks[blocks.length - 1].number;
  if (highest < maxBlock) {
    fail(`Mirror highest fetched block ${highest} is below requested ${maxBlock}`);
  }

  const firstEpochNanos = parseTimestampToEpochNanos(FIRST_BLOCK_TIME);
  const buf = Buffer.alloc((maxBlock + 1) * 8);
  const byDay = new Map();

  for (const b of blocks) {
    const epochNanos = recordNameToEpochNanos(b.name);
    const blockTime = epochNanos - firstEpochNanos;
    if (blockTime < 0n) {
      fail(`Negative block time for block ${b.number} (${b.name})`);
    }
    buf.writeBigInt64BE(blockTime, b.number * 8);

    const day = dayFromRecordName(b.name);
    const [year, month, dayNum] = day.split("-").map(Number);
    const prev = byDay.get(day);
    if (!prev) {
      byDay.set(day, {
        year,
        month,
        day: dayNum,
        firstBlockNumber: b.number,
        firstBlockHash: b.hash,
        lastBlockNumber: b.number,
        lastBlockHash: b.hash,
      });
    } else {
      prev.lastBlockNumber = b.number;
      prev.lastBlockHash = b.hash;
    }
  }

  fs.mkdirSync(path.dirname(blockTimesFile), { recursive: true });
  fs.mkdirSync(path.dirname(dayBlocksFile), { recursive: true });
  fs.writeFileSync(blockTimesFile, buf);

  const dayBlocks = Array.from(byDay.values()).sort((a, b) => {
    if (a.year !== b.year) return a.year - b.year;
    if (a.month !== b.month) return a.month - b.month;
    return a.day - b.day;
  });
  fs.writeFileSync(dayBlocksFile, `${JSON.stringify(dayBlocks, null, 2)}\n`);

  console.log(
    `PASS: generated ${blockTimesFile} (${maxBlock + 1} entries) and ${dayBlocksFile} (${dayBlocks.length} day entries)`
  );
}

main().catch((err) => {
  console.error(`FAIL: ${err.message}`);
  process.exit(1);
});
EOF
}

generate_block_node_metadata_from_mirror() {
  local max_block="$1"
  write_mirror_metadata_generator

  export MIRROR_BLOCK_NUMBER="${max_block}"
  export BLOCK_TIMES_FILE
  export DAY_BLOCKS_FILE
  export MIRROR_REST_URL

  if ! node "${MIRROR_METADATA_SCRIPT}" >/dev/null; then
    echo "Mirror metadata generation failed (stderr shown above)" >&2
    return 1
  fi
}

prepare_wrap_day_archives_from_record_streams() {
  local account_dir account_id src base ts day
  local out_dir out_file stem stem_no_ext
  local primary_records=0
  local other_records=0
  local sig_files=0
  local tar_count=0

  rm -rf "${WRAP_DAYS_SRC_DIR}" "${WRAP_COMPRESSED_DAYS_DIR}" >/dev/null 2>&1 || true
  mkdir -p "${WRAP_DAYS_SRC_DIR}" "${WRAP_COMPRESSED_DAYS_DIR}"

  shopt -s nullglob
  for account_dir in "${RECORD_STREAMS_DIR}"/record0.0.*; do
    [[ -d "${account_dir}" ]] || continue
    account_id="${account_dir##*/record}"
    for src in "${account_dir}"/*; do
      [[ -f "${src}" ]] || continue
      base="$(basename "${src}")"
      [[ "${base}" == *Z* ]] || continue
      ts="${base%%Z*}Z"
      day="${ts%%T*}"
      out_dir="${WRAP_DAYS_SRC_DIR}/${day}/${ts}"
      mkdir -p "${out_dir}"

      case "${base}" in
        *.rcd.gz)
          stem="${base%.gz}"
          stem_no_ext="${stem%.rcd}"
          if [[ "${stem_no_ext}" == "${ts}" && "${account_id}" == "0.0.3" && ! -f "${out_dir}/${ts}.rcd" ]]; then
            gzip -dc "${src}" > "${out_dir}/${ts}.rcd"
            primary_records=$((primary_records + 1))
          else
            out_file="${out_dir}/${stem_no_ext}_node_${account_id}.rcd"
            gzip -dc "${src}" > "${out_file}"
            other_records=$((other_records + 1))
          fi
          ;;
        *.rcd)
          stem_no_ext="${base%.rcd}"
          if [[ "${stem_no_ext}" == "${ts}" && "${account_id}" == "0.0.3" && ! -f "${out_dir}/${ts}.rcd" ]]; then
            cp -f "${src}" "${out_dir}/${ts}.rcd"
            primary_records=$((primary_records + 1))
          else
            cp -f "${src}" "${out_dir}/${stem_no_ext}_node_${account_id}.rcd"
            other_records=$((other_records + 1))
          fi
          ;;
        *.rcd_sig)
          stem_no_ext="${base%.rcd_sig}"
          cp -f "${src}" "${out_dir}/${stem_no_ext}_node_${account_id}.rcd_sig"
          sig_files=$((sig_files + 1))
          ;;
        *.rcs_sig)
          stem_no_ext="${base%.rcs_sig}"
          cp -f "${src}" "${out_dir}/${stem_no_ext}_node_${account_id}.rcs_sig"
          sig_files=$((sig_files + 1))
          ;;
      esac
    done
  done
  shopt -u nullglob

  if (( primary_records == 0 )); then
    echo "No primary record files prepared for wrap input under ${WRAP_DAYS_SRC_DIR}" >&2
    return 1
  fi
  if (( sig_files == 0 )); then
    echo "No signature files prepared for wrap input under ${WRAP_DAYS_SRC_DIR}" >&2
    return 1
  fi

  if ! run_step "Building wrap input archives (gradle :tools:run days compress)" \
      run_block_node_tools "days compress -o \"${WRAP_COMPRESSED_DAYS_DIR}\" \"${WRAP_DAYS_SRC_DIR}\""; then
    echo "Failed to build .tar.zstd wrap input archives" >&2
    return 1
  fi

  tar_count="$(find "${WRAP_COMPRESSED_DAYS_DIR}" -type f -name '*.tar.zstd' | wc -l | tr -d ' ')"
  if [[ "${tar_count}" == "0" ]]; then
    echo "days compress produced no .tar.zstd files under ${WRAP_COMPRESSED_DAYS_DIR}" >&2
    return 1
  fi
}

run_block_node_wrap_tool() {
  local records_dir="$1"
  local wrapped_dir="$2"
  local wrap_args jumpstart_file

  if [[ "${USE_BLOCK_NODE_JUMPSTART}" != "true" ]]; then
    log "USE_BLOCK_NODE_JUMPSTART=false; skipping Block Node wrap tool and using configured jumpstart env values"
    return 0
  fi

  if ! validate_block_node_repo; then
    return 1
  fi
  if [[ ! -d "${records_dir}" ]]; then
    echo "recordStreams directory not found: ${records_dir}" >&2
    return 1
  fi
  if ! ensure_zstd_command_for_block_node; then
    echo "Failed to provide a working zstd command for Block Node wrapping." >&2
    return 1
  fi

  mkdir -p "${wrapped_dir}"
  wrap_args="blocks wrap -i \"${records_dir}\" -o \"${wrapped_dir}\" --blocktimes-file \"${BLOCK_TIMES_FILE}\" --day-blocks \"${DAY_BLOCKS_FILE}\""
  if [[ -n "${BLOCKS_WRAP_EXTRA_ARGS}" ]]; then
    wrap_args="${wrap_args} ${BLOCKS_WRAP_EXTRA_ARGS}"
  fi

  if ! run_step "Running block-node wrap tool (gradle :tools:run blocks wrap)" \
      run_block_node_tools "${wrap_args}"; then
    echo "Block Node wrap command failed" >&2
    return 1
  fi

  if [[ -n "${JUMPSTART_BIN_PATH}" ]]; then
    jumpstart_file="${JUMPSTART_BIN_PATH}"
  else
    jumpstart_file="$(find "${wrapped_dir}" -type f -name "jumpstart.bin" | head -n 1)"
  fi
  if [[ -z "${jumpstart_file}" || ! -f "${jumpstart_file}" ]]; then
    echo "jumpstart.bin not found under ${wrapped_dir}. Override with JUMPSTART_BIN_PATH." >&2
    return 1
  fi

  export JUMPSTART_BIN_PATH="${jumpstart_file}"
}

load_jumpstart_env_from_bin() {
  local jumpstart_file="$1"
  local k v parsed

  [[ -f "${jumpstart_file}" ]] || { echo "jumpstart.bin not found: ${jumpstart_file}" >&2; return 1; }
  write_jumpstart_parser

  parsed="$(node "${JUMPSTART_PARSE_SCRIPT}" "${jumpstart_file}")" || return 1
  while IFS='=' read -r k v; do
    case "${k}" in
      JUMPSTART_BLOCK_NUMBER) JUMPSTART_BLOCK_NUMBER="${v}" ;;
      JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH) JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH="${v}" ;;
      JUMPSTART_CONSENSUS_TIMESTAMP_HASH) JUMPSTART_CONSENSUS_TIMESTAMP_HASH="${v}" ;;
      JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH) JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH="${v}" ;;
      JUMPSTART_STREAMING_HASHER_LEAF_COUNT) JUMPSTART_STREAMING_HASHER_LEAF_COUNT="${v}" ;;
      JUMPSTART_STREAMING_HASHER_HASH_COUNT) JUMPSTART_STREAMING_HASHER_HASH_COUNT="${v}" ;;
      JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES) JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES="${v}" ;;
    esac
  done <<< "${parsed}"

  [[ -n "${JUMPSTART_BLOCK_NUMBER}" ]] || { echo "Failed to parse JUMPSTART_BLOCK_NUMBER from ${jumpstart_file}" >&2; return 1; }
  [[ -n "${JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH}" ]] || { echo "Failed to parse previous hash from ${jumpstart_file}" >&2; return 1; }
  [[ -n "${JUMPSTART_STREAMING_HASHER_LEAF_COUNT}" ]] || { echo "Failed to parse leaf count from ${jumpstart_file}" >&2; return 1; }
  [[ -n "${JUMPSTART_STREAMING_HASHER_HASH_COUNT}" ]] || { echo "Failed to parse hash count from ${jumpstart_file}" >&2; return 1; }
  [[ -n "${JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES}" || "${JUMPSTART_STREAMING_HASHER_HASH_COUNT}" == "0" ]] || {
    echo "Failed to parse subtree hashes from ${jumpstart_file}" >&2
    return 1
  }

  export JUMPSTART_CONSENSUS_TIMESTAMP_HASH
  export JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH
  export JUMPSTART_BLOCK_NUMBER
  export JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH
  export JUMPSTART_STREAMING_HASHER_LEAF_COUNT
  export JUMPSTART_STREAMING_HASHER_HASH_COUNT
  export JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES

}

# Migration vote values parsed from hgcaa.log on the consensus pods (Step 7 validation).
MIGRATION_BLOCK_NUMBER=""
MIGRATION_PREV_HASH=""
MIGRATION_INTERMEDIATE_HASHES=""
MIGRATION_LEAF_COUNT=""

normalize_hash_list() {
  local input="$1"
  echo "${input}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]' | sed 's/^,//; s/,$//; s/,,*/,/g'
}

parse_migration_vote_from_hgcaa() {
  local node pod line="" queued_line="" vote_pod=""
  local attempt=1 max_attempts=36
  local nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"

  while (( attempt <= max_attempts )); do
    for node in "${nodes[@]}"; do
      pod="network-${node}-0"
      line="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
        "awk '/Finalized migration root hash vote values:/{last=\$0} END{if (last) print last}' /opt/hgcapp/services-hedera/HapiApp2.0/output/hgcaa.log 2>/dev/null" || true)"
      if [[ -n "${line}" ]]; then
        vote_pod="${pod}"
        break
      fi
    done
    if [[ -n "${line}" ]]; then
      break
    fi
    sleep 5
    ((attempt++))
  done

  [[ -n "${line}" ]] || {
    echo "Could not find migration vote finalization log line in hgcaa.log within $((max_attempts * 5))s" >&2
    return 1
  }

  if [[ "${line}" =~ Block[[:space:]]+([0-9]+)[[:space:]]+previousWrappedRecordBlockRootHash=([0-9a-fA-F]+),[[:space:]]*wrappedIntermediatePreviousBlockRootHashes=\[([^]]*)\],[[:space:]]*wrappedIntermediateBlockRootsLeafCount=([0-9]+) ]]; then
    MIGRATION_BLOCK_NUMBER="${BASH_REMATCH[1]}"
    MIGRATION_PREV_HASH="${BASH_REMATCH[2]}"
    MIGRATION_INTERMEDIATE_HASHES="${BASH_REMATCH[3]}"
    MIGRATION_LEAF_COUNT="${BASH_REMATCH[4]}"
  elif [[ "${line}" =~ previousWrappedRecordBlockRootHash=([0-9a-fA-F]+),[[:space:]]*wrappedIntermediatePreviousBlockRootHashes=\[([^]]*)\],[[:space:]]*wrappedIntermediateBlockRootsLeafCount=([0-9]+) ]]; then
    MIGRATION_PREV_HASH="${BASH_REMATCH[1]}"
    MIGRATION_INTERMEDIATE_HASHES="${BASH_REMATCH[2]}"
    MIGRATION_LEAF_COUNT="${BASH_REMATCH[3]}"
    queued_line="$(kubectl -n "${SOLO_NAMESPACE}" exec "${vote_pod}" -c root-container -- sh -lc \
      "awk '/Applied queued hash for block[0-9]+:/{last=\$0} END{if (last) print last}' /opt/hgcapp/services-hedera/HapiApp2.0/output/hgcaa.log 2>/dev/null" || true)"
    if [[ "${queued_line}" =~ block([0-9]+): ]]; then
      MIGRATION_BLOCK_NUMBER="${BASH_REMATCH[1]}"
    else
      MIGRATION_BLOCK_NUMBER="${JUMPSTART_BLOCK_NUMBER}"
    fi
  else
    echo "Migration vote line did not match expected format: ${line}" >&2
    return 1
  fi
  MIGRATION_PREV_HASH="$(echo "${MIGRATION_PREV_HASH}" | tr '[:upper:]' '[:lower:]')"
  MIGRATION_INTERMEDIATE_HASHES="$(normalize_hash_list "${MIGRATION_INTERMEDIATE_HASHES}")"
  echo "Parsed migration vote values: block=${MIGRATION_BLOCK_NUMBER}, leafCount=${MIGRATION_LEAF_COUNT}"
}

# Re-run the offline wrap from records 0..to_block and load the resulting
# jumpstart.bin into JUMPSTART_* env (overwriting the Step-5 input values).
run_replay_wrap_to_migration() {
  local mirror_base="$1"
  local to_block="$2"
  local prev_bin_path="${JUMPSTART_BIN_PATH}"

  rm -rf "${RECORD_STREAMS_DIR}" "${REPLAY_WRAPPED_BLOCKS_DIR}" >/dev/null 2>&1 || true
  mkdir -p "${RECORD_STREAMS_DIR}" "${REPLAY_WRAPPED_BLOCKS_DIR}"

  download_solo_minio_record_streams "${to_block}" "${mirror_base}" || return 1
  generate_block_node_metadata_from_mirror "${to_block}" || return 1
  prepare_wrap_day_archives_from_record_streams || return 1

  # Force wrap tool to re-discover jumpstart.bin under the replay output directory.
  JUMPSTART_BIN_PATH=""
  if ! run_block_node_wrap_tool "${WRAP_COMPRESSED_DAYS_DIR}" "${REPLAY_WRAPPED_BLOCKS_DIR}"; then
    export JUMPSTART_BIN_PATH="${prev_bin_path}"
    return 1
  fi
  load_jumpstart_env_from_bin "${JUMPSTART_BIN_PATH}"
}

compare_replay_to_migration_vote() {
  local replay_prev replay_leaf replay_hashes
  local mismatch=0
  replay_prev="$(echo "${JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH}" | tr '[:upper:]' '[:lower:]')"
  replay_leaf="${JUMPSTART_STREAMING_HASHER_LEAF_COUNT}"
  replay_hashes="$(normalize_hash_list "${JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES}")"

  mkdir -p "$(dirname "${MIGRATION_COMPARE_LOG}")"
  {
    echo "migration.block=${MIGRATION_BLOCK_NUMBER}"
    echo "migration.prevHash=${MIGRATION_PREV_HASH}"
    echo "migration.intermediateHashes=${MIGRATION_INTERMEDIATE_HASHES}"
    echo "migration.leafCount=${MIGRATION_LEAF_COUNT}"
    echo "replay.block=${JUMPSTART_BLOCK_NUMBER}"
    echo "replay.prevHash=${replay_prev}"
    echo "replay.intermediateHashes=${replay_hashes}"
    echo "replay.leafCount=${replay_leaf}"
  } > "${MIGRATION_COMPARE_LOG}"

  echo "--------------------------------------------------------------------"
  echo "Migration vs Replay Comparison"
  echo "  blockNumber:"
  echo "    migration = ${MIGRATION_BLOCK_NUMBER}"
  echo "    replay    = ${JUMPSTART_BLOCK_NUMBER}"
  echo "  previousWrappedRecordBlockRootHash:"
  echo "    migration = ${MIGRATION_PREV_HASH}"
  echo "    replay    = ${replay_prev}"
  echo "  wrappedIntermediateBlockRootsLeafCount:"
  echo "    migration = ${MIGRATION_LEAF_COUNT}"
  echo "    replay    = ${replay_leaf}"
  echo "  wrappedIntermediatePreviousBlockRootHashes:"
  echo "    migration = [${MIGRATION_INTERMEDIATE_HASHES}]"
  echo "    replay    = [${replay_hashes}]"

  if [[ "${MIGRATION_PREV_HASH}" != "${replay_prev}" ]]; then
    mismatch=1
    echo "  mismatch: previousWrappedRecordBlockRootHash differs"
  fi
  if [[ "${MIGRATION_INTERMEDIATE_HASHES}" != "${replay_hashes}" ]]; then
    mismatch=1
    echo "  mismatch: wrappedIntermediatePreviousBlockRootHashes differ"
  fi
  if [[ "${MIGRATION_LEAF_COUNT}" != "${replay_leaf}" ]]; then
    mismatch=1
    echo "  mismatch: wrappedIntermediateBlockRootsLeafCount differs"
  fi

  if (( mismatch == 0 )); then
    echo "  result: MATCH"
  else
    echo "  result: MISMATCH"
  fi
  echo "--------------------------------------------------------------------"
  echo "Comparison log: ${MIGRATION_COMPARE_LOG}"

  (( mismatch == 0 ))
}


run_block_node_tools() {
  (cd "${BLOCK_NODE_REPO_PATH}" && ./gradlew :tools:run --args="$1")
}
