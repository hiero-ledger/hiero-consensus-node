NAMESPACE=$1
BN_LOG=/opt/hiero/block-node/logs
TOOLDIR=`dirname $0`

if [ ! -d  podlog_${NAMESPACE} ]
then
  mkdir podlog_${NAMESPACE}
fi

for i in `sh ${TOOLDIR}/kubectlt -n ${NAMESPACE} get pods | grep 'block-node-' | awk '{print $1}'`
do
  sh ${TOOLDIR}/kubectlt -n ${NAMESPACE} exec -it ${i} -- \
  bash -c "grep -h -i -E 'error|exception|warn|severe' $BN_LOG/*.log" | grep -v error_prone_annotations > podlog_${NAMESPACE}/${i}-errors.log
done

cat podlog_${NAMESPACE}/*block-node*-errors.log | grep -v -E 'exception[\=]null' |\
perl -ne 'if (/^\d{4}[\-]\d{2}[\-]\d{2}\s+\d{2}[\:]\d{2}[\:]\d{2}[\.]\d+\s+\d+\s+(.*)$/) {print "$1\n";} else {print;}' | perl -pne '~s/\d+/N/g' | sort | uniq -c | sort -n -k 1 -r |\
grep -v 'error_prone_annotations' > podlog_${NAMESPACE}/error_summary_blocknodes.txt
log_

for i in `sh ${TOOLDIR}/kubectlt -n ${NAMESPACE} get pods | grep 'block-node-' | awk '{print $1}'`
do
  mkdir podlog_${NAMESPACE}/${i}_logs
  sh ${TOOLDIR}/kubectlt -n ${NAMESPACE} cp ${i}:$BN_LOG podlog_${NAMESPACE}/${i}_logs
done
