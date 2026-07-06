RUN 1: EXISTING 5M STATE, NO BUFFER SIZES IN SOCKETFACTORY SET

# JMH version: 1.37
# VM version: JDK 25.0.3, OpenJDK 64-Bit Server VM, 25.0.3+9-LTS
# VM invoker: /Users/user/Library/Java/JavaVirtualMachines/temurin-25.0.3/Contents/Home/bin/java
# VM options: --add-exports=org.hiero.consensus.gossip.impl/org.hiero.consensus.gossip.impl.network.connectivity=com.swirlds.benchmarks,ALL-UNNAMED -Xms2g -Xmx8g -Xlog:gc*:file=/Users/user/Nikita/LC/hiero-consensus-node/platform-sdk/swirlds-benchmarks/build/reconnectbench-gc.log:time,uptime,level,tags
# Blackhole mode: compiler (auto-detected, use -Djmh.blackhole.autoDetect=false to disable)
# Warmup: <none>
# Measurement: 3 iterations, single-shot each
# Timeout: 2147483647 s per iteration
# Threads: 1 thread
# Benchmark mode: Single shot invocation time
# Benchmark: com.swirlds.benchmark.ReconnectBench.reconnect
# Parameters: (keySize = 32, maxKey = 10000000, networkBandwidthMegabitsPerSecond = 200, networkInflightBytesLimit = 16777216, networkLatencyMicroseconds = 270, networkProfile = REALISTIC, networkTransport = LOOPBACK_SOCKET, numFiles = 10, numRecords = 100, numThreads = 32, randomSeed = 9823452658, recordSize = 128, teacherAddProbability = 0.1, teacherModifyProbability = 0.3, teacherRemoveProbability = 0.0)

