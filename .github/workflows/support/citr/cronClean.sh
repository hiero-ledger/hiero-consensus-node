NAMESPACE=$1
NODE_ROOT=/opt/hgcapp


NofNodes=`kubectl -n ${NAMESPACE} get pods | grep 'network-node' | wc -l`

for i in `seq 1 1 $NofNodes`
do
  kubectl -n ${NAMESPACE} exec network-node${i}-0 -c root-container -- bash -c "find $NODE_ROOT/*Streams/*/ -type f -mmin +59 -exec rm -f {} \;" >/dev/null 2>&1 &
done

wait

minioIP=`kubectl -n ${NAMESPACE} describe pod minio-pool-1-0 | grep -E '^IP[\:]' | awk '{print $NF}'`
nlgpod=`kubectl -n ${NAMESPACE} get pods | grep nlg-network-load-generator | awk '{print $1}'`


kubectl -n ${NAMESPACE} get secret minio-secrets -o yaml | grep 'config.env' | awk '{print $NF}' | base64 --decode >/tmp/.$$.tmp.s
username=`grep MINIO_ROOT_USER /tmp/.$$.tmp.s | awk -F = '{print $NF}'`
userpwd=`grep MINIO_ROOT_PASSWORD /tmp/.$$.tmp.s | awk -F = '{print $NF}'`

kubectl -n ${NAMESPACE} exec $nlgpod -- bash -c "if [ ! -f ./mc ]; then curl https://dl.min.io/client/mc/release/linux-amd64/mc -o mc; fi"
kubectl -n ${NAMESPACE} exec $nlgpod -- bash -c "chmod a+rx ./mc"
kubectl -n ${NAMESPACE} exec $nlgpod -- bash -c "./mc alias set myminio http://$minioIP:9000 $username $userpwd"
kubectl -n ${NAMESPACE} exec $nlgpod -- bash -c "./mc rm --older-than 0d1h0s --recursive --force myminio/solo-streams/ >/dev/null 2>&1"
kubectl -n ${NAMESPACE} exec $nlgpod -- bash -c "./mc rm --older-than 0d1h0s --recursive --force myminio/solo-backups/ >/dev/null 2>&1"
