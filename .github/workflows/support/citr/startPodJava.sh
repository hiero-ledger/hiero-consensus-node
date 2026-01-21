set +e
set +x

node_id=$1
isToClean=$2
export MALLOC_ARENA_MAX=4

APP_HOME=/opt/hgcapp/services-hedera/HapiApp2.0

if [ "$isToClean" = "clean" ]
then
  echo "Cleaning old data ..."

  cd $APP_HOME
  rm -rf data/saved/saved/*
  rm -rf data/saved/swirlds-tmp/*
  rm -rf data/saved/preconsensus-events/*/*
  rm -rf /opt/hgcapp/*Streams/*
  rm -rf output/*
  rm -rf data/saved/com.hedera.services.ServicesMain/${node_id}/123/*
  cp .archive/config.txt .
  mv data/config/.archive/genesis-network.json data/config/genesis-network.json
  rm -rf .archive
  #cd $APP_HOME/data/keys
  #bash generate.sh node1
fi

if [ "$isToClean" = "cobertura" ]
then
  echo "Cleaning old data ..."

  cd $APP_HOME
  rm -rf data/saved/saved/*
  rm -rf data/saved/swirlds-tmp/*
  rm -rf data/saved/preconsensus-events/*/*
  rm -rf /opt/hgcapp/*Streams/*
  rm -rf output/*
  rm -rf data/saved/com.hedera.services.ServicesMain/${node_id}/123/*
  export PATH=/usr/local/java/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
  cp .archive/config.txt .
  cp data/config/.archive/genesis-network.json data/config/genesis-network.json

  export EXTRA_COBERTURA_OPTS="--illegal-access=warn -Dnet.sourceforge.cobertura.datafile=/tmp/cobertura.ser -Dio.grpc.netty.shaded.io.netty.tryReflectionSetAccessible=true"
fi

if [ "$isToClean" = "import" ]
then
  echo "Prepare for import ..."

  cd $APP_HOME
#  rm -rf data/saved/swirlds-tmp/*
#  rm -rf data/saved/preconsensus-events/*/*
#  rm -rf /opt/hgcapp/*Streams/*
  rm -rf output/*
  #rm -rf /opt/hgcapp/services-hedera/HapiApp2.0/.archive
  ls -lt data/saved/com.hedera.services.ServicesMain/${node_id}/123
  #cd $APP_HOME/data/keys
  #bash generate.sh node1
fi

LANG=C.utf8
APP_HOME=/opt/hgcapp/services-hedera/HapiApp2.0
JAVA_CLASS_PATH=data/lib/*:data/apps/*
JAVA_OPTS="-XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:ZAllocationSpikeTolerance=2 -XX:ConcGCThreads=14 -XX:ZMarkStackSpaceLimit=16g \
-XX:MaxDirectMemorySize=64g \
-XX:MetaspaceSize=100M -XX:+ZGenerational -Xlog:gc*:gc.log \
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED -Dio.netty.tryReflectionSetAccessible=true"
JAVA_HOME=/usr/local/java
USER=hedera
HOME=/home/hedera
JAVA_HEAP_MIN=32g
JAVA_HEAP_MAX=118g
JAVA_HEAP_OPTS="-Xms${JAVA_HEAP_MIN} -Xmx${JAVA_HEAP_MAX}"
JAVA_MAIN_CLASS=com.hedera.node.app.ServicesMain
LOGNAME=hedera
PATH=/usr/local/java/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

cd $APP_HOME
nohup /usr/bin/env java ${JAVA_HEAP_OPTS} ${JAVA_OPTS} ${EXTRA_COBERTURA_OPTS} -cp "${JAVA_CLASS_PATH}" "${JAVA_MAIN_CLASS}" -local ${node_id} > node.log 2>&1 &
