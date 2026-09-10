# Sei CometBFT ↔ Hiero flow

This is the same shape as the Besu flow, but the peer ledger is local Sei. The
`bridge-from-sei.sh` script defaults to a Hiero verifier contract ID of `0.0.368`
(`0x170`).

### Configure yahcli for the local network (once per checkout)

`bridge-from-sei.sh` uses the first local yahcli network it finds (`localhost` or
`alice`), so `hedera-node/yahcli/config.yml` must define that network and the
matching key directory must hold the payer (account `0.0.2`, the treasury) PEM.

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
cd clpr-hiero/hedera-node/yahcli
mkdir -p localhost/keys
cp ../test-clients/yahcli/localhost/keys/account2.pem localhost/keys/
cp ../test-clients/yahcli/localhost/keys/account2.pass localhost/keys/
```

### Step 1 — Start Hiero

```bash
cd clpr-hiero/hedera-node/yahcli/scripts/clpre2e
TSS_LIB_WRAPS_ARTIFACTS_PATH=/Users/neeharikasompalli/Documents/wraps-v1.0.0 \
  ./start-hiero-local.sh
```

If `~/Documents/wraps-v1.0.0` exists, the script auto-exports
`TSS_LIB_WRAPS_ARTIFACTS_PATH`. Override `HIERO_GRADLE_TASK` if you need a
different Gradle task; the default is `:app:run`.

### Step 2 — Start one local Sei node

```bash
cd clpr-hiero/hedera-node/yahcli/scripts/clpre2e
SEI_MEMORY=8g SEI_CPUS=2 GOMEMLIMIT=6GiB GOGC=50 ./start-sei-local.sh
```

The script pre-funds the default Foundry deployer (`0xf39f...2266`) in Sei
genesis at its direct-cast Sei address
`sei17w0adeg64ky0daxwd2ugyuneellmjgnxw32ydp`. This matters because
`MOCK_BALANCES=true` only makes EVM balance reads _appear_ funded; the proposer still
needs the signer to exist in the Cosmos bank state. It also disables the local
historical-proof rate limiter by default
(`SEI_HISTORICAL_PROOF_RATE_LIMIT=0`) because one CLPR bundle needs several
storage proofs from CometBFT in quick succession. After the first successful
build, you can restart faster with:

```bash
SEI_SKIP_BUILD=true SEI_MEMORY=8g SEI_CPUS=2 GOMEMLIMIT=6GiB GOGC=50 ./start-sei-local.sh
```

On macOS the script publishes container ports instead of relying on Docker host
networking. If you have a prebuilt local image, point the script at it:

```bash
SEI_IMAGE=your-local-image:tag SEI_SKIP_BUILD=true SEI_MEMORY=6g ./start-sei-local.sh
```

The custom image must already exist in the active Docker context. If you want
the image built by `make build-docker-node`, omit `SEI_IMAGE`; the default is
`sei-chain/localnode:latest`. A `6g` container can start the node, but `8g` is
the safer minimum for the CometBFT storage-proof validation path.

To pre-fund a different EVM deployer, pass either a Sei address or an EVM
address:

```bash
SEI_PREFUND_ACCOUNTS=0xYourDeployer ./start-sei-local.sh
```

### Step 3 — Deploy CLPR to Sei and queue a demo message

```bash
cd clpr-hiero/hedera-node/yahcli/scripts/clpre2e
./deploy-sei-clpr.sh
```

This targets Sei's EVM RPC at `http://127.0.0.1:8545`, writes
`clpr-smart-contracts/deployments/<sei-evm-chain-id>/channel.json`, opens a
channel, registers a connector, sends one message, registers the relay's EVM
account as a local Sei endpoint, and seeds Sei's ledger config with the Hiero
endpoint via `SeedHieroEndpoint.s.sol`.

### Step 4 — Bridge the Sei channel into Hiero

```bash
cd clpr-hiero/hedera-node/yahcli/scripts/clpre2e
./bridge-from-sei.sh
```

