namespace=${1}
TOOLDIR=$(dirname ${0})

rm -rf roles/hedera-docker/files/keys-signing/latitude roles/hedera-docker/files/keys-tls/latitude >/dev/null 2>&1

mkdir -p roles/hedera-docker/files/keys-signing/latitude
mkdir -p roles/hedera-docker/files/keys-tls/latitude
mkdir inventory

NofNodes=$(sh ${TOOLDIR}/../kubectlt -n ${namespace} get pods | grep 'network-node' | wc -l)

cat << EOF > inventory/latitude.yml
network:
  vars:
    stake: 10
    node_profile: docker
    nextNodeId: 7
  hosts:
EOF

for i in $(seq 1 1 ${NofNodes})
do
  node_id=$(expr ${i} - 1)
  acc=$(expr ${node_id} + 3)

  sh ${TOOLDIR}/../kubectlt -n ${namespace} cp network-node${i}-0:/opt/hgcapp/services-hedera/HapiApp2.0/data/keys/ roles/hedera-docker/files/keys-signing/latitude/
  sh ${TOOLDIR}/../kubectlt -n ${namespace} cp network-node${i}-0:/opt/hgcapp/services-hedera/HapiApp2.0/hedera.crt roles/hedera-docker/files/keys-tls/latitude/node${node_id}.crt

  ip=$(sh ${TOOLDIR}/../kubectlt -n ${namespace} get svc | grep NodePort | grep "network-node${i}" | awk '{print $3}')
cat << EOF >> inventory/latitude.yml
    node0${node_id}:
      ansible_host: ${ip}
      NODE_ID: 0.0.${acc}
      NODE_NUM: ${node_id}
EOF
done

ls -l roles/hedera-docker/files/keys-signing/latitude/

cat << EOF >> inventory/latitude.yml
proxy:
  children:
    gcp_proxy:
      hosts:
EOF

for i in $(seq 1 1 ${NofNodes})
do
  node_id=$(expr ${i} - 1)
  acc=$(expr ${node_id} + 3)
  ip=$(sh ${TOOLDIR}/../kubectlt -n ${namespace} get svc | grep NodePort | grep "network-node${i}" | awk '{print $3}')

cat << EOF >> inventory/latitude.yml
        proxy0${node_id}_gcp:
          target_node: node0${node_id}
          ansible_host: ${ip}

EOF
done
