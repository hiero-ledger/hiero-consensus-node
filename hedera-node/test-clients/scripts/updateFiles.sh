
#!/usr/bin/env bash

# script to test update feature on local macOS
#
# Steps
#   from directory hedera-node
#   launch HGCApp
#
#   run test to update jar files
#       ./scripts/updateFiles.sh updateFiles/
#
#   run test to update settings.txt
#       ./scripts/updateFiles.sh
#
#
set -eE

OLD_MARKER="init finished"
NEW_MARKER="new version jar"

function updateServiceMainJava
{
    # replace a line in ServicesMain.java
    sed -i -e s/'init finished'/'new version jar'/g  ../hedera-node/src/main/java/com/hedera/services/ServicesMain.java

    # rebuild jar files and use timestamp to tell which jar files have been updated
    cd ../hedera-node
    beforeTime=`date +'%Y-%m-%d %H:%M:%S'`
    sleep 1
    mvn install -DskipTests
    sleep 1
    afterTime=`date +'%Y-%m-%d %H:%M:%S'`

    echo "beforeTime $beforeTime"
    echo "afterTime $afterTime"

    # only copy updated jar files to target directory
    TARGET_DIR=../test-clients/updateFiles
    rm -rf $TARGET_DIR
    mkdir -p $TARGET_DIR
    find . -type f -name "H*.jar" -newermt "$beforeTime" -exec rsync -R {} $TARGET_DIR \;


    git checkout ../hedera-node/src/main/java/com/hedera/services/ServicesMain.java

    # rebuild after checkout to recover binary
    mvn install -DskipTests

    cd -

    echo "Update files after build have been copied to $TARGET_DIR"

    ls -ltr $TARGET_DIR

}

#
# check the hgcaa.log of newly started service
# whether it contains the expected new marker sentence
#
# Arguments
#   $1 searching string
#   $2 log name
#
function checkUpdateResult
{
    if grep -q "$1" $2; then
        echo "SUCCEED: Found expected string ($1) in $2"
    else
        echo "FAIL: Not found expected string ($1) in $2"
        exit 164
    fi
}

if [[ -n $1 ]]; then
    echo "---------- run test to update jar files -----------"
     checkUpdateResult "$OLD_MARKER" ../hedera-node/output/hgcaa.log

    #run client test before freeze
    mvn exec:java -Dexec.mainClass=com.hedera.services.bdd.suites.crypto.CryptoCreateSuite -Dexec.cleanupDaemonThreads=false

    updateServiceMainJava

    newFileDir=$1
    targeFileID=$2

    mvn exec:java -Dexec.mainClass=com.hedera.services.bdd.suites.freeze.UpdateServerFiles -Dexec.args="$newFileDir $targeFileID" -Dexec.cleanupDaemonThreads=false

    echo "sleep to wait hgc server freeze then restart"
    sleep 180

    #run client test after freeze
    mvn exec:java -Dexec.mainClass=com.hedera.services.bdd.suites.crypto.CryptoTransferSuite -Dexec.cleanupDaemonThreads=false

    checkUpdateResult "$NEW_MARKER" ../HapiApp2.0/output/hgcaa.log

else
    echo "---------- run test to update settings.txt -----------"

    #run client test before freeze
    mvn exec:java -Dexec.mainClass=com.hedera.services.bdd.suites.crypto.CryptoCreateSuite -Dexec.cleanupDaemonThreads=false

    mvn exec:java -Dexec.mainClass=com.hedera.services.bdd.suites.freeze.FreezeSuite -Dexec.cleanupDaemonThreads=false
    echo "sleep to wait hgc server freeze then restart"
    sleep 120

    #run client test after freeze
    mvn exec:java -Dexec.mainClass=com.hedera.services.bdd.suites.crypto.CryptoTransferSuite -Dexec.cleanupDaemonThreads=false

    checkUpdateResult "ERROR: fakeParameter is not a valid setting name" ../HapiApp2.0/output/swirlds.log

    # recover settings.txt
    git checkout ../hedera-node/settings.txt
fi
