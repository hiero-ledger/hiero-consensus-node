# CLPR Multi-Network SOLO Deployment Guide

This document is a reference guide for deploying multiple independent Hiero networks using SOLO within a
single Kubernetes cluster for CLPR cross-ledger testing. It explains the process step by step, highlights
key differences from standard single-network SOLO use in CITR, and serves as a reference for both
AI-assisted test development and for the SOLO maintainers who may need to advise on or adapt the process.

This guide is derived from a working multi-network deployment used during CLPR prototype development. The
prototype's CLPR-specific application logic is not relevant here — this document focuses on the SOLO
deployment patterns and Kubernetes infrastructure that are reusable for any multi-network CLPR testing.

---

# 1. Overview: What Is Different from Standard CITR

Standard CITR deploys one Hiero network per test run: one namespace, one deployment, one set of consensus
nodes, one mirror node, one relay. All CITR test suites (MATS through MQPT) assume this single-network
model.

CLPR testing requires two or more independent Hiero networks that can communicate via their CLPR endpoint
modules. This means:

- **Two SOLO deployments** in the same Kubernetes cluster, each in its own namespace.
- **Separate CLPR configuration** per network (different chain IDs, different application properties).
- **Cross-namespace networking** so CLPR endpoints on one network can reach endpoints on the other.
- **Separate port-forward mappings** so the test driver can interact with both networks from outside
  the cluster without port collisions.
- **Parallel deployment** to keep setup times manageable (deploying two networks serially roughly doubles
  the setup time).

The cluster-level infrastructure (kind cluster, cert-manager, MinIO) is shared between the two deployments.
Only the per-deployment resources (consensus nodes, block nodes, mirror nodes, relays) are duplicated.

---

# 2. Architecture

```
┌─────────────────────────────── Kubernetes Cluster ───────────────────────────────┐
│                                                                                   │
│  ┌─── Namespace: clpr-src ───────────┐   ┌─── Namespace: clpr-dst ───────────┐  │
│  │ Consensus Nodes (node1..nodeN)    │   │ Consensus Nodes (node1..nodeN)    │  │
│  │ Block Node                        │   │ Block Node                        │  │
│  │ Mirror Node (importer, REST, DB)  │   │ Mirror Node (importer, REST, DB)  │  │
│  │ JSON-RPC Relay                    │   │ JSON-RPC Relay                    │  │
│  │ Explorer                          │   │ Explorer                          │  │
│  │                                   │   │                                   │  │
│  │ CLPR Config:                      │   │ CLPR Config:                      │  │
│  │   chain_id = hiero:src            │   │   chain_id = hiero:dst            │  │
│  │   clpr.enabled = true             │   │   clpr.enabled = true             │  │
│  └───────────────────────────────────┘   └───────────────────────────────────┘  │
│                                                                                   │
│  ┌─── Namespace: solo-setup ─────────┐                                           │
│  │ Shared: cert-manager, MinIO       │                                           │
│  └───────────────────────────────────┘                                           │
│                                                                                   │
└───────────────────────────────────────────────────────────────────────────────────┘

         │                                            │
    Port Forwards                                Port Forwards
    (localhost)                                  (localhost)
         │                                            │
    gRPC:50261                                   gRPC:60061
    Mirror:5551                                  Mirror:5552
    Relay:7546                                   Relay:7547
    Explorer:9001                                Explorer:9002
```

---

# 3. Prerequisites

- **SOLO CLI** installed and on PATH (`solo --version`).
- **kubectl** configured with access to a Kubernetes cluster (kind, GKE, or Latitude).
- **Hiero consensus node build artifacts** available at a local path (for `--local-build-path`).
- **CLPR application properties files** — one per network, containing CLPR-specific configuration
  (chain ID, enabled flags, system contract settings).

---

# 4. Step-by-Step Deployment

## 4.1 Environment Configuration

Define separate identities for each network. The key principle: everything that is per-deployment gets
a SRC and DST variant. Everything that is per-cluster is shared.

**Shared (one per cluster):**

