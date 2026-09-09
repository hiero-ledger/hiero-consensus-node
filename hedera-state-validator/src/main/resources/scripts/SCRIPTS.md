## Replay transactions with State Operator

1. Specify script parameters. You can either set them as environment variables or update the script.
```shell
ORIGIN_ROUND=211155071  
TARGET_ROUND=211422945
JAR=./hedera-state-validator-0.77.jar
BLOCK_STREAM_DIR="${BLOCK_STREAM_DIR:-gs://hedera-preview-testnet-streams/block-preview/previewnet-2026-07-22T22:19/0/0}"
#for local setup: BLOCK_STREAM_DIR=state-validator-blocks-253689003-to-254469719-rna26-s0"
BILLING_PROJECT-hedera-regression
SNAPSHOT_BUCKET=gs://preview-testnet-backup/previewnet-node00
BLOCK_OUTPUT_DIR=/opt/hgcapp/blockStreams/block-0.0.3
NODE_ID=0
OUT=./out

```
2. Run `./validate-replay.sh`

The script will execute the following steps:
  1. Download state snapshots from GCP (skipped if already present locally)
  2. Reconstruct PCES files from block stream (blocks-to-pces)
  3. Replay PCES through a real platform node (replay-pces)
  4. Apply resulting blocks back to the origin state (apply-blocks)
  5. Diff the resulting state against the expected state (diff)


## Replay transactions with Solo setup

### Prerequisites 
1. Download an origin round and target round
```shell
 gcloud storage cp --recursive gs://testnet-2024-02-state-backups/testnet-2023-01-node00/213726472 .
 gcloud storage cp --recursive   gs://testnet-2024-02-state-backups/testnet-2023-01-node00/214806705  .
```
2. Download State Operator
```
gcloud storage cp gs://hedera-ci-ephemeral-artifacts/hedera/hedera-state-validator/hedera-state-validator-0.77.jar .
```

3. Prepare PCES files: 
```shell
java -jar ./hedera-state-validator-0.77.jar blocks-to-pces --block-stream-dir gs://hedera-testnet-streams-2024-02/block-preview/testnet-2024-12-03T17:27/0/0 --origin-round 213726472 --target-round 214806705  --out ./out   --billing-project hedera-regression
```

4. Prepare snapshot for ConsensusNode, a snapshot.zip file including two directories:
```
\com.hedera.services.ServicesMain\<node id>\123\<origin round>\ 
\preconsensus-events\<node_id>\<YYYY>\<MM>\<DD>\ 
``` 

`<origin round>` directory must contain origin round files downloaded previously
`\preconsensus-events\<node_id>\<YYYY>\<MM>\<DD>\  directory must contain PCES files generated previously

5. Checkout `validator_0_77` and build it (run `gradlew assemble`). This workspace will be used later by the deploy script. 
Specifically, it will define values of `LOCAL_BUILD_PATH`, `CUSTOM_PROPERTY_FILE`, and `CUSTOM_SETTINGS_FILE`. 
6. Install Solo and its prerequisites (see [Solo Quckstart](https://solo.hiero.org/docs/simple-solo-setup/quickstart/))

### Replay transactions

Make sure that the configuratiion of the script is correct in the `Configuration` section.
Run [`deploy-solo.sh`](./deploy-solo.sh). 

This script will do the following:

1. Initialize Solo, create and configure a cluster with 1 Consensus Node, 1 Block Nonde, and 1 Mirror Node.
2. Deploy a Block Node with the information about the first block to use. 
3. Deploy a Consensus Node with custom settings and properties. 
4. Start the Consensus Node with the snapshot.zip file prepared previously. It will start with the round the snapshot was 
created for and apply PCES files included in the snapshot.
5. Deploy a Mirror Node

If you need to do the clean up, run [`destroy-solo.sh`](./destroy-solo.sh). This script will stop and remove all Solo containers and delete the Solo cluster.