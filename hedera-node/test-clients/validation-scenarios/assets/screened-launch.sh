#! /bin/sh
java -jar /opt/bin/ValidationScenarios.jar "$@" 2>syserr.log
RC=$?
  cat syserr.log
exit $RC
