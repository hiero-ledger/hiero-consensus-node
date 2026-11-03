#!/usr/bin/env bash
#
# Spin up a single-node Besu QBFT network in Docker Compose with a pre-funded
# well-known dev account (the one from Besu's QBFT tutorials):
#
#   address     : 0xfe3b557e8fb62b89f4916b721be55ceb828dbd73
#   private key : 0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63
#
# After this script returns, the JSON-RPC endpoint is at http://localhost:8545
# and chainId is 1337.
#
# Usage:
#   ./start-besu-qbft.sh             # start (idempotent — wipes ./qbft-network)
#   ./start-besu-qbft.sh down        # stop and remove containers + data
#
set -euo pipefail

BESU_IMAGE="${BESU_IMAGE:-hyperledger/besu:24.10.0}"
NETWORK_DIR="${NETWORK_DIR:-$(pwd)/qbft-network}"
RPC_PORT="${RPC_PORT:-8545}"
P2P_PORT="${P2P_PORT:-30303}"
CHAIN_ID="${CHAIN_ID:-1337}"

PAYER_ADDRESS="fe3b557e8fb62b89f4916b721be55ceb828dbd73"
PAYER_BALANCE="0xad78ebc5ac6200000"   # 200 ETH

cmd="${1:-up}"

if [[ "$cmd" == "down" ]]; then
  (cd "$NETWORK_DIR" && docker compose down -v) || true
  rm -rf "$NETWORK_DIR"
  echo "Network torn down."
  exit 0
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "docker not found on PATH" >&2
  exit 1
fi

# A previous run may have left a running container holding files in $NETWORK_DIR.
# Tear it down first so the cleanup below is unobstructed.
if [[ -f "$NETWORK_DIR/docker-compose.yml" ]]; then
  (cd "$NETWORK_DIR" && docker compose down -v >/dev/null 2>&1) || true
fi
docker rm -f besu-qbft-node1 >/dev/null 2>&1 || true

# Plain rm first; fall back to an in-container rm if files are owned by the
# container's uid and the host user can't unlink them.
rm -rf "$NETWORK_DIR" 2>/dev/null || true
if [[ -e "$NETWORK_DIR" ]]; then
  docker run --rm --user 0 \
    -v "$(dirname "$NETWORK_DIR"):/host" \
    alpine rm -rf "/host/$(basename "$NETWORK_DIR")"
fi
mkdir -p "$NETWORK_DIR"

# ---- 1. Blueprint for `besu operator generate-blockchain-config` -------------
cat > "$NETWORK_DIR/qbftConfigFile.json" <<EOF
{
  "genesis": {
    "config": {
      "chainId": ${CHAIN_ID},
      "homesteadBlock": 0,
      "eip150Block": 0,
      "eip155Block": 0,
      "eip158Block": 0,
      "byzantiumBlock": 0,
      "constantinopleBlock": 0,
      "petersburgBlock": 0,
      "istanbulBlock": 0,
      "berlinBlock": 0,
      "londonBlock": 0,
      "shanghaiTime": 0,
      "cancunTime": 0,
      "zeroBaseFee": true,
      "qbft": {
        "blockperiodseconds": 2,
        "epochlength": 30000,
        "requesttimeoutseconds": 4
      }
    },
    "nonce": "0x0",
    "timestamp": "0x0",
    "gasLimit": "0x1fffffffffffff",
    "difficulty": "0x1",
    "mixHash": "0x63746963616c2062797a616e74696e65206661756c7420746f6c6572616e6365",
    "coinbase": "0x0000000000000000000000000000000000000000",
    "alloc": {
      "${PAYER_ADDRESS}": {
        "balance": "${PAYER_BALANCE}",
        "comment": "Besu docs well-known dev account"
      }
    }
  },
  "blockchain": {
    "nodes": {
      "generate": true,
      "count": 1
    }
  }
}
EOF

# ---- 2. Generate node key + extraData-laced genesis --------------------------
# Note: besu 24.x's `operator generate-blockchain-config` writes a valid
# genesis + keys and *then* throws "Output directory already exists." with
# exit 1. We swallow the spurious failure and validate the artifacts instead.
docker run --rm \
  -v "$NETWORK_DIR:/opt/besu/data" \
  -w /opt/besu/data \
  "$BESU_IMAGE" \
  operator generate-blockchain-config \
  --config-file=/opt/besu/data/qbftConfigFile.json \
  --to=/opt/besu/data/networkFiles \
  --private-key-file-name=key || true

if [[ ! -s "$NETWORK_DIR/networkFiles/genesis.json" ]]; then
  echo "operator generate-blockchain-config did not produce genesis.json" >&2
  exit 1
fi

# operator emits keys under networkFiles/keys/<validator-address>/
GENERATED_KEY_DIR="$(find "$NETWORK_DIR/networkFiles/keys" -mindepth 1 -maxdepth 1 -type d | head -n1)"
if [[ -z "$GENERATED_KEY_DIR" || ! -s "$GENERATED_KEY_DIR/key" ]]; then
  echo "operator generate-blockchain-config did not produce a validator key" >&2
  exit 1
fi

mkdir -p "$NETWORK_DIR/node1/data"
cp "$GENERATED_KEY_DIR/key"     "$NETWORK_DIR/node1/data/key"
cp "$GENERATED_KEY_DIR/key.pub" "$NETWORK_DIR/node1/data/key.pub"
cp "$NETWORK_DIR/networkFiles/genesis.json" "$NETWORK_DIR/genesis.json"

# Besu in the container runs as uid 1000 (`besu`). Make data writable.
chmod -R a+rwX "$NETWORK_DIR/node1" || true

# ---- 3. docker-compose.yml ---------------------------------------------------
cat > "$NETWORK_DIR/docker-compose.yml" <<EOF
services:
  besu:
    image: ${BESU_IMAGE}
    container_name: besu-qbft-node1
    restart: unless-stopped
    ports:
      - "${RPC_PORT}:8545"
      - "${P2P_PORT}:30303"
      - "${P2P_PORT}:30303/udp"
    volumes:
      - ./genesis.json:/opt/besu/genesis.json:ro
      - ./node1/data:/opt/besu/data
    command:
      - --data-path=/opt/besu/data
      - --genesis-file=/opt/besu/genesis.json
      - --node-private-key-file=/opt/besu/data/key
      - --network-id=${CHAIN_ID}
      - --p2p-host=0.0.0.0
      - --p2p-port=30303
      - --min-gas-price=0
      - --rpc-http-enabled
      - --rpc-http-host=0.0.0.0
      - --rpc-http-port=8545
      - --rpc-http-cors-origins=all
      - --rpc-http-api=ETH,NET,WEB3,QBFT,ADMIN,DEBUG,TRACE,TXPOOL
      - --host-allowlist=*
      - --logging=INFO
EOF

# ---- 4. Up! ------------------------------------------------------------------
(cd "$NETWORK_DIR" && docker compose up -d)

echo
echo "Besu QBFT single-node network is starting."
echo "  Network dir : $NETWORK_DIR"
echo "  RPC         : http://localhost:${RPC_PORT}"
echo "  Chain ID    : ${CHAIN_ID}"
echo "  Payer       : 0x${PAYER_ADDRESS}"
echo "  Payer key   : 0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63"
echo
echo "Tail logs : docker logs -f besu-qbft-node1"
echo "Tear down : $0 down"
