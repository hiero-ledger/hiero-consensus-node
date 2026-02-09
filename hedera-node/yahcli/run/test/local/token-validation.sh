#! /bin/sh

TAG=${1:-'0.1.1'}

docker run -v $(pwd):/launch yahcli:$TAG -p 2 \
  validate token
