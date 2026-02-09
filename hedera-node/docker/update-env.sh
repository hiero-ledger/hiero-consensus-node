#!/usr/bin/env bash

# This scripts create a '.env' file that is used for docker & docker-compose as an input of environment variables.
# This script is called by gradle and get the current project version as an input param

if [ $# -lt 1 ]; then
  echo "USAGE: $0 <TAG>"
  exit 1
fi

echo "TAG=$1" > .env
echo "REGISTRY_PREFIX=" >> .env
echo "VERSION/TAG UPDATED TO $1"