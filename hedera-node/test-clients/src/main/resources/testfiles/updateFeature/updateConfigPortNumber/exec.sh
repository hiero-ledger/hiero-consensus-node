#!/usr/bin/env bash

echo "Wait HGCApp save its lates signed state "
sleep 40

echo "Kill java process first"
rm -f exec.log

# solution 1 find PID
processId=$(ps -ef | grep 'com.swirlds.platform.Browser' | grep -v 'grep' | awk '{ printf $2 }')
echo "HGCApp processID=$processId" >> exec.log
kill $processId

# solution 2 pkill
pkill java

# wait for browser terminate

echo "Sleep to wait HGCApp quit" >> exec.log

sleep 15

echo "Restart HGCApp" >> exec.log

java -Dflag=1 -cp data/lib/swirlds.jar:data/lib/* com.swirlds.platform.Browser
