#! /bin/sh
# Silence protobuf's UnsafeUtil sun.misc.Unsafe deprecation warnings on JDK 25.
# The netty System.loadLibrary warning is handled via the JAR's Enable-Native-Access manifest.
java --sun-misc-unsafe-memory-access=allow -jar /opt/bin/yahcli.jar "$@" 2>syserr.log
RC=$?
cat syserr.log
exit $RC
