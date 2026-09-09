#!/usr/bin/env bash
set -uo pipefail

# ============================================================
#  Solo Consensus Node Cleanup Script
#  Tears down: mirror node, block node, consensus network,
#  and optionally the Kind cluster.
#
#  Safe to run even if some components are already gone —
#  each step checks before acting.
# ============================================================

# ---------- Configuration — must match deploy script --------
CLUSTER_NAME="solo-cluster"
NAMESPACE="solo-ns"
DEPLOYMENT="solo-deployment"
NODE_ALIASES="node1"
# ------------------------------------------------------------

CLUSTER_REF="kind-${CLUSTER_NAME}"

echo ""
echo "============================================================"
echo " Solo Cleanup"
echo " Cluster   : ${CLUSTER_NAME}"
echo " Namespace : ${NAMESPACE}"
echo " Deployment: ${DEPLOYMENT}"
echo " Nodes     : ${NODE_ALIASES}"
echo "============================================================"
echo ""

# --- 1. Stop consensus node ---------------------------------
echo "[1/6] Stopping consensus node..."
if solo consensus node stop --deployment "${DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" -q 2>/dev/null; then
  echo "Done."
else
  echo "SKIP: Node already stopped or not found."
fi

# --- 2. Destroy mirror node ---------------------------------
echo ""
echo "[2/6] Destroying mirror node..."
if kubectl get pods -n "${NAMESPACE}" -l "app.kubernetes.io/name=importer" --no-headers 2>/dev/null | grep -q .; then
  solo mirror node destroy --deployment "${DEPLOYMENT}" --force -q || echo "WARNING: mirror node destroy reported an error, continuing..."
else
  echo "SKIP: No mirror node found."
fi

# --- 3. Destroy block node ----------------------------------
echo ""
echo "[3/6] Destroying block node..."
if kubectl get statefulset -n "${NAMESPACE}" -l "app.kubernetes.io/name=block-node" --no-headers 2>/dev/null | grep -q .; then
  solo block node destroy --deployment "${DEPLOYMENT}" --cluster-ref "${CLUSTER_REF}" || echo "WARNING: block node destroy reported an error, continuing..."
else
  echo "SKIP: No block node found."
fi

# --- 4. Destroy consensus network ----------------------------
echo ""
echo "[4/6] Destroying consensus network..."
if kubectl get pods -n "${NAMESPACE}" -l "solo.hedera.com/type=network-node" --no-headers 2>/dev/null | grep -q .; then
  solo consensus network destroy --deployment "${DEPLOYMENT}" --delete-pvcs --delete-secrets --force -q || echo "WARNING: consensus network destroy reported an error, continuing..."
else
  echo "SKIP: No consensus network found."
fi

# --- 5. Delete deployment config -----------------------------
echo ""
echo "[5/6] Deleting deployment config..."
if solo deployment config delete --deployment "${DEPLOYMENT}" -q 2>/dev/null; then
  echo "Done."
else
  echo "SKIP: Deployment config already removed or not found."
fi

# --- 6. Delete Kind cluster ----------------------------------
echo ""
echo "[6/6] Deleting Kind cluster '${CLUSTER_NAME}'..."
if kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
  kind delete cluster -n "${CLUSTER_NAME}" || echo "WARNING: kind delete cluster reported an error."
else
  echo "SKIP: Kind cluster '${CLUSTER_NAME}' not found."
fi

echo ""
echo "============================================================"
echo " Cleanup complete."
echo "============================================================"
echo ""
