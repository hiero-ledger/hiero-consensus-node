#!/usr/bin/env bash
set -euo pipefail

# ============================================================
#  Solo Local Deployment Script (Idempotent)
#  Deploys: consensus node + block node + mirror node
#  Safe to re-run — each step checks whether it already
#  completed and skips forward if so.
# ============================================================

# ---------- Configuration — edit these before running -------
CLUSTER_NAME="solo-cluster"
NAMESPACE="solo-ns"
DEPLOYMENT="solo-deployment"
NODE_ALIASES="node1"
RELEASE_TAG="v0.77.0"
LOCAL_BUILD_PATH="./hiero-consensus-node/hedera-node/data/"            # leave empty for standard release
CUSTOM_PROPERTY_FILE="./hiero-consensus-node/hedera-node/hedera-app/data/config/custom-application.properties"
CUSTOM_SETTINGS_FILE="./hiero-consensus-node/hedera-node/configuration/mainnet/settings.txt"
# ------------------------------------------------------------

CLUSTER_REF="kind-${CLUSTER_NAME}"
CONTEXT="kind-${CLUSTER_NAME}"
FIRST_BLOCK="103609000"

echo ""
echo "============================================================"
echo " Solo Local Deployment (idempotent)"
echo " Cluster   : ${CLUSTER_NAME}"
echo " Namespace : ${NAMESPACE}"
echo " Deployment: ${DEPLOYMENT}"
echo " Nodes     : ${NODE_ALIASES}"
echo " Version   : ${RELEASE_TAG}"
echo " Local Build: ${LOCAL_BUILD_PATH:-<not set>}"
echo "============================================================"
echo ""

# --- Validate local build path if set -----------------------
if [[ -n "${LOCAL_BUILD_PATH}" ]]; then
  if [[ ! -d "${LOCAL_BUILD_PATH}/apps" ]]; then
    echo "ERROR: LOCAL_BUILD_PATH does not contain an 'apps' subdirectory."
    echo "       Expected: ${LOCAL_BUILD_PATH}/apps"
    exit 1
  fi
  if [[ ! -d "${LOCAL_BUILD_PATH}/lib" ]]; then
    echo "ERROR: LOCAL_BUILD_PATH does not contain a 'lib' subdirectory."
    echo "       Expected: ${LOCAL_BUILD_PATH}/lib"
    exit 1
  fi
  echo "Local build path validated: ${LOCAL_BUILD_PATH}"
  echo ""
fi

# --- 1. Solo init -------------------------------------------
echo "[1/13] Initialising Solo..."
solo init || true
# solo init is deprecated and a no-op if already done

# --- 2. Create Kind cluster ----------------------------------
echo ""
echo "[2/13] Creating Kind cluster '${CLUSTER_NAME}'..."
if kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
  echo "SKIP: Kind cluster '${CLUSTER_NAME}' already exists."
else
  kind create cluster -n "${CLUSTER_NAME}"
fi

# --- 3. Set kubectl context ----------------------------------
echo ""
echo "[3/13] Setting kubectl context..."
kubectl config set-context "${CONTEXT}"

# Give the control plane a moment to stabilise
sleep 10

# --- 4. Connect cluster reference ----------------------------
echo ""
echo "[4/13] Connecting cluster reference..."
solo cluster-ref config connect --cluster-ref "${CLUSTER_REF}" --context "${CONTEXT}"

# --- 5. Set up cluster reference -----------------------------
echo ""
echo "[5/13] Setting up cluster reference..."
solo cluster-ref config setup --cluster-ref "${CLUSTER_REF}"

# --- 6. Create deployment ------------------------------------
echo ""
echo "[6/13] Creating deployment..."
solo deployment config create --deployment "${DEPLOYMENT}" --namespace "${NAMESPACE}" || echo "SKIP: Deployment already exists, continuing..."

# --- 7. Attach cluster to deployment -------------------------
echo ""
echo "[7/13] Attaching cluster to deployment..."
if kubectl get namespace "${NAMESPACE}" --no-headers 2>/dev/null | grep -q .; then
  echo "SKIP: Cluster already attached to deployment (namespace ${NAMESPACE} exists)."
