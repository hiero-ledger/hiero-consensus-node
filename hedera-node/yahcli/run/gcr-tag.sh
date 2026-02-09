#! /bin/sh
if [ $# -lt 1 ]; then
  echo "USAGE: $0 <local tag>"
  exit 1
fi

TAG=$1
docker tag yahcli:$TAG gcr.io/hedera-registry/yahcli:$TAG
