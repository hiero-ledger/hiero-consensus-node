#! /bin/sh
set -x
if [ $# -lt 1 ]; then
  echo "USAGE: $0 [source.sol]"
  exit 1
fi
CONTRACT=${1%%.*}
solcjs --bin --abi -o ../bytecodes/ $1
BASE_PATH="../bytecodes/${CONTRACT}_sol_${CONTRACT}"
echo $BASE_PATH
mv ${BASE_PATH}.bin ../bytecodes/${CONTRACT}.bin
if [ "-abi" = "$2" ]; then
  cat ${BASE_PATH}.abi | python -m json.tool | less
fi
