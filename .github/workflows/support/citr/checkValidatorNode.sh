node_id=$1

ps -aef | grep -w stateAnalyzer | grep -v grep | grep stateAnalyzer >/dev/null
if [ $? -eq 0 ]
then
  echo "Previous Validator is still running on ${node_id}"
  exit 0
fi

cd /opt/hgcapp/services-hedera/HapiApp2.0/data/saved/validation.tmp
currentRound=`ls -1t ../com.hedera.services.ServicesMain/${node_id}/123/ | head -n 1`

if [ -f round_${currentRound}.log ]
then
  newestRound=`ls -1t ../com.hedera.services.ServicesMain/${node_id}/123/ | head -n 1`
  while [ $newestRound -eq $currentRound ]
  do
      sleep 60
      newestRound=`ls -1t ../com.hedera.services.ServicesMain/${node_id}/123/ | head -n 1`
  done
  currentRound=$newestRound
fi
java -Xms16g -Xmx64g -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:ZAllocationSpikeTolerance=2 -XX:ConcGCThreads=14 \
     -XX:ZMarkStackSpaceLimit=16g -XX:MaxDirectMemorySize=32g -XX:NativeMemoryTracking=detail -XX:MetaspaceSize=100M \
     -XX:+ZGenerational -Dthread.num=16 \
     -jar /tmp/hedera-state-validator-*-all.jar ../com.hedera.services.ServicesMain/${node_id}/123/${currentRound} \
     validate rehash stateAnalyzer account tokenRelations internal leaf > round_${currentRound}.log 2>&1
result=$?
if [ $result -eq 0 ]
then
  echo "Node: $node_id validation of round ${currentRound} is OK"
  grep -i -E 'time.* taken' round_${currentRound}.log validator.log
  rm -rf data report.json round_${currentRound}.log slack_report.json validator.log
else
  echo "Node: $node_id validation of round ${currentRound} failed"
  mkdir ${currentRound}.failed
  mv data report.json round_${currentRound}.log slack_report.json validator.log ${currentRound}.failed/
fi
exit $result