| Variable | Example | Purpose |
|---|---|---|
| `SOLO_CLUSTER_CONTEXT` | `kind-solo` | kubectl context |
| `SOLO_CLUSTER_REF` | `solo-shared` | SOLO's logical cluster reference |
| `SOLO_CLUSTER_SETUP_NAMESPACE` | `solo-setup` | Namespace for shared infra (cert-manager, MinIO) |

**Per-deployment:**

| Variable | SRC Value | DST Value | Purpose |
|---|---|---|---|
| `SOLO_SRC_DEPLOYMENT` | `clpr-src` | — | SOLO deployment name |
| `SOLO_DST_DEPLOYMENT` | — | `clpr-dst` | SOLO deployment name |
| `SOLO_SRC_NAMESPACE` | `clpr-src` | — | Kubernetes namespace |
| `SOLO_DST_NAMESPACE` | — | `clpr-dst` | Kubernetes namespace |
| `SRC_APP_PROPERTIES` | `path/to/application-src.properties` | — | CLPR config for this network |
| `DST_APP_PROPERTIES` | — | `path/to/application-dst.properties` | CLPR config for this network |

**Port mappings (no collisions):**

| Service | SRC Port | DST Port | Remote Port |
|---|---|---|---|
| gRPC (consensus) | 50261 | 60061 | 50211 |
| Block Node | 54082 | 54083 | 40840 |
| Mirror REST | 5551 | 5552 | 80 |
| JSON-RPC Relay | 7546 | 7547 | 7546 |
| Explorer | 9001 | 9002 | 8080 |

## 4.2 Cluster Setup (One-Time)

If the cluster and shared infrastructure do not already exist:

```bash
# Connect SOLO to the cluster
solo cluster-ref config connect \
  --cluster-ref $SOLO_CLUSTER_REF \
  --context $SOLO_CLUSTER_CONTEXT \
  -q

# Install shared infrastructure (cert-manager, MinIO, etc.)
solo cluster-ref config setup \
  --cluster-setup-namespace $SOLO_CLUSTER_SETUP_NAMESPACE \
  --no-prometheus-stack \
  -q --dev
```

If the cluster is already set up (e.g., by a previous CITR run), this step is skipped. The
`cluster-ref config setup` is idempotent — running it twice is safe but unnecessary. The key indicator
is whether the `solo-setup` namespace exists.

**Difference from standard CITR:** Standard CITR runs cluster setup once per test suite. Multi-network
CLPR testing shares the same cluster setup across both deployments. No change needed to the cluster
setup step itself — only to the awareness that it is shared.

## 4.3 Create Deployment Configurations (Serial)

Create the SOLO deployment configs for both networks. This is a lightweight local operation (no
Kubernetes resources are created yet):

```bash
# Source network
solo deployment config create -d $SOLO_SRC_DEPLOYMENT -n $SOLO_SRC_NAMESPACE --dev -q
solo deployment cluster attach -d $SOLO_SRC_DEPLOYMENT -c $SOLO_CLUSTER_REF \
  --num-consensus-nodes $NODE_COUNT --dev -q

# Destination network
solo deployment config create -d $SOLO_DST_DEPLOYMENT -n $SOLO_DST_NAMESPACE --dev -q
solo deployment cluster attach -d $SOLO_DST_DEPLOYMENT -c $SOLO_CLUSTER_REF \
  --num-consensus-nodes $NODE_COUNT --dev -q
```

**Difference from standard CITR:** Standard CITR creates one deployment config. Here we create two.
The `deployment cluster attach` call associates each deployment with the shared cluster reference. Both
deployments can reference the same cluster — they are logically separate but physically co-located.

## 4.4 Deploy Core Network (Parallel)

The core network deployment (keys, consensus nodes, block nodes) can run in parallel for both networks.
Each side runs in a subshell with its own isolated SOLO home directory to prevent state file conflicts.

**Parallel isolation:** Before launching the parallel subshells, copy the SOLO local config to isolated
directories:

```bash
mkdir -p $SOLO_HOME/parallel-src $SOLO_HOME/parallel-dst
cp $SOLO_HOME/local-config.yaml $SOLO_HOME/parallel-src/
cp $SOLO_HOME/local-config.yaml $SOLO_HOME/parallel-dst/
```

