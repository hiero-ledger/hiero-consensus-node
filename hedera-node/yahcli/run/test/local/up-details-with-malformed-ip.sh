#! /bin/sh

TAG=${1:-'0.1.0'}

set +e

cp localhost/sysfiles/nodeDetails.json nodeDetails.json.bkup
cp run/test/local/assets/details-with-malformed-ip.json \
  localhost/sysfiles/nodeDetails.json

docker run -v $(pwd):/launch yahcli:$TAG -p 2 \
  sysfiles upload node-details

mv nodeDetails.json.bkup localhost/sysfiles/nodeDetails.json

cat syserr.log | head -5
