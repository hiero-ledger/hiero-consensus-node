#!/usr/bin/env bash

#
# Sample script of how to update jar files using update feature
#
# Argument
#   $1 node id number
#

set -eE

create_new_directory () {
    # Wait for database being stored during freeze stage then run the script to copy directory
    sleep 60

    # create a new working directory in the format of HapiApp2.0-20200930T143000
    new_directory_name="HapiApp2.0-"`date +%Y%m%dT%H%M%S`
    shell_echo $LINENO $0 "Creating new working directory $new_directory_name"
    mkdir -p ../$new_directory_name

    shell_echo $LINENO $0 "Building symbolic link to $new_directory_name"

    # build symbolic link
    cd ..
    rm -f HapiApp2.0
    ln -s $new_directory_name HapiApp2.0
    cd -

    shell_echo $LINENO $0 "Copying existing file to new working directory $new_directory_name"
    cp -r ./ ../$new_directory_name

    cd ../$new_directory_name

    # direct output to output log of the new directory
    if [ -f $SERVICE_LOG4J2 ]; then
        OUTPUT=../$new_directory_name/hgcaa.log
    else
        OUTPUT=../$new_directory_name/output/hgcaa.log
    fi

}

update_sdk_files () {
    # check if new sdk file directory exist
    if [ -d temp/sdk ]; then
        shell_echo $LINENO $0 "Found new sdk files "
        shell_echo $LINENO $0 "Copying files from temp/sdk to current working directory"
        cp -r temp/sdk/* ./
    else
        shell_echo $LINENO $0 "No new sdk files found "
    fi
}

unamestr=`uname`

NODE_ID=$1

SERVICE_LOG4J2="log4j2-services-regression.xml"
PWD=`pwd`
if [ -f $SERVICE_LOG4J2 ]; then
    OUTPUT=$PWD/hgcaa.log
else
    OUTPUT=$PWD/output/hgcaa.log
fi

#
#  Generate log message to follow log4j2 format
#
#  $0 line number
#  $1 script name
#  $2 message
#
shell_echo() {
    if [[ "$unamestr" == 'Linux' ]]; then
        echo $(date +"%Y-%m-%d %T.%3N") INFO  $1  $2 "- NETWORK_UPDATE Node $NODE_ID" "$3" >> $OUTPUT
    elif [[ "$unamestr" == 'Darwin' ]]; then
        echo $(date +"%Y-%m-%d %T.000") INFO  $1  $2 "- NETWORK_UPDATE Node $NODE_ID" "$3" >> $OUTPUT
    else
        echo $(date) INFO  $1  $2 "- NETWORK_UPDATE Node $NODE_ID" "$3" >> $OUTPUT
    fi
}

# make sure output file log exist otherwise
# cannot continue

if [[ -f $OUTPUT ]]; then
    shell_echo $LINENO $0 "$OUTPUT exists."
else
    echo "ERROR: output $OUTPUT does not exist." >> error.log
    exit
fi

USER=`whoami`
shell_echo $LINENO $0 "Start backgorund bash script"
shell_echo $LINENO $0 "current user is $USER"

# find PID
processId=$(ps -ef | grep 'com.swirlds.platform.Browser' | grep -v 'grep' | awk '{ printf $2 }')
shell_echo $LINENO $0 "HGCApp processID=$processId"

# create new working directory
create_new_directory
update_sdk_files


# detect current platform and restart java process
if [[ "$unamestr" == 'Linux' ]]; then
    # useful set circle ci AWS environment variable
    if [[ -f ~/.bash_profile ]]; then
        source ~/.bash_profile  >> $OUTPUT
    fi
    if [[ -n "${CI_AWS}" ]]; then
        shell_echo $LINENO $0 "Running on CIRCLECI"

        FILE="data/apps/HederaNode.jar"
        RENAME="data/apps/HGCApp.jar"
        # if new files contain HederaNode.jar, rename it to HGCApp.jar
        if [ -f $FILE ]; then
            shell_echo $LINENO $0 "The file HGCApp.jar pre-exist, need rename to $FILE."
            rm $RENAME
            mv $FILE $RENAME
        fi

        # call DevOps script here ?
        shell_echo $LINENO $0 "Restart HGCAPP service"
        sleep 15 # wait platform save database properly
        sudo service hgcapp restart >> $OUTPUT 2>&1

    else
        shell_echo $LINENO $0 "Running on Linux"
        sleep 15 # wait platform save database properly
        kill $processId

        shell_echo $LINENO $0 "Wait for HGCApp to quit"
        sleep 15

        shell_echo $LINENO $0 "Restart HGCApp"

        # running suite test with platform regression flow
        if [ -f $SERVICE_LOG4J2 ]; then
            LOG4j2XML=$SERVICE_LOG4J2
            java -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:ConcGCThreads=14 \
            -XX:+UseLargePages -Xmx98g -Xms10g -XX:ZMarkStackSpaceLimit=16g -XX:MaxDirectMemorySize=32g \
            -XX:MetaspaceSize=100M  -Xlog:gc*:gc.log  -Dlog4j.configurationFile=log4j2-services-regression.xml \
            -cp 'data/lib/*' com.swirlds.platform.Browser >>output.log 2>&1 & disown -h
        else
            java -Dflag=1 -cp swirlds.jar:data/lib/* com.swirlds.platform.Browser
        fi
    fi
elif [[ "$unamestr" == 'Darwin' ]]; then
    shell_echo $LINENO $0 "Running on macOS"
    sleep 15 # wait platform save database properly

    kill $processId

    shell_echo $LINENO $0 "Wait for HGCApp to quit"
    sleep 15

    shell_echo $LINENO $0 "Restart HGCApp"
    java -Dflag=1 -cp swirlds.jar:data/lib/* com.swirlds.platform.Browser
else
    shell_echo $LINENO $0 " untested OS :$platform"
    exit
fi


