#!/usr/bin/env bash
set -euo pipefail

JFR_SETTINGS="data/config/MemoryLowOverhead.jfc"
JFR_FILE="output/recording.jfr"

exec java \
  -cp 'data/lib/*:data/apps/*' \
  -server \
  -Djava.awt.headless=true \
  -Djava.util.concurrent.ForkJoinPool.common.parallelism=2 \
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
  -Dio.netty.allocator.type=unpooled \
  -XX:StartFlightRecording=dumponexit=true,settings="$JFR_SETTINGS",filename="$JFR_FILE" \
  com.hedera.node.app.ServicesMain \
  -local 0
