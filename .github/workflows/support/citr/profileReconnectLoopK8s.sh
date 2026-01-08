set +x
set +e

namespace=${1}
downtime=${2}
warmtime=${3}
NofLoops=${4}

TOOLDIR=`dirname ${0}`

LEARNER_POD=network-node1-0
LEARNER_NODEID=0
LEARNER_LOG_DIR=/opt/hgcapp/services-hedera/HapiApp2.0/output
LEARNER_LOG_LOCAL=swirlds.log

k="sh ${TOOLDIR}/kubectlt -n ${namespace}"

counter=0

${k} cp -c root-container ${TOOLDIR}/startPodJava.sh ${LEARNER_POD}:${LEARNER_LOG_DIR}/../

echo "NFT transfer started. Working for warmtime=${warmtime} ..."

sleep ${warmtime}

while [[ ${counter} -lt ${NofLoops} ]]
do
  counter=`expr ${counter} + 1`
  echo "Loop: ${counter}"

  echo "Stopping java"

  ${k} exec ${LEARNER_POD} -c root-container -- su - hedera -c bash -c "ps -aef | grep -w java | grep -v grep | awk '{print \$2}' | xargs kill -15"
  sleep 60
  ${k} exec ${LEARNER_POD} -c root-container -- su - hedera -c "mv ${LEARNER_LOG_DIR}/${LEARNER_LOG_LOCAL} ${LEARNER_LOG_DIR}/swirlds_reconnect_${counter}.log"

  echo "Down for downtime=${downtime} ..."
  sleep ${downtime}

  ${k} exec ${LEARNER_POD} -c root-container -- su - hedera -c bash -c "cd ${LEARNER_LOG_DIR}/../; sh startPodJava.sh ${LEARNER_NODEID}"

  sleep 10
  date
  echo "Waiting for BEHIND|CHECKING state ..."
  ${k} cp -c root-container ${LEARNER_POD}:${LEARNER_LOG_DIR}/${LEARNER_LOG_LOCAL} ${LEARNER_LOG_LOCAL}
  grep -E 'Now in BEHIND|Now in CHECKING' ${LEARNER_LOG_LOCAL} >/dev/null

  while [[ ${?} -ne 0 ]]
  do
    sleep 10
    ${k} cp -c root-container ${LEARNER_POD}:${LEARNER_LOG_DIR}/${LEARNER_LOG_LOCAL} ${LEARNER_LOG_LOCAL}

    grep 'Unknown bucket field' ${LEARNER_LOG_LOCAL}
    if [[ ${?} -eq 0 ]]
    then
      echo "ERROR!!! Unknown bucket field. STOP-ing and exiting.."
      ${k} exec ${LEARNER_POD} -c root-container -- su - hedera -c bash -c "ps -aef | grep -w java | grep -v grep | awk '{print \$2}' | xargs kill -15"
      exit 13
    fi

    grep -E 'Now in BEHIND|Now in CHECKING' ${LEARNER_LOG_LOCAL} >/dev/null
  done
  grep -E 'Now in BEHIND|Now in CHECKING' ${LEARNER_LOG_LOCAL}
  date

  echo "Waiting for ACTIVE state ..."
  ${k} cp -c root-container ${LEARNER_POD}:${LEARNER_LOG_DIR}/${LEARNER_LOG_LOCAL} ${LEARNER_LOG_LOCAL}
  grep 'Now in ACTIVE' ${LEARNER_LOG_LOCAL} >/dev/null
  while [[ ${?} -ne 0 ]]
  do
    sleep 10
    ${k} cp -c root-container ${LEARNER_POD}:${LEARNER_LOG_DIR}/${LEARNER_LOG_LOCAL} ${LEARNER_LOG_LOCAL}

    grep 'Unknown bucket field' ${LEARNER_LOG_LOCAL}
    if [[ ${?} -eq 0 ]]
    then
      echo "ERROR!!! Unknown bucket field. STOP-ing and exiting.."
      ${k} exec ${LEARNER_POD} -c root-container -- su - hedera -c bash -c "ps -aef | grep -w java | grep -v grep | awk '{print \$2}' | xargs kill -15"
      exit 13
    fi
    grep 'Now in ACTIVE' ${LEARNER_LOG_LOCAL} >/dev/null
  done
  grep -E 'INFO.*PLATFORM_STATUS|INFO.*newLastLeafPath' ${LEARNER_LOG_LOCAL}
  grep 'Now in ACTIVE' ${LEARNER_LOG_LOCAL}
  date

  echo "Reconnect N=${counter} done. Working for warmtime=${warmtime} ..."

  sleep ${warmtime}

done
echo "Finished"