The bridge script builds a `ClprSeiLedgerConfigurationPayload` from the local
CometBFT validator set and completes the channel against `0.0.368`. It uses
the first local yahcli network it finds (`localhost` or `alice`); override with
`NET=...` if your `hedera-node/yahcli/config.yml` uses a different name.

It also regenerates `hedera-node/yahcli/ledger-config.json` by default and funds
node account `0.0.3` with `1,000,000,000 hbar`, because self-submitted
`ClprSubmitBundle` transactions are paid by the node account. To skip the
top-up, set `FUND_NODE_ACCOUNT=false`.

### Step 5 — Run the Sei relay

```bash
cd clpr-hiero/hedera-node/yahcli/scripts/clpre2e
./run-sei-relay-from-state.sh
```

The relay config is generated at `.bridge/sei-relay.yaml`. The relay uses
`ProofType=CometBFT`, so its `SeiBundleConstructor` builds real ICS-23 storage
proofs from `abci_query` and submits them to Hiero. Before starting, the script
also verifies/registers the relay account as a local Sei endpoint so ACK bundles
do not revert with `ClprEndpointNotRegistered()`.

For a clean restart of this flow:

```bash
cd clpr-hiero/hedera-node/yahcli/scripts/clpre2e

# Stop foreground relay with Ctrl-C first, then:
./start-hiero-local.sh down
./start-sei-local.sh down

SEI_SKIP_BUILD=true SEI_MEMORY=8g SEI_CPUS=2 GOMEMLIMIT=6GiB GOGC=50 \
  ./start-sei-local.sh

TSS_LIB_WRAPS_ARTIFACTS_PATH=/Users/neeharikasompalli/Documents/wraps-v1.0.0 \
  ./start-hiero-local.sh

./deploy-sei-clpr.sh
./bridge-from-sei.sh
./run-sei-relay-from-state.sh
```

### Step 6 — Verify proof logs

In the Hiero node log, look for:

```bash
tail -f clpr-hiero/hedera-node/hedera-app/build/node/output/hgcaa.log \
  | grep -E "verifyConfig \\(Sei\\)|verifyBundle \\(Sei\\)|SeiCometBftProofVerifier|ClprSubmitBundle rejected"
```

Successful config verification includes:

```text
verifyConfig (Sei) EXIT: SUCCESS
```

Successful bundle proof verification includes:

```text
verifyBundle (Sei) EXIT: SUCCESS
```

Queue another message from the smart-contracts repo:

```bash
cd clpr-smart-contracts
HIERO_RPC=http://127.0.0.1:8545 forge script script/SendMessage.s.sol --rpc-url hiero --broadcast --legacy
```

## Tearing down

```bash
# Stop the Sei relay
Ctrl-C

# Stop the Hedera node
cd clpr-hiero/hedera-node/yahcli/scripts/clpre2e
./start-hiero-local.sh down

# Stop Sei
./start-sei-local.sh down

# Stop Besu
cd clpr-smart-contracts && npm run e2e:down
```

## Scripts in this directory

|            Script             |                                                                             What it does                                                                              |
|-------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `bridge-from-sei.sh`          | Bridge a Sei-deployed channel onto Hiero via yahcli using the native Sei verifier system contract (`0.0.368`).                                                        |
| `deploy-sei-clpr.sh`          | Deploy CLPR contracts to the local Sei EVM endpoint, open a channel, register a connector, and queue a demo message.                                                  |
| `run-sei-relay-from-state.sh` | Generate a local `relay.yaml` from the Sei deployment state and run `clpr-evm-endpoint` with `ProofType=CometBFT`.                                                    |
| `start-hiero-local.sh`        | Start or stop one local Hiero node with `./gradlew :app:run` by default, auto-using local TSS wraps when present and cleaning up stale HAPI port listeners on `down`. |
| `start-sei-local.sh`          | Start or stop one local Sei node in Docker with memory-focused defaults and the correct Docker platform for the host.                                                 |