# Run progress: 0,00% complete, ETA 00:00:00
# Fork: 1 of 1
WARNING: Unknown module: org.hiero.consensus.gossip.impl specified to --add-exports
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.openjdk.jmh.util.Utils (file:/Users/user/Nikita/LC/hiero-consensus-node/platform-sdk/swirlds-benchmarks/build/libs/swirlds-benchmarks-0.77.0-SNAPSHOT-jmh-merged.jar)
WARNING: Please consider reporting this to the maintainers of class org.openjdk.jmh.util.Utils
WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
Iteration   1: 2026-07-06 14:42:56.399 Benchmark configuration: BenchmarkConfig[benchmarkData=data,saveDataDirectory=true,verifyResult=false,enableSnapshots=false,printHistogram=false,csvOutputFolder=data,csvMetricsFileName=BenchmarkMetrics.csv,csvMetricNamesFileName=BenchmarkMetricNames.csv,csvWriteFrequency=1000,csvAppend=true,deviceName=sda]
2026-07-06 14:42:56.402 Build: 0.77.0-SNAPSHOT (3af8481)
2026-07-06 14:42:56.435 Restoring map from data/ReconnectBench/teacher/saved0
2026-07-06 14:42:56.601 Restored map from data/ReconnectBench/teacher/saved0
2026-07-06 14:42:56.602 Restoring map from data/ReconnectBench/learner/saved0
2026-07-06 14:42:56.687 Restored map from data/ReconnectBench/learner/saved0
2026-07-06 14:42:56.714 --------------------------------
2026-07-06 14:42:56.715 ReconnectBench traversal mode=pullTopToBottom
2026-07-06 14:42:56.715 ReconnectBench transport=LOOPBACK_SOCKET, network profile=REALISTIC, latencyNanos=270000, bandwidthBytesPerSecond=25000000, inflightBytesLimit=16777216
2026-07-06 14:42:56.716 Starting Tree: com.swirlds.virtualmap.VirtualMap@6f3513e9 metadata=VirtualMapMetadata[firstLeafPath=4999998,lastLeafPath=9999996,size=4999999]
2026-07-06 14:42:56.716 Desired Tree: com.swirlds.virtualmap.VirtualMap@4639d5b7 metadata=VirtualMapMetadata[firstLeafPath=5558833,lastLeafPath=11117666,size=5558834]
2026-07-06 14:42:56.740 Socket transport diagnostics: SocketTransportDiagnostics[transport=LOOPBACK_SOCKET, profile=REALISTIC, latencyShapingActive=true, bandwidthShapingActive=true, configuredLatencyNanos=270000, configuredBandwidthBytesPerSecond=25000000, inflightBytesLimitIgnored=true, streamBufferBytes=8192, serverReceiveBufferBytes=131072, clientSendBufferBytes=146988, clientReceiveBufferBytes=408300, acceptedSendBufferBytes=146988, acceptedReceiveBufferBytes=408300, clientTcpNoDelay=true, acceptedTcpNoDelay=true]
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.openjdk.jmh.util.Utils (file:/Users/user/Nikita/LC/hiero-consensus-node/platform-sdk/swirlds-benchmarks/build/libs/swirlds-benchmarks-0.77.0-SNAPSHOT-jmh-merged.jar)
WARNING: Please consider reporting this to the maintainers of class org.openjdk.jmh.util.Utils
WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
2026-07-06 14:43:59.987 Reconnect stats: ReconnectMapStatsSnapshot: transfersFromTeacher=6319189; transfersFromLearner=6513135; internalHashes=2284806; internalCleanHashes=571953; internalData=2298377; internalCleanData=570722; leafHashes=3838514; leafCleanHashes=1883159; leafData=4144502; leafCleanData=1922374
2026-07-06 14:43:59.995 Network teacherToLearner: SimulatedNetworkStats[bytesWritten=392186956, bytesRead=392186956, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
2026-07-06 14:43:59.995 Network learnerToTeacher: SimulatedNetworkStats[bytesWritten=411812788, bytesRead=411812788, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
63,279 s/op
Iteration   2: 2026-07-06 14:44:00.014 --------------------------------
2026-07-06 14:44:00.015 ReconnectBench traversal mode=pullTopToBottom
2026-07-06 14:44:00.015 ReconnectBench transport=LOOPBACK_SOCKET, network profile=REALISTIC, latencyNanos=270000, bandwidthBytesPerSecond=25000000, inflightBytesLimit=16777216
2026-07-06 14:44:00.015 Starting Tree: com.swirlds.virtualmap.VirtualMap@6f3513e9 metadata=VirtualMapMetadata[firstLeafPath=4999998,lastLeafPath=9999996,size=4999999]
2026-07-06 14:44:00.015 Desired Tree: com.swirlds.virtualmap.VirtualMap@4639d5b7 metadata=VirtualMapMetadata[firstLeafPath=5558833,lastLeafPath=11117666,size=5558834]
2026-07-06 14:44:00.017 Socket transport diagnostics: SocketTransportDiagnostics[transport=LOOPBACK_SOCKET, profile=REALISTIC, latencyShapingActive=true, bandwidthShapingActive=true, configuredLatencyNanos=270000, configuredBandwidthBytesPerSecond=25000000, inflightBytesLimitIgnored=true, streamBufferBytes=8192, serverReceiveBufferBytes=131072, clientSendBufferBytes=146988, clientReceiveBufferBytes=408300, acceptedSendBufferBytes=146988, acceptedReceiveBufferBytes=408300, clientTcpNoDelay=true, acceptedTcpNoDelay=true]
2026-07-06 14:45:15.527 Reconnect stats: ReconnectMapStatsSnapshot: transfersFromTeacher=6344041; transfersFromLearner=6513486; internalHashes=2291892; internalCleanHashes=571299; internalData=2301476; internalCleanData=570805; leafHashes=3830027; leafCleanHashes=1884640; leafData=4144502; leafCleanData=1922374
2026-07-06 14:45:15.530 Network teacherToLearner: SimulatedNetworkStats[bytesWritten=392186956, bytesRead=392186956, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
2026-07-06 14:45:15.530 Network learnerToTeacher: SimulatedNetworkStats[bytesWritten=411812788, bytesRead=411812788, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
75,513 s/op
Iteration   3: 2026-07-06 14:45:15.547 --------------------------------
2026-07-06 14:45:15.547 ReconnectBench traversal mode=pullTopToBottom
2026-07-06 14:45:15.547 ReconnectBench transport=LOOPBACK_SOCKET, network profile=REALISTIC, latencyNanos=270000, bandwidthBytesPerSecond=25000000, inflightBytesLimit=16777216
2026-07-06 14:45:15.548 Starting Tree: com.swirlds.virtualmap.VirtualMap@6f3513e9 metadata=VirtualMapMetadata[firstLeafPath=4999998,lastLeafPath=9999996,size=4999999]
2026-07-06 14:45:15.548 Desired Tree: com.swirlds.virtualmap.VirtualMap@4639d5b7 metadata=VirtualMapMetadata[firstLeafPath=5558833,lastLeafPath=11117666,size=5558834]
2026-07-06 14:45:15.553 Socket transport diagnostics: SocketTransportDiagnostics[transport=LOOPBACK_SOCKET, profile=REALISTIC, latencyShapingActive=true, bandwidthShapingActive=true, configuredLatencyNanos=270000, configuredBandwidthBytesPerSecond=25000000, inflightBytesLimitIgnored=true, streamBufferBytes=8192, serverReceiveBufferBytes=131072, clientSendBufferBytes=146988, clientReceiveBufferBytes=408300, acceptedSendBufferBytes=146988, acceptedReceiveBufferBytes=408300, clientTcpNoDelay=true, acceptedTcpNoDelay=true]
2026-07-06 14:46:22.622 Reconnect stats: ReconnectMapStatsSnapshot: transfersFromTeacher=6329925; transfersFromLearner=6516003; internalHashes=2284590; internalCleanHashes=570011; internalData=2299671; internalCleanData=570507; leafHashes=3709080; leafCleanHashes=1873612; leafData=4144502; leafCleanData=1922374
2026-07-06 14:46:22.624 Network teacherToLearner: SimulatedNetworkStats[bytesWritten=392186956, bytesRead=392186956, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
2026-07-06 14:46:22.624 Network learnerToTeacher: SimulatedNetworkStats[bytesWritten=411812788, bytesRead=411812788, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
67,075 s/op


Result "com.swirlds.benchmark.ReconnectBench.reconnect":
N = 3
mean =     68,623 ±(99.9%) 114,243 s/op

Histogram, s/op:
[60,000, 61,250) = 0
[61,250, 62,500) = 0
[62,500, 63,750) = 1
[63,750, 65,000) = 0
[65,000, 66,250) = 0
[66,250, 67,500) = 1
[67,500, 68,750) = 0
[68,750, 70,000) = 0
[70,000, 71,250) = 0
[71,250, 72,500) = 0
[72,500, 73,750) = 0
[73,750, 75,000) = 0
[75,000, 76,250) = 1
[76,250, 77,500) = 0
[77,500, 78,750) = 0

Percentiles, s/op:
p(0,0000) =     63,279 s/op
p(50,0000) =     67,075 s/op
p(90,0000) =     75,513 s/op
p(95,0000) =     75,513 s/op
p(99,0000) =     75,513 s/op
p(99,9000) =     75,513 s/op
p(99,9900) =     75,513 s/op
p(99,9990) =     75,513 s/op
p(99,9999) =     75,513 s/op
p(100,0000) =     75,513 s/op


# Run complete. Total time: 00:03:28

REMEMBER: The numbers below are just data. To gain reusable insights, you need to follow up on
why the numbers are the way they are. Use profilers (see -prof, -lprof), design factorial
experiments, perform baseline and negative tests that provide experimental control, make sure
the benchmarking environment is safe on JVM/OS/HW level, ask for reviews from the domain experts.
Do not assume the numbers tell you what you want them to tell.

NOTE: Current JVM experimentally supports Compiler Blackholes, and they are in use. Please exercise
extra caution when trusting the results, look into the generated code to check the benchmark still
works, and factor in a small probability of new VM bugs. Additionally, while comparisons between
different JVMs are already problematic, the performance difference caused by different Blackhole
modes can be very significant. Please make sure you use the consistent Blackhole mode for comparisons.

Benchmark                 (keySize)  (maxKey)  (networkBandwidthMegabitsPerSecond)  (networkInflightBytesLimit)  (networkLatencyMicroseconds)  (networkProfile)  (networkTransport)  (numFiles)  (numRecords)  (numThreads)  (randomSeed)  (recordSize)  (teacherAddProbability)  (teacherModifyProbability)  (teacherRemoveProbability)  Mode  Cnt   Score     Error  Units
ReconnectBench.reconnect         32  10000000                                  200                     16777216                           270         REALISTIC     LOOPBACK_SOCKET          10           100            32    9823452658           128                      0.1                         0.3                         0.0    ss    3  68,623 ± 114,243   s/op


---

RUN 2: EXISTING 5M STATE, BUFFER SIZES IN SOCKETFACTORY SET  to 1 << 20 (1MiB)
final int reconnectBufferBytes =  1 << 20; // 1MiB
serverSocket.setReceiveBufferSize(reconnectBufferBytes);
...
clientSocket.setReceiveBufferSize(reconnectBufferBytes);
clientSocket.setSendBufferSize(reconnectBufferBytes);

# JMH version: 1.37
# VM version: JDK 25.0.3, OpenJDK 64-Bit Server VM, 25.0.3+9-LTS
# VM invoker: /Users/user/Library/Java/JavaVirtualMachines/temurin-25.0.3/Contents/Home/bin/java
# VM options: --add-exports=org.hiero.consensus.gossip.impl/org.hiero.consensus.gossip.impl.network.connectivity=com.swirlds.benchmarks,ALL-UNNAMED -Xms2g -Xmx8g -Xlog:gc*:file=/Users/user/Nikita/LC/hiero-consensus-node/platform-sdk/swirlds-benchmarks/build/reconnectbench-gc.log:time,uptime,level,tags
# Blackhole mode: compiler (auto-detected, use -Djmh.blackhole.autoDetect=false to disable)
# Warmup: <none>
# Measurement: 3 iterations, single-shot each
# Timeout: 2147483647 s per iteration
# Threads: 1 thread
# Benchmark mode: Single shot invocation time
# Benchmark: com.swirlds.benchmark.ReconnectBench.reconnect
# Parameters: (keySize = 32, maxKey = 10000000, networkBandwidthMegabitsPerSecond = 200, networkInflightBytesLimit = 16777216, networkLatencyMicroseconds = 270, networkProfile = REALISTIC, networkTransport = LOOPBACK_SOCKET, numFiles = 10, numRecords = 100, numThreads = 32, randomSeed = 9823452658, recordSize = 128, teacherAddProbability = 0.1, teacherModifyProbability = 0.3, teacherRemoveProbability = 0.0)

# Run progress: 0,00% complete, ETA 00:00:00
# Fork: 1 of 1
WARNING: Unknown module: org.hiero.consensus.gossip.impl specified to --add-exports
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.openjdk.jmh.util.Utils (file:/Users/user/Nikita/LC/hiero-consensus-node/platform-sdk/swirlds-benchmarks/build/libs/swirlds-benchmarks-0.77.0-SNAPSHOT-jmh-merged.jar)
WARNING: Please consider reporting this to the maintainers of class org.openjdk.jmh.util.Utils
WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
Iteration   1: 2026-07-06 14:48:52.811 Benchmark configuration: BenchmarkConfig[benchmarkData=data,saveDataDirectory=true,verifyResult=false,enableSnapshots=false,printHistogram=false,csvOutputFolder=data,csvMetricsFileName=BenchmarkMetrics.csv,csvMetricNamesFileName=BenchmarkMetricNames.csv,csvWriteFrequency=1000,csvAppend=true,deviceName=sda]
2026-07-06 14:48:52.813 Build: 0.77.0-SNAPSHOT (3af8481)
2026-07-06 14:48:52.848 Restoring map from data/ReconnectBench/teacher/saved0
2026-07-06 14:48:53.011 Restored map from data/ReconnectBench/teacher/saved0
2026-07-06 14:48:53.011 Restoring map from data/ReconnectBench/learner/saved0
2026-07-06 14:48:53.077 Restored map from data/ReconnectBench/learner/saved0
2026-07-06 14:48:53.105 --------------------------------
2026-07-06 14:48:53.106 ReconnectBench traversal mode=pullTopToBottom
2026-07-06 14:48:53.106 ReconnectBench transport=LOOPBACK_SOCKET, network profile=REALISTIC, latencyNanos=270000, bandwidthBytesPerSecond=25000000, inflightBytesLimit=16777216
2026-07-06 14:48:53.107 Starting Tree: com.swirlds.virtualmap.VirtualMap@70ca180 metadata=VirtualMapMetadata[firstLeafPath=4999998,lastLeafPath=9999996,size=4999999]
2026-07-06 14:48:53.107 Desired Tree: com.swirlds.virtualmap.VirtualMap@6f3513e9 metadata=VirtualMapMetadata[firstLeafPath=5558833,lastLeafPath=11117666,size=5558834]
2026-07-06 14:48:53.126 Socket transport diagnostics: SocketTransportDiagnostics[transport=LOOPBACK_SOCKET, profile=REALISTIC, latencyShapingActive=true, bandwidthShapingActive=true, configuredLatencyNanos=270000, configuredBandwidthBytesPerSecond=25000000, inflightBytesLimitIgnored=true, streamBufferBytes=8192, serverReceiveBufferBytes=1048576, clientSendBufferBytes=1061580, clientReceiveBufferBytes=1061580, acceptedSendBufferBytes=146988, acceptedReceiveBufferBytes=1061580, clientTcpNoDelay=true, acceptedTcpNoDelay=true]
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.openjdk.jmh.util.Utils (file:/Users/user/Nikita/LC/hiero-consensus-node/platform-sdk/swirlds-benchmarks/build/libs/swirlds-benchmarks-0.77.0-SNAPSHOT-jmh-merged.jar)
WARNING: Please consider reporting this to the maintainers of class org.openjdk.jmh.util.Utils
WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
2026-07-06 14:49:49.375 Reconnect stats: ReconnectMapStatsSnapshot: transfersFromTeacher=6364814; transfersFromLearner=6515347; internalHashes=2286750; internalCleanHashes=561230; internalData=2285411; internalCleanData=566413; leafHashes=3658544; leafCleanHashes=1879235; leafData=4144502; leafCleanData=1922374
2026-07-06 14:49:49.380 Network teacherToLearner: SimulatedNetworkStats[bytesWritten=392186956, bytesRead=392186956, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
2026-07-06 14:49:49.381 Network learnerToTeacher: SimulatedNetworkStats[bytesWritten=411812788, bytesRead=411812788, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
56,274 s/op
Iteration   2: 2026-07-06 14:49:49.399 --------------------------------
2026-07-06 14:49:49.399 ReconnectBench traversal mode=pullTopToBottom
2026-07-06 14:49:49.399 ReconnectBench transport=LOOPBACK_SOCKET, network profile=REALISTIC, latencyNanos=270000, bandwidthBytesPerSecond=25000000, inflightBytesLimit=16777216
2026-07-06 14:49:49.400 Starting Tree: com.swirlds.virtualmap.VirtualMap@70ca180 metadata=VirtualMapMetadata[firstLeafPath=4999998,lastLeafPath=9999996,size=4999999]
2026-07-06 14:49:49.400 Desired Tree: com.swirlds.virtualmap.VirtualMap@6f3513e9 metadata=VirtualMapMetadata[firstLeafPath=5558833,lastLeafPath=11117666,size=5558834]
2026-07-06 14:49:49.401 Socket transport diagnostics: SocketTransportDiagnostics[transport=LOOPBACK_SOCKET, profile=REALISTIC, latencyShapingActive=true, bandwidthShapingActive=true, configuredLatencyNanos=270000, configuredBandwidthBytesPerSecond=25000000, inflightBytesLimitIgnored=true, streamBufferBytes=8192, serverReceiveBufferBytes=1048576, clientSendBufferBytes=1061580, clientReceiveBufferBytes=1061580, acceptedSendBufferBytes=146988, acceptedReceiveBufferBytes=1061580, clientTcpNoDelay=true, acceptedTcpNoDelay=true]
2026-07-06 14:50:42.952 Reconnect stats: ReconnectMapStatsSnapshot: transfersFromTeacher=6352254; transfersFromLearner=6514858; internalHashes=2273992; internalCleanHashes=561850; internalData=2273036; internalCleanData=566203; leafHashes=3660975; leafCleanHashes=1874314; leafData=4144502; leafCleanData=1922374
2026-07-06 14:50:42.952 Network teacherToLearner: SimulatedNetworkStats[bytesWritten=392186956, bytesRead=392186956, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
2026-07-06 14:50:42.952 Network learnerToTeacher: SimulatedNetworkStats[bytesWritten=411812788, bytesRead=411812788, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
53,552 s/op
Iteration   3: 2026-07-06 14:50:42.954 --------------------------------
2026-07-06 14:50:42.954 ReconnectBench traversal mode=pullTopToBottom
2026-07-06 14:50:42.954 ReconnectBench transport=LOOPBACK_SOCKET, network profile=REALISTIC, latencyNanos=270000, bandwidthBytesPerSecond=25000000, inflightBytesLimit=16777216
2026-07-06 14:50:42.954 Starting Tree: com.swirlds.virtualmap.VirtualMap@70ca180 metadata=VirtualMapMetadata[firstLeafPath=4999998,lastLeafPath=9999996,size=4999999]
2026-07-06 14:50:42.954 Desired Tree: com.swirlds.virtualmap.VirtualMap@6f3513e9 metadata=VirtualMapMetadata[firstLeafPath=5558833,lastLeafPath=11117666,size=5558834]
2026-07-06 14:50:42.956 Socket transport diagnostics: SocketTransportDiagnostics[transport=LOOPBACK_SOCKET, profile=REALISTIC, latencyShapingActive=true, bandwidthShapingActive=true, configuredLatencyNanos=270000, configuredBandwidthBytesPerSecond=25000000, inflightBytesLimitIgnored=true, streamBufferBytes=8192, serverReceiveBufferBytes=1048576, clientSendBufferBytes=1061580, clientReceiveBufferBytes=1061580, acceptedSendBufferBytes=146988, acceptedReceiveBufferBytes=1061580, clientTcpNoDelay=true, acceptedTcpNoDelay=true]
2026-07-06 14:51:36.616 Reconnect stats: ReconnectMapStatsSnapshot: transfersFromTeacher=6340826; transfersFromLearner=6518498; internalHashes=2265250; internalCleanHashes=557849; internalData=2261740; internalCleanData=564079; leafHashes=3627298; leafCleanHashes=1870518; leafData=4144502; leafCleanData=1922374
2026-07-06 14:51:36.616 Network teacherToLearner: SimulatedNetworkStats[bytesWritten=392186956, bytesRead=392186956, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
2026-07-06 14:51:36.616 Network learnerToTeacher: SimulatedNetworkStats[bytesWritten=411812788, bytesRead=411812788, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
53,660 s/op


Result "com.swirlds.benchmark.ReconnectBench.reconnect":
N = 3
mean =     54,495 ±(99.9%) 28,122 s/op

Histogram, s/op:
[53,000, 53,250) = 0
[53,250, 53,500) = 0
[53,500, 53,750) = 2
[53,750, 54,000) = 0
[54,000, 54,250) = 0
[54,250, 54,500) = 0
[54,500, 54,750) = 0
[54,750, 55,000) = 0
[55,000, 55,250) = 0
[55,250, 55,500) = 0
[55,500, 55,750) = 0
[55,750, 56,000) = 0
[56,000, 56,250) = 0
[56,250, 56,500) = 1
[56,500, 56,750) = 0

Percentiles, s/op:
p(0,0000) =     53,552 s/op
p(50,0000) =     53,660 s/op
p(90,0000) =     56,274 s/op
p(95,0000) =     56,274 s/op
p(99,0000) =     56,274 s/op
p(99,9000) =     56,274 s/op
p(99,9900) =     56,274 s/op
p(99,9990) =     56,274 s/op
p(99,9999) =     56,274 s/op
p(100,0000) =     56,274 s/op


# Run complete. Total time: 00:02:45

REMEMBER: The numbers below are just data. To gain reusable insights, you need to follow up on
why the numbers are the way they are. Use profilers (see -prof, -lprof), design factorial
experiments, perform baseline and negative tests that provide experimental control, make sure
the benchmarking environment is safe on JVM/OS/HW level, ask for reviews from the domain experts.
Do not assume the numbers tell you what you want them to tell.

NOTE: Current JVM experimentally supports Compiler Blackholes, and they are in use. Please exercise
extra caution when trusting the results, look into the generated code to check the benchmark still
works, and factor in a small probability of new VM bugs. Additionally, while comparisons between
different JVMs are already problematic, the performance difference caused by different Blackhole
modes can be very significant. Please make sure you use the consistent Blackhole mode for comparisons.

Benchmark                 (keySize)  (maxKey)  (networkBandwidthMegabitsPerSecond)  (networkInflightBytesLimit)  (networkLatencyMicroseconds)  (networkProfile)  (networkTransport)  (numFiles)  (numRecords)  (numThreads)  (randomSeed)  (recordSize)  (teacherAddProbability)  (teacherModifyProbability)  (teacherRemoveProbability)  Mode  Cnt   Score    Error  Units
ReconnectBench.reconnect         32  10000000                                  200                     16777216                           270         REALISTIC     LOOPBACK_SOCKET          10           100            32    9823452658           128                      0.1                         0.3                         0.0    ss    3  54,495 ± 28,122   s/op

---

RUN 3: EXISTING 5M STATE, BUFFER SIZES IN SOCKETFACTORY SET  to 32768
final int reconnectBufferBytes =  32768;
serverSocket.setReceiveBufferSize(reconnectBufferBytes);
...
clientSocket.setReceiveBufferSize(reconnectBufferBytes);
clientSocket.setSendBufferSize(reconnectBufferBytes);

2026-07-06 14:55:54.609 --------------------------------
2026-07-06 14:55:54.610 ReconnectBench traversal mode=pullTopToBottom
2026-07-06 14:55:54.610 ReconnectBench transport=LOOPBACK_SOCKET, network profile=REALISTIC, latencyNanos=270000, bandwidthBytesPerSecond=25000000, inflightBytesLimit=16777216
2026-07-06 14:55:54.610 Starting Tree: com.swirlds.virtualmap.VirtualMap@6f3513e9 metadata=VirtualMapMetadata[firstLeafPath=4999998,lastLeafPath=9999996,size=4999999]
2026-07-06 14:55:54.611 Desired Tree: com.swirlds.virtualmap.VirtualMap@4639d5b7 metadata=VirtualMapMetadata[firstLeafPath=5558833,lastLeafPath=11117666,size=5558834]
2026-07-06 14:55:54.629 Socket transport diagnostics: SocketTransportDiagnostics[transport=LOOPBACK_SOCKET, profile=REALISTIC, latencyShapingActive=true, bandwidthShapingActive=true, configuredLatencyNanos=270000, configuredBandwidthBytesPerSecond=25000000, inflightBytesLimitIgnored=true, streamBufferBytes=8192, serverReceiveBufferBytes=32768, clientSendBufferBytes=65328, clientReceiveBufferBytes=326640, acceptedSendBufferBytes=146988, acceptedReceiveBufferBytes=326640, clientTcpNoDelay=true, acceptedTcpNoDelay=true]
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.openjdk.jmh.util.Utils (file:/Users/user/Nikita/LC/hiero-consensus-node/platform-sdk/swirlds-benchmarks/build/libs/swirlds-benchmarks-0.77.0-SNAPSHOT-jmh-merged.jar)
WARNING: Please consider reporting this to the maintainers of class org.openjdk.jmh.util.Utils
WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
2026-07-06 14:56:49.878 Reconnect stats: ReconnectMapStatsSnapshot: transfersFromTeacher=6337111; transfersFromLearner=6516072; internalHashes=2267346; internalCleanHashes=562884; internalData=2274984; internalCleanData=569062; leafHashes=3663570; leafCleanHashes=1869115; leafData=4144502; leafCleanData=1922374
2026-07-06 14:56:49.883 Network teacherToLearner: SimulatedNetworkStats[bytesWritten=392186956, bytesRead=392186956, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
2026-07-06 14:56:49.884 Network learnerToTeacher: SimulatedNetworkStats[bytesWritten=411812788, bytesRead=411812788, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
55,273 s/op
Iteration   2: 2026-07-06 14:56:49.901 --------------------------------
2026-07-06 14:56:49.901 ReconnectBench traversal mode=pullTopToBottom
2026-07-06 14:56:49.902 ReconnectBench transport=LOOPBACK_SOCKET, network profile=REALISTIC, latencyNanos=270000, bandwidthBytesPerSecond=25000000, inflightBytesLimit=16777216
2026-07-06 14:56:49.902 Starting Tree: com.swirlds.virtualmap.VirtualMap@6f3513e9 metadata=VirtualMapMetadata[firstLeafPath=4999998,lastLeafPath=9999996,size=4999999]
2026-07-06 14:56:49.902 Desired Tree: com.swirlds.virtualmap.VirtualMap@4639d5b7 metadata=VirtualMapMetadata[firstLeafPath=5558833,lastLeafPath=11117666,size=5558834]
2026-07-06 14:56:49.904 Socket transport diagnostics: SocketTransportDiagnostics[transport=LOOPBACK_SOCKET, profile=REALISTIC, latencyShapingActive=true, bandwidthShapingActive=true, configuredLatencyNanos=270000, configuredBandwidthBytesPerSecond=25000000, inflightBytesLimitIgnored=true, streamBufferBytes=8192, serverReceiveBufferBytes=32768, clientSendBufferBytes=65328, clientReceiveBufferBytes=326640, acceptedSendBufferBytes=146988, acceptedReceiveBufferBytes=326640, clientTcpNoDelay=true, acceptedTcpNoDelay=true]
2026-07-06 14:57:43.008 Reconnect stats: ReconnectMapStatsSnapshot: transfersFromTeacher=6364060; transfersFromLearner=6515827; internalHashes=2280137; internalCleanHashes=563694; internalData=2288289; internalCleanData=570053; leafHashes=3661895; leafCleanHashes=1869401; leafData=4144502; leafCleanData=1922374
2026-07-06 14:57:43.008 Network teacherToLearner: SimulatedNetworkStats[bytesWritten=392186956, bytesRead=392186956, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
2026-07-06 14:57:43.008 Network learnerToTeacher: SimulatedNetworkStats[bytesWritten=411812788, bytesRead=411812788, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
53,105 s/op
Iteration   3: 2026-07-06 14:57:43.010 --------------------------------
2026-07-06 14:57:43.010 ReconnectBench traversal mode=pullTopToBottom
2026-07-06 14:57:43.010 ReconnectBench transport=LOOPBACK_SOCKET, network profile=REALISTIC, latencyNanos=270000, bandwidthBytesPerSecond=25000000, inflightBytesLimit=16777216
2026-07-06 14:57:43.010 Starting Tree: com.swirlds.virtualmap.VirtualMap@6f3513e9 metadata=VirtualMapMetadata[firstLeafPath=4999998,lastLeafPath=9999996,size=4999999]
2026-07-06 14:57:43.010 Desired Tree: com.swirlds.virtualmap.VirtualMap@4639d5b7 metadata=VirtualMapMetadata[firstLeafPath=5558833,lastLeafPath=11117666,size=5558834]
2026-07-06 14:57:43.011 Socket transport diagnostics: SocketTransportDiagnostics[transport=LOOPBACK_SOCKET, profile=REALISTIC, latencyShapingActive=true, bandwidthShapingActive=true, configuredLatencyNanos=270000, configuredBandwidthBytesPerSecond=25000000, inflightBytesLimitIgnored=true, streamBufferBytes=8192, serverReceiveBufferBytes=32768, clientSendBufferBytes=65328, clientReceiveBufferBytes=326640, acceptedSendBufferBytes=146988, acceptedReceiveBufferBytes=326640, clientTcpNoDelay=true, acceptedTcpNoDelay=true]
2026-07-06 14:58:37.063 Reconnect stats: ReconnectMapStatsSnapshot: transfersFromTeacher=6348832; transfersFromLearner=6515742; internalHashes=2266130; internalCleanHashes=561936; internalData=2277812; internalCleanData=568876; leafHashes=3662783; leafCleanHashes=1869540; leafData=4144502; leafCleanData=1922374
2026-07-06 14:58:37.063 Network teacherToLearner: SimulatedNetworkStats[bytesWritten=392186956, bytesRead=392186956, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
2026-07-06 14:58:37.063 Network learnerToTeacher: SimulatedNetworkStats[bytesWritten=411812788, bytesRead=411812788, maxInflightBytes=0, writeCalls=0, writeRanges=0, readCalls=0, capacityWaitCount=0, capacityWaitNanos=0, emptyReadWaitCount=0, emptyReadWaitNanos=0, arrivalWaitCount=0, arrivalWaitNanos=0]
54,052 s/op


Result "com.swirlds.benchmark.ReconnectBench.reconnect":
N = 3
mean =     54,143 ±(99.9%) 19,825 s/op

Histogram, s/op:
[53,000, 53,250) = 1
[53,250, 53,500) = 0
[53,500, 53,750) = 0
[53,750, 54,000) = 0
[54,000, 54,250) = 1
[54,250, 54,500) = 0
[54,500, 54,750) = 0
[54,750, 55,000) = 0
[55,000, 55,250) = 0
[55,250, 55,500) = 1
[55,500, 55,750) = 0

Percentiles, s/op:
p(0,0000) =     53,105 s/op
p(50,0000) =     54,052 s/op
p(90,0000) =     55,273 s/op
p(95,0000) =     55,273 s/op
p(99,0000) =     55,273 s/op
p(99,9000) =     55,273 s/op
p(99,9900) =     55,273 s/op
p(99,9990) =     55,273 s/op
p(99,9999) =     55,273 s/op
p(100,0000) =     55,273 s/op


# Run complete. Total time: 00:02:44

REMEMBER: The numbers below are just data. To gain reusable insights, you need to follow up on
why the numbers are the way they are. Use profilers (see -prof, -lprof), design factorial
experiments, perform baseline and negative tests that provide experimental control, make sure
the benchmarking environment is safe on JVM/OS/HW level, ask for reviews from the domain experts.
Do not assume the numbers tell you what you want them to tell.

NOTE: Current JVM experimentally supports Compiler Blackholes, and they are in use. Please exercise
extra caution when trusting the results, look into the generated code to check the benchmark still
works, and factor in a small probability of new VM bugs. Additionally, while comparisons between
different JVMs are already problematic, the performance difference caused by different Blackhole
modes can be very significant. Please make sure you use the consistent Blackhole mode for comparisons.

Benchmark                 (keySize)  (maxKey)  (networkBandwidthMegabitsPerSecond)  (networkInflightBytesLimit)  (networkLatencyMicroseconds)  (networkProfile)  (networkTransport)  (numFiles)  (numRecords)  (numThreads)  (randomSeed)  (recordSize)  (teacherAddProbability)  (teacherModifyProbability)  (teacherRemoveProbability)  Mode  Cnt   Score    Error  Units
ReconnectBench.reconnect         32  10000000                                  200                     16777216                           270         REALISTIC     LOOPBACK_SOCKET          10           100            32    9823452658           128                      0.1                         0.3                         0.0    ss    3  54,143 ± 19,825   s/op
