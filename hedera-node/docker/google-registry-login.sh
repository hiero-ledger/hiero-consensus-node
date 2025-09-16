#!/usr/bin/env bash

if [ $# -lt 1 ]; then
  echo "USAGE: $0 <KEY>"
  exit 1
fi

cat <<< '$1' | docker login -u _json_key --password-stdin https://gcr.io