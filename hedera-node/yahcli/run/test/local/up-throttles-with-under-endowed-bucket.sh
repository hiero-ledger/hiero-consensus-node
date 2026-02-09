#! /bin/sh

TAG=${1:-'0.1.0'}

set +e

cp localhost/sysfiles/throttles.json throttles.json.bkup
cp run/test/local/assets/throttles-with-under-endowed-bucket.json \
  localhost/sysfiles/throttles.json

docker run -v $(pwd):/launch yahcli:$TAG -p 2 \
  sysfiles upload throttles

echo "RC=$?"

mv throttles.json.bkup localhost/sysfiles/throttles.json