else
  solo deployment cluster attach \
    --deployment "${DEPLOYMENT}" \
    --cluster-ref "${CLUSTER_REF}" \
    --num-consensus-nodes 1
fi

# --- 8. Add block node (MUST precede network deploy) ---------
echo ""
echo "[8/13] Adding block node (must happen before network deploy)..."
if kubectl get statefulset -n "${NAMESPACE}" -l "app.kubernetes.io/name=block-node" --no-headers 2>/dev/null | grep -q .; then
  echo "SKIP: Block node already exists in namespace ${NAMESPACE}."
else
  cat > block-node-values.yaml << EOF
blockNode:
  config:
    BLOCK_NODE_EARLIEST_MANAGED_BLOCK: "${FIRST_BLOCK}"
EOF

  solo block node add \
    --deployment "${DEPLOYMENT}" \
    --cluster-ref "${CLUSTER_REF}" \
    --values-file block-node-values.yaml
fi
# --- 9. Generate consensus keys ------------------------------
echo ""
echo "[9/13] Generating consensus keys..."
solo keys consensus generate \
  --gossip-keys --tls-keys \
  --deployment "${DEPLOYMENT}" \
  --node-aliases "${NODE_ALIASES}"

# --- 10. Deploy consensus network ----------------------------
echo ""
echo "[10/13] Deploying consensus network at ${RELEASE_TAG}..."
if kubectl get pods -n "${NAMESPACE}" -l "solo.hedera.com/type=network-node" --no-headers 2>/dev/null | grep -q .; then
  echo "SKIP: Consensus network already deployed in namespace ${NAMESPACE}."
else
  solo consensus network deploy \
    --deployment "${DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --release-tag "${RELEASE_TAG}" \
    --pvcs true \
    --application-properties "${CUSTOM_PROPERTY_FILE}" \
    --settings-txt  "${CUSTOM_SETTINGS_FILE}"
fi

# --- 11. Set up consensus node --------------------------------
echo ""
echo "[11/13] Setting up consensus node..."
if [[ -n "${LOCAL_BUILD_PATH}" ]]; then
  echo "Using local build from ${LOCAL_BUILD_PATH}"
  solo consensus node setup \
    --deployment "${DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --release-tag "${RELEASE_TAG}" \
    --local-build-path "${LOCAL_BUILD_PATH}"
else
  solo consensus node setup \
    --deployment "${DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --release-tag "${RELEASE_TAG}"
fi

# --- 11.5. Fix application.properties overrides --------------
echo ""
echo "[11.5/13] Applying custom property overrides..."
kubectl exec -n solo-ns network-node1-0 -c root-container -- \
  sed -i '/^tss\.forceMockSignatures=/d' \
  /opt/hgcapp/services-hedera/HapiApp2.0/data/config/application.properties

kubectl exec -n solo-ns network-node1-0 -c root-container -- \
  bash -c 'echo "tss.forceMockSignatures=true" >> /opt/hgcapp/services-hedera/HapiApp2.0/data/config/application.properties'


# --- 12. Start consensus node --------------------------------
echo ""
echo "[12/13] Starting consensus node..."
solo consensus node start --deployment "${DEPLOYMENT}" --state-file "./snapshot.zip"

# --- 13. Add mirror node (before node start) ------------------
echo ""
echo "[13/13] Adding mirror node..."
if kubectl get pods -n "${NAMESPACE}" -l "app.kubernetes.io/name=importer" --no-headers 2>/dev/null | grep -q .; then
  echo "SKIP: Mirror node already exists in namespace ${NAMESPACE}."
else
  solo mirror node add \
    --deployment "${DEPLOYMENT}" \
    --cluster-ref "${CLUSTER_REF}" \
    --enable-ingress -q
fi

echo ""
echo "============================================================"
echo " Deployment complete!"
echo "============================================================"
echo ""
echo "To tear everything down, run:"
echo "  solo mirror node destroy --deployment ${DEPLOYMENT} --force -q"
echo "  solo block node destroy  --deployment ${DEPLOYMENT} --cluster-ref ${CLUSTER_REF}"
echo "  solo consensus network destroy --deployment ${DEPLOYMENT} --delete-pvcs --delete-secrets --force -q"
echo "  kind delete cluster -n ${CLUSTER_NAME}"
echo ""
