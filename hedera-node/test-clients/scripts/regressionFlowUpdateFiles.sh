#!/usr/bin/env bash

# script to be called by platform regression scrip to build new jar for update feature test
#
#
set -eE

updateServiceMainJava()
{
    rm -rf updateFiles

    if [[ -n "${CI}" ]]; then
        MVN_OPTION="--no-transfer-progress"
    fi

    # rebuild all jar files and use timestamp to tell which jar files have been updated
    cd ..
    mvn -T 2C $MVN_OPTION install -DskipTests

    # replace a line in ServicesMain.java
    sed -i -e s/'init finished'/'new version jar'/g  hedera-node/src/main/java/com/hedera/services/ServicesMain.java
    beforeTime=`date +'%Y-%m-%d %H:%M:%S'`

    cd hedera-node/ # only build service

    sleep 1
    mvn -T 2C $MVN_OPTION install -DskipTests
    sleep 1
    afterTime=`date +'%Y-%m-%d %H:%M:%S'`

    echo "beforeTime $beforeTime"
    echo "afterTime $afterTime"

    if [[ -n "${CI}" ]]; then
        echo "Installing rsync"
        sudo apt update; sudo apt --assume-yes install rsync
    fi

    # only copy updated jar files to target directory
    TARGET_DIR=../test-clients/updateFiles
    rm -rf $TARGET_DIR
    mkdir -p $TARGET_DIR

    # search all changed jar files and copy to target directory, keeping the old directory hierarchy
    find . -type f -name "H*.jar" -newermt "$beforeTime" -exec rsync  -R {} $TARGET_DIR \;

    if [[ -n "${CI}" ]]; then
        echo "Running on CIRCLECI, no need to restore"
    else
        echo "Restore source code and jar files"
        git checkout src/main/java/com/hedera/services/ServicesMain.java

        # rebuild after checkout to recover binary
        mvn -T 2C $MVN_OPTION install -DskipTests
    fi

    echo "Update files after build have been copied to $TARGET_DIR"

    ls -ltr $TARGET_DIR
    cd ../test-clients

}

updateConfig(){
  TARGET_DIR=../test-clients/updateFiles
  rm -rf $TARGET_DIR
  mkdir -p $TARGET_DIR

  # copy the new config.txt which is already created by platform JRS with update nodes in it to updateFiles directory
  cp "$SRC_CONFIG_PATH/config.txt" $TARGET_DIR
  echo "Update files after build have been copied to $TARGET_DIR"

  ls -ltr $TARGET_DIR
  cd ../test-clients
}

SRC_CONFIG_PATH=""
if [[ $1 == 'updateNode' ]]; then
  echo "Check if updateNode Test $1"
  echo "Hedera repo path $2"
  SRC_CONFIG_PATH=$2
  updateConfig
else
  updateServiceMainJava
fi