Each subshell sets `SOLO_HOME` (and `HELM_CACHE_HOME`, `HELM_CONFIG_HOME`, `HELM_DATA_HOME`) to its
isolated directory before running any SOLO commands.

**Per-side core deployment sequence:**

```bash
# 1. Generate consensus keys
solo keys consensus generate \
  -d <deployment> \
  --gossip-keys --tls-keys \
  -i <node-aliases> \
  --dev -q

# 2. Deploy block node (if enabled)
solo block node add \
  -d <deployment> \
  --force-port-forward=false \
  --dev -q

# 3. Deploy consensus network with CLPR application properties
solo consensus network deploy \
  -d <deployment> \
  -i <node-aliases> \
  --application-properties <src-or-dst-app-properties> \
  --dev -q

# 4. Apply local build artifacts
solo consensus node setup \
  -d <deployment> \
  -i <node-aliases> \
  --local-build-path $CN_LOCAL_BUILD_PATH \
  --dev -q

# 5. Start consensus nodes
solo consensus node start \
  -d <deployment> \
  -i <node-aliases> \
  --dev -q
```

**The critical SRC vs DST difference is step 3:** the `--application-properties` flag points to
different files for each network. These files contain the CLPR-specific configuration — chain ID,
`clpr.enabled=true`, system contract settings, and any network-specific parameters.

**Difference from standard CITR:** Standard CITR runs this sequence once. Here it runs twice in
parallel. The parallel execution roughly maintains the same wall-clock time as a single deployment,
at the cost of higher CPU and memory usage during setup.

**Error handling:** If either parallel subshell fails, the working pattern retries the failed side
serially (the parallel environment may have transient Kubernetes connectivity issues). If serial
retry also fails, the deployment aborts.

## 4.5 Deploy Services (Parallel)

After core networks are running, deploy the supporting services (mirror node, relay, explorer) in
parallel:

```bash
# Mirror node
solo mirror node add \
  -d <deployment> \
  --profile tiny \
  --force-port-forward=false \
  --dev -q

# JSON-RPC Relay
solo relay node add \
  -d <deployment> \
  -i <node-aliases> \
  --profile tiny \
  --force-port-forward=false \
  --dev -q

# Explorer (requires mirror node to be running)
solo explorer node add \
  -d <deployment> \
  --mirror-namespace <namespace> \
  --profile tiny \
  --force-port-forward=false \
  --dev -q
```

**Difference from standard CITR:** Same commands, but run for both deployments. The `--profile tiny`
flag is important for multi-network deployments on resource-constrained clusters — it minimizes the
resource footprint of each service. Additional CPU/memory overrides may be applied via
`kubectl set resources` after deployment.

## 4.6 Post-Deployment: Mirror Node Configuration

After mirror node deployment, two additional steps are needed per network:

**Enforce node public key:** The mirror node importer must know the consensus node's public key to
verify record streams. Extract it from the Kubernetes secret and set it as an environment variable:

```bash
# Extract node public key from the K8s secret
PUBLIC_KEY_HEX=$(kubectl get secret network-node1-keys-secrets \
  -n <namespace> \
  -o jsonpath='{.data.s-public-node1\.pem}' | base64 -d | \
  openssl ec -pubin -inform PEM -outform DER 2>/dev/null | \
  xxd -p | tr -d '\n')

# Set on the mirror importer deployment
kubectl set env deployment/mirror-1-importer \
  -n <namespace> \
  HIERO_MIRROR_IMPORTER_NODE_PUBLIC_KEY=$PUBLIC_KEY_HEX
```

This step is specific to multi-network SOLO because each network has its own consensus node keys.
Standard CITR may handle this automatically within a single deployment.

## 4.7 Port Forwarding

Set up port forwards from localhost to each network's services. Each service gets a unique local port
per network (see the port mapping table in §4.1).

Port forwards should be managed by a supervisor process that automatically restarts the
`kubectl port-forward` command if it drops. The working pattern uses `nohup` detached bash loops that
monitor the child process and restart it on exit.

**Difference from standard CITR:** Standard CITR forwards to a single set of ports. Multi-network
requires two sets of non-overlapping ports. The test driver must be configured to use the correct ports
for each network.

## 4.8 Cross-Namespace Networking for CLPR Endpoints

