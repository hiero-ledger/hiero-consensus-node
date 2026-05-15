#! /bin/sh
# Silence JDK 25 warnings: protobuf's sun.misc.Unsafe::arrayBaseOffset deprecation
# and netty's System::loadLibrary restricted-method warning.
java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -jar /opt/bin/ValidationScenarios.jar "$@" 2>syserr.log
RC=$?
  cat syserr.log
exit $RC
