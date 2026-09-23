# Besu QBFT ↔ Hiero CLPR end-to-end test

End-to-end walkthrough for verifying the Besu-QBFT verifier in clpr-hiero against
a real Besu network: deploy CLPR contracts on Besu, stand up a Hiero node, bridge
the channel from Besu to Hiero via yahcli, run the EVM relay, and watch a
message flow Besu → Hiero with full proof verification on chain.

## Repos involved

```
~/.../clpr/
  clpr-smart-contracts/   ← Solidity CLPR contracts + local Besu compose
  clpr-evm-endpoint/      ← EVM-side relay (watches Besu, pushes bundles via gRPC)
  clpr-hiero/             ← this repo: Hedera node + yahcli + verifier system contract
```

All three should be siblings of each other. The bridge script auto-discovers
`clpr-smart-contracts` from either `<hiero>/../clpr-smart-contracts` or
`<hiero>/../../clpr-smart-contracts`; override with `SMART_CONTRACTS_REPO=...`
if your layout is different.

## Prerequisites

- JDK 25 (yahcli.jar is class file 69 — older JDKs fail with
  `UnsupportedClassVersionError`). The scripts auto-export
  `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`
  if present; otherwise set `JAVA_HOME` yourself.
- [Foundry](https://book.getfoundry.sh/) (`forge`, `cast`) on PATH —
  `bridge-from-besu.sh` shells out to `cast`.
- Docker + Docker Compose — the Besu compose stack runs the QBFT chain.
- Python 3 — the trust-anchor and connector-signing tools at
  `hedera-node/tools/*.py` are stdlib-only Python.
- `jq` on PATH.
- `~/.sdkman/candidates/java/25.x` or the temurin JDK 25 install above; sdkman
  is the easiest way to switch.

## Build artifacts (once per checkout)

```bash
# clpr-hiero: build yahcli.jar
cd clpr-hiero
./gradlew :yahcli:copyYahCli              # produces yahcli.jar in hedera-node/yahcli/

# clpr-evm-endpoint: build the relay
cd ../clpr-evm-endpoint
./gradlew :clpr-relay-app:installDist     # produces clpr-relay-app/build/install/...
```

## Configure yahcli for the local network (once per checkout)

The yahcli calls below run as `-n localhost -p 2`, so `hedera-node/yahcli/config.yml`
must define a `localhost` network and the matching key directory must hold the payer
(account `0.0.2`, the treasury) PEM.

Add a `localhost` network to `hedera-node/yahcli/config.yml`:

```yaml
networks:
  localhost:
    allowedReceiverAccountIds: []
    nodes:
      - { id: 0, account: 3, ipv4Addr: 127.0.0.1, port: 50211 }
```

Then place the treasury key in `localhost/keys/` (the `account2.pass` passphrase is
already there; only the PEM is missing):

```bash
cd hedera-node/yahcli
mkdir -p localhost/keys
cp ../test-clients/yahcli/localhost/keys/account2.pem localhost/keys/
cp ../test-clients/yahcli/localhost/keys/account2.pass localhost/keys/
```

## Step 1 — Bring up Besu and deploy CLPR

```bash
cd clpr-smart-contracts
script/demo.sh
```

What this does:
1. Boots a single-node Besu QBFT chain in Docker on `127.0.0.1:53321`
(`test/e2e/backend/docker-compose.yml`).
2. Runs `script/Deploy.s.sol` — deploys the CLPR Service + module libraries.
3. Runs `script/CreateChannel.s.sol` — creates an ACTIVE channel on Besu
using `PEER_PK=0xa11ce` and `PEER_CHAIN_ID="eip155:1338"` (defaults).
4. Runs `script/CreateConnector.s.sol` — registers a connector using
`CONNECTOR_PK=0xc044ec` (default).
5. Runs `script/SendMessage.s.sol` — queues a test message on the channel.
6. Runs `script/SeedHieroEndpoint.s.sol` — pushes a seed endpoint pointing at
the Hedera HAPI port (`127.0.0.1:50211`) into Besu's on-chain ledger config
so the relay knows where to forward bundles.

Outputs:
- `.env` with deployed addresses (`CLPR_SERVICE`, `BESU_RPC_A`, etc.).
- `deployments/<chainId>/channel.json` — `{ clprService, channelId, peerChainId, verifier, peerAddress }`.
- `deployments/<chainId>/connector.json` — `{ connectorId, connectorContract, connectorSigner, admin, stakeWei, ... }`.

These JSON files are what the bridge script reads in step 3. Don't edit them by hand.

Tear down with `npm run e2e:down` when you're done.

## Step 2 — Start the Hedera consensus node

The node hosts the `proto.ClprEndpointService` gRPC service on the HAPI port
(`localhost:50211`) and the Besu-QBFT verifier system contract at `0.0.367`.

```bash
cd clpr-hiero
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home \
  ./gradlew :app:modrun
```

Wait ~30 s for `ServicesMain` to come up. Verify:

```bash
lsof -nP -iTCP:50211 -sTCP:LISTEN     # should show the java pid
```

The node typically writes logs to `hedera-node/hedera-app/build/node/output/hgcaa.log`.

### Top up the node payer account

Every inbound `ClprSubmitBundle` transaction the sync workflow generates is
paid by the node's own account (default `0.0.3` on `localhost`). Each verifier dispatch
burns gas. The `run-clpr-end-to-end.sh` `accounts send` step seeds 1B hbar by
default; use the following command to specify an amount:

```bash
cd hedera-node/yahcli
./yahcli -n localhost -p 2 accounts send --to 3 1000000000 -d hbar
```

Symptom of an empty balance: `[EvmClprVerifier] dispatch FAILED status=INSUFFICIENT_PAYER_BALANCE`
spammed in `hgcaa.log`.

## Step 3 — Bridge Besu's channel identity into Hiero

```bash
cd hedera-node/yahcli
VERIFIER_CONTRACT=0.0.367 ./scripts/clpre2e/bridge-from-besu.sh
```

What the script does (9 steps; each yahcli call is logged):
1. `update-ledger-configuration` on `localhost` (Hiero's own throttles/endpoints).
2. Builds the Besu QBFT trust anchor:
- Reads `clprService`, `peerChainId`, `channelId` from
`clpr-smart-contracts/deployments/<chainId>/channel.json`.
- Derives the validator address from `.env`'s `PRIVATE_KEY` (single-validator
dev Besu = the same deployer key).
- `cast keccak "$(cast code $CLPR_SERVICE)"` for the code hash.
- Fetches live throttles + endpoints via
`cast call getLedgerConfiguration()` and synthesizes a fallback endpoint
at `127.0.0.1:9545` (relay's gRPC port) if none are registered.
- Calls `hedera-node/tools/build-besu-qbft-trust-anchor.py --config-json ...`
to emit the RLP trust-anchor envelope wrapped in a protobuf
`ClprLedgerConfiguration`.
3. `gen-channel-identity.sh` — builds the channel identity bundle (`channel.json`)
using `PEER_PK=0xa11ce` (matches `CreateChannel.s.sol`'s default).
4. `register-channel` — commit phase.
5. `complete-channel` — reveal phase with the trust-anchor bytes from step 2.
6. Connector identity bundle via `sign-clpr-connector-identity.py` using
`CONNECTOR_PK=0xc044ec` + `salt=0` (matches `CreateConnector.s.sol`).
7. `register-connector`.
8. `contracts create` for `PassThroughAuth` (the connector contract).
9. `complete-connector`.

If everything passes you'll see `ok: complete-connector` and a summary block:

```
Besu side (from clpr-smart-contracts/deployments/<chainId>/):
  CLPR service      : 0x...
  Channel id     : 0x...
  Connector id      : 0x...

Hiero side (this script):
  Network            : localhost
  Verifier contract  : 0.0.367
  Connector contract : 0.0.<id>
  Channel bundle  : .bridge/channel.json
  Connector bundle   : .bridge/connector.json
```

Per-step yahcli logs are at `scripts/clpre2e/.run-logs-bridge/`.

### Useful overrides

|                  Env                  |             Default             |                                        Purpose                                        |
|---------------------------------------|---------------------------------|---------------------------------------------------------------------------------------|
| `VERIFIER_CONTRACT`                   | (required)                      | Pre-deployed Besu-QBFT verifier system contract id on Hiero (0.0.367 on localhost)    |
| `SMART_CONTRACTS_REPO`                | sibling auto-detect             | Path to `clpr-smart-contracts`                                                        |
| `CHAIN_ID`                            | auto-detect from `deployments/` | Picks one of the chain subdirs                                                        |
| `PEER_PK`                             | `0xa11ce`                       | secp256k1 key used for the channel identity (must match Besu's `CreateChannel.s.sol`) |
| `CONNECTOR_PK`                        | `0xc044ec`                      | secp256k1 key for the connector identity                                              |
| `NET`                                 | `localhost`                     | Target Hiero network from `yahcli/config.yml`                                         |
| `PAYER`                               | `2`                             | Yahcli payer account                                                                  |
| `LOCKED_STAKE`                        | `100000000`                     | Connector locked stake (tinybars)                                                     |
| `FALLBACK_EP_IP` / `FALLBACK_EP_PORT` | `127.0.0.1` / `9545`            | Synthetic seed endpoint when Besu's list is empty                                     |

## Step 4 — Start the EVM relay

```bash
cd clpr-evm-endpoint
script/run-from-state.sh
```

This auto-discovers the Besu RPC URL from `clpr-smart-contracts/.env`
(`BESU_RPC_A`), reads `CLPR_SERVICE` from `deployments/<chainId>/channel.json`,
runs the preflight (`cast` reachability + bytecode at the contract address),
then `exec`s `./gradlew :clpr-relay-app:run` with the right `-D` flags.

Watch the relay's stdout for:

```
INFO [RelayInstance] on-chain protocolVersion=1, relay protocolVersion=1
INFO CLPR EVM Relay started on port 9545
INFO [StateListener] state changed for conn=… block=N; M pending message(s)
```

If you see `protocolVersion=0` or `Connection failed after 3 retries`, the relay
is pointing at the wrong RPC or the contract address is stale — re-run
`smart-contracts/script/demo.sh` and restart.

## Step 5 — Send a fresh message and validate end-to-end

In the smart-contracts repo, queue a new outbound message:

```bash
cd clpr-smart-contracts
forge script script/SendMessage.s.sol --rpc-url besu --broadcast --legacy
```

Then watch both sides simultaneously.

### Relay (clpr-evm-endpoint stdout)

```
INFO [StateListener] state changed for conn=… block=N; X pending message(s)
INFO [QbftBundle] building proof for conn=… block=N messages=[1..X] count=X
INFO [QbftBundle] cached proof for conn=… block=N blockHash=… accountProofNodes=A storageProofEntries=5 bundleContentBytes=B totalProofBytes=T
```

### Hedera node (`hedera-node/hedera-app/build/node/output/hgcaa.log`)

The full success chain looks like:

```
INFO BesuQBFTVerifyBundleCall - verifyBundle (QBFT) ENTER: bundlePayload=N bytes, trustAnchor=…
INFO BesuQBFTVerifyBundleCall - verifyBundle (QBFT) trustAnchor decoded: trustedValidator=0xf39fd6…, trustedClprService=0x…, trustedClprServiceCodeHash=0x…
INFO BesuQbftProofVerifier  - BesuQbftProofVerifier.verifyBundle ENTER: bundlePayload=N bytes, trustAnchor(validator)=0xf39fd6…
INFO BesuQbftProofVerifier  - BesuQbftProofVerifier.verifyBundle header: blockHash=0x…, stateRoot=0x…, headerFields=21
INFO BesuQbftProofVerifier  - BesuQbftProofVerifier.verifyQbftSealAgainstTrustAnchor: recoveredValidator=0xf39fd6…, trustAnchor=0xf39fd6…
INFO BesuQbftProofVerifier  - BesuQbftProofVerifier.verifyBundle QBFT committed seal verified against trustAnchor
INFO BesuQbftProofVerifier  - BesuQbftProofVerifier.verifyBundle account proof verified: contractAddress=0x…, provenStorageRoot=0x…, provenCodeHash=0x…
INFO BesuQbftProofVerifier  - BesuQbftProofVerifier.verifyBundle storageProof[0..4] verified
INFO BesuQbftProofVerifier  - BesuQbftProofVerifier.verifyBundle queueMetadata: nextMessageId=…, receivedMessageId=…, …
INFO BesuQbftProofVerifier  - BesuQbftProofVerifier.verifyBundle EXIT: SUCCESS blockHash=0x… bundleContent=B bytes
INFO BesuQBFTVerifyBundleCall - verifyBundle (QBFT) EXIT: SUCCESS trustAnchor=… blockHash=… content=B bytes
```

Filter command:

```bash
tail -f hedera-node/hedera-app/build/node/output/hgcaa.log \
  | grep -E "verifyBundle|verifyQbftSeal|account proof|storage proof|ClprSubmitBundle rejected"
```

### Common error logs

- **`recoveredValidator` != `trustAnchor`** → seal-hash mismatch. Make sure the
  relay is on a build with the `BlockHeader` record containing
  `blobGasUsed`/`excessBlobGas`/`requestsHash` (full 21-field Besu post-Cancun
  header). See `clpr-evm-endpoint/clpr-relay-evm/.../QbftBundleConstructor.java`.
- **`block header is not an RLP list of 15..23 fields`** → relay's header is
  shorter than what Besu signed; same fix.
- **`payload is not a valid RLP item: trailing bytes after RLP item`** → relay
  is using `StubBundleConstructor` instead of `QbftBundleConstructor`. Check
  `clpr-evm-endpoint/clpr-relay-app/.../RelayInstance.java` step 2.
- **`ClprSubmitBundle rejected: status=CLPR_VERIFIER_CONFIG_FAILED`** at
  *complete-channel time* → trust anchor's throttles or endpoints are
  missing/empty. Re-run the bridge script with throttles populated.
- **`Connection failed after 3 retries`** in the relay → the Besu RPC port is
  unreachable. Re-run `script/demo.sh` (Docker may have re-mapped the port on
  a restart).

## Scripts in this directory

|          Script           |                                                                                          What it does                                                                                           |
|---------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `bridge-from-besu.sh`     | Bridge a Besu-deployed channel onto Hiero via yahcli. Auto-pulls state from `clpr-smart-contracts/deployments/`.                                                                                |
| `gen-channel-identity.sh` | Build a CLPR channel-identity JSON from a channel id + secp256k1 private key (used by the bridge in step 3).                                                                                    |
| `start-besu-qbft.sh`      | Spin up a single-node Besu QBFT chain in Docker Compose with a pre-funded dev account. Not currently used by the bridge flow (`clpr-smart-contracts/script/demo.sh` uses its own compose file). |
| `start-hiero-local.sh`    | Start or stop one local Hiero node with `./gradlew :app:run` by default, auto-using local TSS wraps when present and cleaning up stale HAPI port listeners on `down`.                           |
