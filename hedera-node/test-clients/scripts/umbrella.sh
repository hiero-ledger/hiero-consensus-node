#! /bin/sh
if [ $# -lt 2 ]; then
  echo "USAGE: $0 host default_node [property_suffix=1k]"
  exit 1
fi
source run/functions.sh
HOST=$1
DEFAULT_NODE=$2
CONFIG_SUFFIX=${3:-1k}
mv config/umbrellaTest.properties tmp/
cp config/umbrellaTest.properties.alt$CONFIG_SUFFIX \
	config/umbrellaTest.properties
mvnExec regression.umbrella.UmbrellaTest $HOST $DEFAULT_NODE
mv tmp/umbrellaTest.properties config/
