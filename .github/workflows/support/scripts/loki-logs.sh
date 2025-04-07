#!/usr/bin/env bash
#
# Use Loki API to check if all nodes are up and running by searching "ACTIVE" message in swirlds.log

network=${1}
maxnodes=${2:-7}
timegap=${3:-3}
limit=${4:-300}
cmd=${5:-/usr/local/bin/logcli}
pad=${6:-0}

echo "network  = [$network]"
echo "maxnodes = [$maxnodes]"
echo "timegap  = [$timegap]"
echo "limit    = [$limit]"
echo "pad      = [$pad]"

#. env.loki



while true
do

    back=$(( ( timegap + pad ) * 2 ))
    beg=$(date --date="${back} minutes ago" '+%Y-%m-%dT%H:%M:%SZ' --utc)
    end=$(date '+%Y-%m-%dT%H:%M:%SZ' --utc)

    query_result=$(${cmd} query --timezone=UTC --limit=${limit} --from="${beg}" --to="${end}" '{environment="engnet1",log_name="swirlds", node_id=~".*"} |~ `.*newStatus.*:.*ACTIVE.*`')
    nodes=$(${cmd} query --timezone=UTC --limit=${limit} --from="${beg}" --to="${end}" '{environment="engnet1",log_name="swirlds", node_id=~".*"} |~ `.*newStatus.*:.*ACTIVE.*`' | wc -l)

    echo "beg   = [$beg]"
    echo "end   = [$end]"
    echo "back  = [$back]"
    echo "query_result = [$query_result]"
    echo "nodes = [$nodes]"

    if [[ $nodes -ge $maxnodes ]]; then
        break
    fi

    echo `date` "......sleep ${timegap} minutes"
    sleep ${timegap}m

done

echo `date` "Network [$network] started!!!"
