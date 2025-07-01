NAMESPACE=$1
NODE_ROOT=/opt/hgcapp/services-hedera/HapiApp2.0
LOG_DIR=$NODE_ROOT/output
STATS_DIR=$NODE_ROOT/data/stats
CONFIG_DIR=$NODE_ROOT/data/config

TOOLDIR=`dirname $0`

mkdir podlog_${NAMESPACE}

NofNodes=`kubectl -n ${NAMESPACE} get pods | grep 'network-node' | wc -l`

for i in `seq 1 1 $NofNodes`
do
  sh ${TOOLDIR}/kubectlt -n ${NAMESPACE} exec -it network-node${i}-0 -c root-container -- \
  bash -c "grep -h -i -E 'error|exception|warn' $LOG_DIR/*.log |grep -v -E 'error-|-error|exception-|-exception|exception[\=]' | grep -v 'The most likely causes'" > podlog_${NAMESPACE}/podlog_${NAMESPACE}_network-node${i}-errors.log
done

cat podlog_${NAMESPACE}/podlog_${NAMESPACE}_network-node*-errors.log | grep -v -E 'exception[\=]null' |\
perl -ne 'if (/^\d{4}[\-]\d{2}[\-]\d{2}\s+\d{2}[\:]\d{2}[\:]\d{2}[\.]\d+\s+\d+\s+(.*)$/) {print "$1\n";} else {print;}' | perl -pne '~s/\d+/N/g' |sort | uniq -c | sort -n -k 1 -r |\
grep -v 'Report[\[]' | grep -v 'contracts.evm.chargeGasOnEvmHandleException [\=] true' > podlog_${NAMESPACE}/error_summary.txt


for i in `seq 1 1 $NofNodes`
do
  mkdir podlog_${NAMESPACE}/network-node${i}_logs
  mkdir podlog_${NAMESPACE}/network-node${i}_logs/stats
  mkdir podlog_${NAMESPACE}/network-node${i}_logs/config


  sh ${TOOLDIR}/kubectlt -n ${NAMESPACE} exec -it network-node${i}-0 -c root-container -- bash -c "journalctl" > podlog_${NAMESPACE}/network-node${i}_logs/journalctl.log

  sh ${TOOLDIR}/kubectlt -n ${NAMESPACE} exec -it network-node${i}-0 -c root-container -- \
  bash -c "cd $NODE_ROOT; cp gc.log* ./output/"

  sh ${TOOLDIR}/kubectlt -n ${NAMESPACE} cp -c root-container network-node${i}-0:$LOG_DIR podlog_${NAMESPACE}/network-node${i}_logs
  sh ${TOOLDIR}/kubectlt -n ${NAMESPACE} cp -c root-container network-node${i}-0:$STATS_DIR podlog_${NAMESPACE}/network-node${i}_logs/stats
  sh ${TOOLDIR}/kubectlt -n ${NAMESPACE} cp -c root-container network-node${i}-0:$CONFIG_DIR podlog_${NAMESPACE}/network-node${i}_logs/config
  sh ${TOOLDIR}/kubectlt -n ${NAMESPACE} cp -c root-container network-node${i}-0:$NODE_ROOT/config.txt podlog_${NAMESPACE}/network-node${i}_logs/config/config.txt
  sh ${TOOLDIR}/kubectlt -n ${NAMESPACE} cp -c root-container network-node${i}-0:$NODE_ROOT/settingsUsed.txt podlog_${NAMESPACE}/network-node${i}_logs/config/settingsUsed.txt

done
