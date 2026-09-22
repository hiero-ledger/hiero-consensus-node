# SPDX-License-Identifier: Apache-2.0
#
# Shared helpers for seeding the Block Node's WRB (Wrapped Record Block) RSA bootstrap roster.
# Source it from a scenario script (its dir is a sibling of this file's parent `solo/` dir):
#
#   source "${SCRIPT_DIR}/../block-node-rsa-roster.sh"
#
# Background: a Block Node that verifies WRBs needs the consensus-node address book
# (node_id -> RSA public key), loaded at startup by its RsaRosterBootstrapPlugin. With no local
# bootstrap file AND no reachable Mirror Node fallback the plugin loads nothing, the address book
# stays empty, and the verifier rejects every block with BAD_BLOCK_PROOF. These helpers generate the
# roster from the running network's gossip certs and bake it into the BN pod via a
# `solo block node add --values-file` seed init container (the file-first load path).
#
# The caller must set these before invoking the helpers, and provide a `require_cmd` function:
#   NODE_ALIASES                                  e.g. "node1,node2,node3"
#   SOLO_NAMESPACE                                k8s namespace the consensus nodes run in
#   RSA_BOOTSTRAP_ROSTER_FILE                      local path to write the generated roster JSON
#   BLOCK_NODE_RSA_VALUES_FILE                     local path to write the BN helm values file
#   ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL      Mirror Node fallback URL (blank to disable)
#   ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS / _READ_TIMEOUT_SECONDS / _PAGE_SIZE

# Builds the RSA bootstrap roster file (PBJ JSON of NodeAddressBook) by extracting the X.509
# SubjectPublicKeyInfo from each consensus node's gossip cert (s-public-node{N}.pem) and hex-encoding
# the DER bytes. Format consumed by RsaKeyDecoder.buildKeyMap: rsaPubKey = hex of DER X.509 SPKI (no
# 0x prefix). JSON shape: { "nodeAddress": [ {"RSAPubKey": "..."}, {"nodeId": "1", "RSAPubKey": "..."} ] }
# (nodeId is a STRING and is omitted when 0, the proto default).
generate_rsa_bootstrap_roster_json() {
  require_cmd openssl
  local node node_idx node_id pem hex
  local nodes=()
  local cn_pod="network-node1-0"
  local entries=""

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    node_idx="${node#node}"             # node3 -> 3 (PEM filename suffix is 1-based)
    node_id="$((node_idx - 1))"         # JSON nodeId is 0-based
    pem="$(kubectl -n "${SOLO_NAMESPACE}" exec "${cn_pod}" -c root-container -- \
      cat "/opt/hgcapp/services-hedera/HapiApp2.0/data/keys/s-public-node${node_idx}.pem" 2>/dev/null || true)"
    if [[ -z "${pem}" ]]; then
      echo "Failed to read s-public-node${node_idx}.pem from ${cn_pod}" >&2
      return 1
    fi
    # od -An -v -tx1 | tr -d ' \n' is the dependency-free equivalent of `xxd -p` (hex of the DER bytes).
    hex="$(printf '%s' "${pem}" \
      | openssl x509 -pubkey -noout 2>/dev/null \
      | openssl pkey -pubin -outform DER 2>/dev/null \
      | od -An -v -tx1 | tr -d ' \n')"
    if [[ -z "${hex}" ]]; then
      echo "Failed to extract X.509 SPKI hex for node${node_idx}" >&2
      return 1
    fi
    [[ -n "${entries}" ]] && entries+=","
    if [[ "${node_id}" == "0" ]]; then
      entries+=$'\n    {"RSAPubKey": "'"${hex}"'"}'
    else
      entries+=$'\n    {"nodeId": "'"${node_id}"'", "RSAPubKey": "'"${hex}"'"}'
    fi
  done

  cat > "${RSA_BOOTSTRAP_ROSTER_FILE}" <<EOF
{
  "nodeAddress": [${entries}
  ]
}
EOF
  echo "Generated RSA bootstrap roster (${#nodes[@]} entries): ${RSA_BOOTSTRAP_ROSTER_FILE}"
}

# Writes the BN helm values file: relocates the RSA bootstrap file onto the live-data PVC subpath and
# adds a `seed-rsa-bootstrap-roster` init container that bakes in the generated roster before
# BlockNodeApp.loadApplicationState() reads it at startup. The chart-default `init-storage-dirs`
# container is repeated verbatim because Helm REPLACES lists on values merge (omitting it would leave
# the BN's PVC subpaths uncreated). Any text passed as $1 is injected verbatim under
# `blockNode.config:` (4-space indent), letting a caller add scenario-specific keys
# (e.g. the cutover scenario's BLOCK_NODE_EARLIEST_MANAGED_BLOCK / BACKFILL_START_BLOCK).
write_block_node_rsa_values() {
  local extra_config="${1:-}"
  local roster_indented
  roster_indented="$(sed 's/^/          /' "${RSA_BOOTSTRAP_ROSTER_FILE}")"

  {
    echo "blockNode:"
    echo "  config:"
    [[ -n "${extra_config}" ]] && printf '%s\n' "${extra_config}"
    cat <<EOF
    APP_STATE_RSA_BOOTSTRAP_FILE_PATH: "/opt/hiero/block-node/data/live/rsa-bootstrap-roster.json"
    ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL: "${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL}"
    ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS: "${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS}"
    ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS: "${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS}"
    ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE: "${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE}"
  initContainers:
    - name: init-storage-dirs
      image: busybox
      command:
        - sh
        - -c
        - |
          mkdir -p /live-pvc/live-data && \\
          chown 2000:2000 /live-pvc/live-data && \\
          chmod 700 /live-pvc/live-data && \\
          mkdir -p /archive-pvc/archive-data && \\
          chown 2000:2000 /archive-pvc/archive-data && \\
          chmod 700 /archive-pvc/archive-data && \\
          chown 2000:2000 /verification-pvc && \\
          chmod 700 /verification-pvc
      volumeMounts:
        - name: live-storage
          mountPath: /live-pvc
        - name: archive-storage
          mountPath: /archive-pvc
        - name: verification-storage
          mountPath: /verification-pvc
    - name: seed-rsa-bootstrap-roster
      image: busybox
      command:
        - sh
        - -c
        - |
          cat > /live-pvc/live-data/rsa-bootstrap-roster.json <<'ROSTER'
${roster_indented}
          ROSTER
          chown 2000:2000 /live-pvc/live-data/rsa-bootstrap-roster.json
          chmod 644 /live-pvc/live-data/rsa-bootstrap-roster.json
          echo "Seeded rsa-bootstrap-roster.json:"
          ls -la /live-pvc/live-data/rsa-bootstrap-roster.json
      volumeMounts:
        - name: live-storage
          mountPath: /live-pvc
EOF
  } > "${BLOCK_NODE_RSA_VALUES_FILE}"
}
