#! /bin/sh
set -x
if [ $# -lt 1 ]; then
  echo "USAGE: $0 [source.sol]"
  exit 1
fi
CONTRACT=${1%%.*}

# If only solcjs is installed
solcjs --bin --abi -o ../bytecodes/ $1
BASE_PATH="../bytecodes/${CONTRACT}_sol_${CONTRACT}"
mv ${BASE_PATH}.bin ../bytecodes/${CONTRACT}.bin

# RECOMMENDED - after brew install solidity
# solc --via-ir --overwrite --optimize --bin --abi -o ../bytecodes/ $1