This is the most important difference from standard SOLO deployment.

**For the test driver (external to the cluster):** The port forwards provide access. The test driver
sends HAPI transactions to `localhost:50261` (SRC) and `localhost:60061` (DST).

**For CLPR endpoint-to-endpoint communication (inside the cluster):** The CLPR endpoint module on a
consensus node in the SRC namespace needs to reach a consensus node in the DST namespace via gRPC.
Kubernetes allows cross-namespace pod communication by default within a cluster — pods can address
services in other namespaces using the fully qualified DNS name:

```
haproxy-node1-svc.<dst-namespace>.svc.cluster.local:50211
```

The CLPR application properties must configure seed endpoints or peer addresses using these
cross-namespace DNS names. This is where the `application-src.properties` and
`application-dst.properties` files differ: each points to the other network's service addresses
within the cluster.

**No NetworkPolicy changes are needed** if the cluster uses the default "allow all" policy (which kind
clusters do). For production-like clusters with restrictive NetworkPolicies, explicit rules must allow
gRPC traffic between the CLPR namespaces.

---

# 5. Teardown

Teardown reverses the deployment in order, for each network:

```bash
# 1. Stop consensus nodes
solo consensus node stop -d <deployment> -i <node-aliases> --dev -q

# 2. Destroy services (reverse order of deployment)
solo relay node destroy -d <deployment> -i <node-aliases> --dev -q
solo explorer node destroy -d <deployment> --force --dev -q
solo mirror node destroy -d <deployment> --force --dev -q
solo block node destroy -d <deployment> --force --dev -q

# 3. Destroy consensus network and clean up K8s resources
solo consensus network destroy -d <deployment> --delete-pvcs --delete-secrets --force --dev -q

# 4. Delete local deployment config
solo deployment config delete -d <deployment> --dev -q
```

Service destroy commands use `|| true` to ignore errors — teardown should be best-effort and not abort
on partial failures.

**Difference from standard CITR:** Run the teardown sequence for each deployment. The cluster-level
infrastructure (solo-setup namespace) is not torn down — it is shared and may be used by subsequent
test runs.

---

# 6. Key Differences Summary

| Aspect | Standard CITR (Single Network) | Multi-Network CLPR |
|---|---|---|
| Deployments | 1 | 2+ |
| Namespaces | 1 | 1 per deployment |
| Cluster setup | Once | Once (shared) |
| Core deployment | Serial, single invocation | Parallel, isolated SOLO_HOME per side |
| Application properties | One file | One per network (different chain IDs) |
| Port forwards | One set | Non-overlapping sets per network |
| Cross-namespace networking | N/A | Required for endpoint-to-endpoint gRPC |
| Teardown | One deployment | Per deployment, best-effort |
| Resource usage | Baseline | ~2x baseline (two full networks) |

---

# 7. CITR Integration Considerations

For SOLO maintainers evaluating this approach:

1. **SOLO's deployment model already supports this.** Every command takes `-d <deployment>` and
   `-n <namespace>` parameters. The tool has no single-deployment assumption — the single-deployment
   pattern in CITR is a CITR convention, not a SOLO limitation.

2. **Parallel execution requires isolated SOLO_HOME directories.** When running two SOLO command
   sequences concurrently, each must operate on its own state directory to avoid file conflicts. The
   pattern of copying `local-config.yaml` to isolated directories and setting `SOLO_HOME` per subshell
   is a workaround — SOLO could natively support concurrent operations on different deployments if
   this pattern proves fragile.

3. **The `--force-port-forward=false` flag is essential.** Multi-network deployments must disable
   SOLO's automatic port forwarding because the default port assignments would collide. Port forwarding
   is managed externally with explicit port mappings per network.

4. **The `--profile tiny` flag reduces resource footprint.** Running two full networks on a single
   kind cluster is resource-intensive. The `tiny` profile and additional `kubectl set resources`
   overrides keep the total footprint manageable.

5. **Cross-namespace DNS is the networking mechanism.** No SOLO changes are needed for CLPR
   endpoint-to-endpoint communication — Kubernetes service DNS provides it. The CLPR application
   properties just need to reference the correct fully-qualified service names.
