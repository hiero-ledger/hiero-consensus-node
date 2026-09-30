export LC_ALL=C.UTF-8
export LANG=en_US.UTF-8
export TERM=xterm
asyncmode=$1
format=$2

if [ "$format" == "flamegraph" ]
then
  extension="html"
fi

sysctl kernel.perf_event_paranoid=1; sysctl kernel.kptr_restrict=0
rm -rf /tmp/asprof*.* >/dev/null 2>&1

cd /opt/hgcapp/services-hedera/HapiApp2.0/output/

startfreeze=`date +%s`
#start AsyncProf
stepname=freeze
jpid=`ps -aef | grep -w java | grep 'java ' | grep -v grep | tr -s ' ' | cut -d ' ' -f2`
/tmp/async-profiler/bin/asprof "start" -t -e ${asyncmode} -o ${format} $jpid

perl -ne 'exit 11 if /Now in FREEZE_COMPLETE/' swirlds.log
while [ "$?" != "11" ]
do
 sleep 0.1s
 perl -ne 'exit 11 if /Now in FREEZE_COMPLETE/' swirlds.log
 if [ "$?" == "11" ]
 then
   break
 fi
done
endfreeze=`date +%s`
#end AsyncProf
/tmp/async-profiler/bin/asprof "stop" -t -o ${format} -f /tmp/asprof_${asyncmode}_${stepname}.${extension} \
-I '*virtual-*' -I '*StateSnapshotManager*' -I '*TransactionHandler*' \
-X '*ForkJoinPool*' -X '*ForkJoinThread*' -X '*epollEventLoopGroup*' -X '*RuntimeWorker*' -X '*ZWorker*' -X '*SyncProtocolWith*' $jpid


#start AsyncProf
stepname=down
jpid=`ps -aef | grep -w java | grep 'java ' | grep -v grep | tr -s ' ' | cut -d ' ' -f2`
startdown=`date +%s`

echo "Started shutdown"
/package/admin/s6/command/s6-svc -d /run/service/network-node

/tmp/async-profiler/bin/asprof "start" --timeout 5 -t -e ${asyncmode} -o ${format} \
-f /tmp/asprof_${asyncmode}_${stepname}.${extension} $jpid
#-I '*virtual-*' -I '*StateSnapshotManager*' -I '*TransactionHandler*' \
#-X '*ForkJoinPool*' -X '*ForkJoinThread*' -X '*epollEventLoopGroup*' -X '*RuntimeWorker*' -X '*ZWorker*' -X '*SyncProtocolWith*' $jpid

ps -aef | /usr/bin/grep -w java | /usr/bin/grep -v grep | /usr/bin/grep -w java >/dev/null
while [ "$?" == "0" ]
do
 echo "Still shutting down..."
 sleep 0.1s
 ps -aef | /usr/bin/grep -w java | /usr/bin/grep -v grep | /usr/bin/grep -w java >/dev/null
done

echo "Finished shutdown"
ps -aef | /usr/bin/grep -w java | /usr/bin/grep -v grep

downtime=`date +%s`

cp swirlds.log swirlds_freeze.log
truncate --size 1 swirlds.log

start=`date +%s`
echo "Starting CN..."

/package/admin/s6/command/s6-svc -u /run/service/network-node
#Workaround
sleep 0.1s
/package/admin/s6/command/s6-svc -u /run/service/network-node

ps -aef | /usr/bin/grep -w java | /usr/bin/grep -v grep | /usr/bin/grep -w java >/dev/null
while [ "$?" != "0" ]
do
 sleep 0.1s
 ps -aef | /usr/bin/grep -w java | /usr/bin/grep -v grep | /usr/bin/grep -w java >/dev/null
done

#start AsyncProf
echo "Adding profiling..."

sleep 5
stepname=start
jpid=`ps -aef | grep -w java | grep 'java ' | grep -v grep | tr -s ' ' | cut -d ' ' -f2`
/tmp/async-profiler/bin/asprof "start" -t -e ${asyncmode} -o ${format} $jpid

#cat swirlds.log | /usr/bin/grep -a 'Now in ACTIVE'
#>/dev/null
perl -ne 'exit 11 if /Now in ACTIVE/' swirlds.log
while [ "$?" != "11" ]
do
    sleep 0.1s
    #cat swirlds.log | /usr/bin/grep -a 'Now in ACTIVE'
    perl -ne 'exit 11 if /Now in ACTIVE/' swirlds.log
    if [ "$?" == "11" ]
    then
      break
    fi
done
finish=`date +%s`
#end AsyncProf
/tmp/async-profiler/bin/asprof "stop" -t -o ${format} -f /tmp/asprof_${asyncmode}_${stepname}.${extension} \
-I '*virtual-*' -I '*StateSnapshotManager*' -I '*TransactionHandler*' \
-X '*ForkJoinPool*' -X '*ForkJoinThread*' -X '*epollEventLoopGroup*' -X '*RuntimeWorker*' -X '*ZWorker*' -X '*SyncProtocolWith*' $jpid

freezeduration=$(expr $endfreeze - $startfreeze)
downduration=$(expr $downtime - $startdown)
startduration=$(expr $finish - $start)

echo "Summary: freeze duration=$freezeduration, shutdown duration=$downduration, start duration=$startduration"
cd /tmp/
tar cf /tmp/asprofreport.tar asprof_*
