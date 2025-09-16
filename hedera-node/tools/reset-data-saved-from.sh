#! /bin/sh
if [ $# -lt 2 ]; then
  echo "USAGE: $0 <source state dir> <source round> [<# nodes>]"
  exit 1
fi


FROM_DIR=$1
ROUND=$2
NUM_NODES=${3:-'5'}

rm -rf hedera-node/data/saved
NODE=0
while [ $NODE -lt $NUM_NODES ]; do
  # NODE_DIR="hedera-node/data/saved/com.hedera.services.ServicesMain/$NODE/123/$ROUND/"
  NODE_DIR="hedera-node/data/saved/com.hedera.node.app.service.mono.ServicesMain/$NODE/123/$ROUND/"
  mkdir -p $NODE_DIR
  cp -R $FROM_DIR/* $NODE_DIR
  NODE=$((NODE+1))
done
