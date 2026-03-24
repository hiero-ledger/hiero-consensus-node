node_id=${1}
isToClean=${2}
export MALLOC_ARENA_MAX=4

APP_HOME=/opt/hgcapp/services-hedera/HapiApp2.0

if [[ "${isToClean}" == "clean" ]]
then
  echo "Cleaning old data ..."

  cd ${APP_HOME}
  rm -rf data/saved/saved/*
  rm -rf data/saved/swirlds-tmp/*
  rm -rf data/saved/preconsensus-events/*/*
  rm -rf /opt/hgcapp/*Streams/*
  rm -rf output/*
  rm -rf data/saved/com.hedera.services.ServicesMain/${node_id}/123/*
fi

if [[ "${isToClean}" == "cobertura" ]]
then
  echo "Cleaning old data ..."

  cd "${APP_HOME}"
  rm -rf data/saved/saved/*
  rm -rf data/saved/swirlds-tmp/*
  rm -rf data/saved/preconsensus-events/*/*
  rm -rf /opt/hgcapp/*Streams/*
  rm -rf output/*
  rm -rf data/saved/com.hedera.services.ServicesMain/${node_id}/123/*

  echo "Generate module allowances:";
  echo > module_reads.txt
  java -p $(find data/lib/* -type f -name '*.jar' -printf "%p:") --list-modules |  grep -E '[\.]hiero|[\.]swirlds|[\.]hedera|[\.]pbj|hyperledger[\.]besu' > packs.txt

  for package in `cat packs.txt | awk '{print $1}'`
  do
   file=`grep ${package} packs.txt | awk '{print $2}'| sed -e 's@file://@@g'`
   packagename=`echo ${package} | awk -F @ '{print $1}'`
   unzip -l ${file} | awk '{print $NF}' |  grep '.class' | grep -v 'module-info.class' | sed -e 's/^\(.*\)[\/][^\/][^\/]*.class/\1/g' | sort -u | sed -e 's/[\/]/./g' |\
   perl -ne "~s/\n//g;print \"--add-reads ${packagename}=cobertura --add-opens ${packagename}/\$_=cobertura --add-exports $packagename/\$_=cobertura \"" >>module_reads.txt
  done
  export DISABLE_JDK_SERIAL_FILTER=true
  export EXTRA_COBERTURA_OPTS="--add-modules cobertura --add-reads cobertura=ALL-UNNAMED --add-opens cobertura/net.sourceforge.cobertura=ALL-UNNAMED  --add-modules org.slf4j --add-reads cobertura=org.slf4j --add-reads org.slf4j=cobertura $(cat module_reads.txt)"
fi

if [[ "${isToClean}" == "import" ]]
then
  echo "Prepare for import ..."

  cd "${APP_HOME}"
  rm -rf output/*
  ls -lt "data/saved/com.hedera.services.ServicesMain/${node_id}/123"
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

cd ${APP_HOME}
nohup /usr/bin/env java ${JAVA_HEAP_OPTS} ${JAVA_OPTS} ${EXTRA_COBERTURA_OPTS} -cp "${JAVA_CLASS_PATH}" "${JAVA_MAIN_CLASS}" -local ${node_id} > node.log 2>&1 &
