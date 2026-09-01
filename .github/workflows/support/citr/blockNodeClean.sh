NAMESPACE=$1
# Optional second argument. "once" runs a single pruning pass and returns; anything else (including
# no argument, which is how 831/832 invoke this) keeps the original hourly loop.
MODE=$2
LIVEDATA_ROOT=/opt/hiero/block-node/data/live

while [ true ]
do

  for pod in `kubectl -n ${NAMESPACE} get pods | grep 'block-node' | awk '{print $1}'`
  do
    kubectl -n ${NAMESPACE} exec ${pod} -- bash -c "find $LIVEDATA_ROOT/ -type f -name '*.blk*' -mmin +59 -exec rm -f {} \;" >/dev/null 2>&1 &
  done

  wait

  if [ "${MODE}" = "once" ]
  then
    break
  fi

  sleep 3600
done