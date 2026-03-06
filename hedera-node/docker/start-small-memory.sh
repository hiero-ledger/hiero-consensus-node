#!/usr/bin/env bash
set -euo pipefail

JFR_SETTINGS="data/config/MemoryLowOverhead.jfc"
JFR_FILE="output/recording.jfr"

exec java \
  -cp 'data/lib/*:data/apps/*' \
  -server \
  -Djava.awt.headless=true \
  -Djava.util.concurrent.ForkJoinPool.common.parallelism=2 \
  --limit-modules=java.base,java.compiler,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.httpserver,jdk.jfr,jdk.management,jdk.management.jfr,jdk.naming.dns,jdk.net,jdk.unsupported,jdk.zipfs \
  -XX:+UseSerialGC \
  -Xms196M \
  -Xmx196M \
  -XX:+AlwaysPreTouch \
  -XX:MaxMetaspaceSize=84M \
  -XX:CompressedClassSpaceSize=48M \
  -Xss256K \
  -XX:-TieredCompilation \
  -XX:CICompilerCount=1 \
  -XX:ReservedCodeCacheSize=24M \
  -XX:MaxDirectMemorySize=16M \
  -XX:NativeMemoryTracking=summary \
  -Dio.netty.allocator.type=unpooled \
  -XX:StartFlightRecording=dumponexit=true,settings="$JFR_SETTINGS",filename="$JFR_FILE" \
  com.hedera.node.app.ServicesMain \
  -local 0
