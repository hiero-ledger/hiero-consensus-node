NAMESPACE=$1
NODE_ROOT=/opt/hgcapp/services-hedera/HapiApp2.0
LOG_DIR=$NODE_ROOT/output


NofNodes=`kubectl -n ${NAMESPACE} get pods | grep 'network-node' | wc -l`

for i in `seq 1 1 $NofNodes`
do
  kubectl -n ${NAMESPACE} exec -it network-node${i}-0 -c root-container -- bash -c "find $LOG_DIR -type f -name '*.log' -exec truncate -c -s 0 {} \;"
done
