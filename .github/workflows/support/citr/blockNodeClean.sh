NAMESPACE=$1
LIVEDATA_ROOT=/opt/hiero/block-node/data/live

while [ true ]
do

  for pod in `kubectl -n ${NAMESPACE} get pods | grep 'block-node' | awk '{print $1}'`
  do
    kubectl -n ${NAMESPACE} exec ${pod} -- bash -c "find $LIVEDATA_ROOT/ -type f -name '*.blk*' -mmin +59 -exec rm -f {} \;" >/dev/null 2>&1 &
  done

  wait
  sleep 3600
done