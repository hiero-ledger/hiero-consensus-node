namespace="$1"
k="kubectl -n $namespace"
nlgpod="$2"
N_of_CNs=$3
profiling_agent=$4
async_profiler=$5
format=$6
echo "Setting..."
for i in `seq 1 1 ${N_of_CNs}`
do
  $k cp $profiling_agent network-node${i}-0:/tmp/profileRestart.sh >/dev/null 2>&1 &
  $k cp $async_profiler network-node${i}-0:/tmp/async-profiler >async-profiler_tmp_${i}.log 2>&1 &
  $k exec -t network-node${i}-0 -- bash -c "ps -aef | grep profileRestart.sh | grep -v grep | awk '{print \$2}' | xargs kill -9 " >/dev/null 2>&1 &
done

wait

echo "Starting ..."

format=$format
$k exec -t network-node1-0 -- bash -c "TERM=xterm /usr/bin/bash /tmp/profileRestart.sh cpu        $format > /tmp/profileRestart.log 2>&1" >/dev/null 2>&1 &
$k exec -t network-node2-0 -- bash -c "TERM=xterm /usr/bin/bash /tmp/profileRestart.sh alloc      $format > /tmp/profileRestart.log 2>&1" >/dev/null 2>&1 &
$k exec -t network-node3-0 -- bash -c "TERM=xterm /usr/bin/bash /tmp/profileRestart.sh nativemem  $format > /tmp/profileRestart.log 2>&1" >/dev/null 2>&1 &
$k exec -t network-node4-0 -- bash -c "TERM=xterm /usr/bin/bash /tmp/profileRestart.sh lock       $format > /tmp/profileRestart.log 2>&1" >/dev/null 2>&1 &
$k exec -t network-node5-0 -- bash -c "TERM=xterm /usr/bin/bash /tmp/profileRestart.sh wall       $format > /tmp/profileRestart.log 2>&1" >/dev/null 2>&1 &
$k exec -t network-node6-0 -- bash -c "TERM=xterm /usr/bin/bash /tmp/profileRestart.sh wall       $format > /tmp/profileRestart.log 2>&1" >/dev/null 2>&1 &
$k exec -t network-node7-0 -- bash -c "TERM=xterm /usr/bin/bash /tmp/profileRestart.sh wall       $format > /tmp/profileRestart.log 2>&1" >/dev/null 2>&1 &

sleep 0.5
$k exec ${nlgpod} -c nlg -- bash -c "/usr/bin/env java -Xmx30g -Dorg.slf4j.simpleLogger.defaultLogLevel=debug -cp /app/lib/*:\$(ls -1 /app/network-load-generator-*.jar) com.hedera.benchmark.Freeze"

echo "Waiting ..."
wait

for i in `seq 1 1 ${N_of_CNs}`
do
  $k exec network-node${i}-0 -c root-container -- bash -c "cat /tmp/profileRestart.log"
done

rm -rf ZDT_report
mkdir ZDT_report
for i in `seq 1 1 ${N_of_CNs}`
do
  $k cp network-node${i}-0:/tmp/asprofreport.tar ./asprofreport_${i}.tar
  cd ZDT_report
  mkdir network-node${i}
  cd network-node${i}
  tar xf ../../asprofreport_${i}.tar
  cd ../..
done

exit


##sleep 60
#wait for FREEZE completion, parallel, and prepared
##exit

for i in `seq 1 1 ${N_of_CNs}`
do
  $k exec network-node${i}-0 -- bash -c "ls -l /opt/hgcapp/services-hedera/HapiApp2.0/data/upgrade/current/now_frozen.mf"
  $k exec network-node${i}-0 -- bash -c "grep PLATFORM_STATUS /opt/hgcapp/services-hedera/HapiApp2.0/output/swirlds.log"
done

exit


for i in `seq 1 1 ${N_of_CNs}`
do

  $k exec network-node${i}-0 -- bash -c "/package/admin/s6/command/s6-svc -d /run/service/network-node"
done

sleep 30

echo "Check no java running:"
for i in `seq 1 1 ${N_of_CNs}`
do
  $k exec network-node${i}-0 -- bash -c "ps -aef | grep java | grep -v grep"
done


for i in `seq 1 1 ${N_of_CNs}`
do
  $k exec network-node${i}-0 -- bash -c "/package/admin/s6/command/s6-svc -u /run/service/network-node"
done