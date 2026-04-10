// SPDX-License-Identifier: Apache-2.0
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.shadow")
}

description = "Hedera Services Test Clients for End to End Tests (EET)"

// Detect available resources and scale JVM settings accordingly
val availableCpus = Runtime.getRuntime().availableProcessors()
val totalMemoryGib: Double =
    try {
        val osName = System.getProperty("os.name", "").lowercase()
        if (osName.contains("linux")) {
            // Try cgroup limit first (container-aware), fall back to /proc/meminfo
            val cgroupV2 = File("/sys/fs/cgroup/memory.max")
            val cgroupV1 = File("/sys/fs/cgroup/memory/memory.limit_in_bytes")
            val cgroupBytes: Long? =
                when {
                    cgroupV2.exists() -> cgroupV2.readText().trim().toLongOrNull()
                    cgroupV1.exists() -> cgroupV1.readText().trim().toLongOrNull()
                    else -> null
                }
            if (cgroupBytes != null && cgroupBytes < Long.MAX_VALUE / 2) {
                cgroupBytes / 1024.0 / 1024.0 / 1024.0
            } else {
                val memLine =
                    File("/proc/meminfo").readLines().first { line -> line.startsWith("MemTotal") }
                memLine.split("\\s+".toRegex())[1].toLong() / 1024.0 / 1024.0
            }
        } else {
            // macOS/other: use Gradle JVM max memory as a proxy, fallback to 16 GiB
            // This is the Gradle daemon's max heap, not physical RAM, but provides a
            // reasonable lower bound for scaling test settings
            Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0 / 1024.0
        }
    } catch (_: Exception) {
        16.0
    }
// Use all available processors but cap at 8 to avoid excessive thread contention
val testProcessorCount = availableCpus.coerceAtMost(8)
// Parallelism is set per-task based on actual node count (see testSubprocessConcurrent below)
// Reserve ~half of total memory for the test client JVM, leave the rest for forked node JVMs and OS
val testClientHeapGib = (totalMemoryGib / 2).toInt().coerceIn(4, 8)
val testMaxHeap = "${testClientHeapGib}g"
// Pass remaining memory pool to ProcessUtils, which divides by actual node count at runtime
val nodePoolMib = ((totalMemoryGib - testClientHeapGib) * 1024 * 0.8).toInt().coerceAtLeast(2048)

logger.lifecycle(
    "Test resource detection: cpus=$availableCpus, totalMem=${String.format("%.1f", totalMemoryGib)}GiB -> processorCount=$testProcessorCount, clientHeap=$testMaxHeap, nodePool=${nodePoolMib}m"
)

mainModuleInfo {
    runtimeOnly("org.junit.jupiter.engine")
    runtimeOnly("org.junit.platform.launcher")
}

sourceSets { create("rcdiff") }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-exports") }

tasks.register<JavaExec>("runTestClient") {
    group = "build"
    description = "Run a test client via -PtestClient=<Class>"

    classpath = configurations.runtimeClasspath.get().plus(files(tasks.jar))
    mainClass = providers.gradleProperty("testClient")
}

tasks.jacocoTestReport {
    classDirectories.setFrom(files(project(":app").layout.buildDirectory.dir("classes/java/main")))
    sourceDirectories.setFrom(files(project(":app").projectDir.resolve("src/main/java")))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.test {
    testClassesDirs = sourceSets.main.get().output.classesDirs
    classpath = configurations.runtimeClasspath.get().plus(files(tasks.jar))

    // Unlike other tests, these intentionally corrupt embedded state to test FAIL_INVALID
    // code paths; hence we do not run LOG_VALIDATION after the test suite finishes
    useJUnitPlatform { includeTags("(INTEGRATION|STREAM_VALIDATION)") }

    systemProperty("junit.jupiter.execution.parallel.enabled", true)
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    // Surprisingly, the Gradle JUnitPlatformTestExecutionListener fails to gather result
    // correctly if test classes run in parallel (concurrent execution WITHIN a test class
    // is fine). So we need to force the test classes to run in the same thread. Luckily this
    // is not a huge limitation, as our test classes generally have enough non-leaky tests to
    // get a material speed up. See https://github.com/gradle/gradle/issues/6453.
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "same_thread")
    systemProperty(
        "junit.jupiter.testclass.order.default",
        "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
    )
    // Tell our launcher to target an embedded network whose mode is set per-class
    systemProperty("hapi.spec.embedded.mode", "per-class")

    // Scale heap and processor count to match available resources
    maxHeapSize = testMaxHeap
    jvmArgs("-XX:ActiveProcessorCount=$testProcessorCount")
}

val miscTags =
    "!(INTEGRATION|CRYPTO|TOKEN|RESTART|UPGRADE|SMART_CONTRACT|ND_RECONNECT|LONG_RUNNING|STATE_THROTTLING|ISS|BLOCK_NODE|SIMPLE_FEES|ATOMIC_BATCH|WRAPS_DOWNLOAD)"
val miscTagsSerial = "$miscTags&SERIAL"

val prCheckTags =
    mapOf(
        "hapiTestAdhoc" to "ADHOC",
        "hapiTestCrypto" to "CRYPTO",
        "hapiTestCryptoSerial" to "(CRYPTO&SERIAL)",
        "hapiTestToken" to "TOKEN",
        "hapiTestTokenSerial" to "(TOKEN&SERIAL)",
        "hapiTestRestart" to "RESTART|UPGRADE",
        "hapiTestSmartContract" to "SMART_CONTRACT",
        "hapiTestSmartContractSerial" to "(SMART_CONTRACT&SERIAL)",
        "hapiTestNDReconnect" to "ND_RECONNECT",
        "hapiTestWraps" to "WRAPS",
        "hapiTestWrapsDownload" to "WRAPS_DOWNLOAD",
        "hapiTestCutover" to "CUTOVER",
        "hapiTestTimeConsuming" to "LONG_RUNNING",
        "hapiTestTimeConsumingSerial" to "(LONG_RUNNING&SERIAL)",
        "hapiTestIss" to "ISS",
        "hapiTestBlockNodeCommunication" to "BLOCK_NODE",
        "hapiTestMisc" to miscTags,
        "hapiTestMiscSerial" to miscTagsSerial,
        "hapiTestMiscRecords" to miscTags,
        "hapiTestMiscRecordsSerial" to miscTagsSerial,
        "hapiTestSimpleFees" to "SIMPLE_FEES",
        "hapiTestSimpleFeesSerial" to "(SIMPLE_FEES&SERIAL)",
        "hapiTestAtomicBatch" to "ATOMIC_BATCH",
        "hapiTestAtomicBatchSerial" to "(ATOMIC_BATCH&SERIAL)",
        "hapiTestStateThrottling" to "(STATE_THROTTLING&SERIAL)",
    )

val remoteCheckTags =
    prCheckTags
        .filterNot {
            it.key in
                listOf(
                    "hapiTestIss",
                    "hapiTestRestart",
                    "hapiTestWrapsDownload",
                    "hapiTestToken",
                    "hapiTestTokenSerial",
                )
        }
        .mapKeys { (key, _) -> key.replace("hapiTest", "remoteTest") }
val prCheckStartPorts =
    mapOf(
        "hapiTestAdhoc" to "25000",
        "hapiTestCrypto" to "25200",
        "hapiTestToken" to "25400",
        "hapiTestRestart" to "25600",
        "hapiTestSmartContract" to "25800",
        "hapiTestNDReconnect" to "26000",
        "hapiTestTimeConsuming" to "26200",
        "hapiTestWraps" to "26300",
        "hapiTestIss" to "26400",
        "hapiTestWrapsDownload" to "26500",
        "hapiTestCutover" to "26600",
        "hapiTestMisc" to "26800",
        "hapiTestBlockNodeCommunication" to "27000",
        "hapiTestMiscRecords" to "27200",
        "hapiTestAtomicBatch" to "27400",
        "hapiTestCryptoSerial" to "27600",
        "hapiTestTokenSerial" to "27800",
        "hapiTestMiscSerial" to "28000",
        "hapiTestMiscRecordsSerial" to "28200",
        "hapiTestTimeConsumingSerial" to "28400",
        "hapiTestStateThrottling" to "28600",
        "hapiTestSimpleFees" to "28800",
        "hapiTestSimpleFeesSerial" to "29000",
        "hapiTestAtomicBatchSerial" to "29200",
        "hapiTestSmartContractSerial" to "29400",
    )
val prCheckPropOverrides =
    mapOf(
        "hapiTestAdhoc" to
            "tss.hintsEnabled=true,tss.historyEnabled=true,tss.wrapsEnabled=true,tss.forceMockSignatures=false,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true",
        "hapiTestToken" to "hedera.transaction.maximumPermissibleUnhealthySeconds=5",
        "hapiTestCrypto" to
            "tss.hintsEnabled=true,tss.historyEnabled=true,tss.wrapsEnabled=false,tss.forceMockSignatures=false,blockStream.blockPeriod=1s,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true,hedera.transaction.maximumPermissibleUnhealthySeconds=5",
        "hapiTestCryptoSerial" to
            "tss.hintsEnabled=true,tss.historyEnabled=true,tss.wrapsEnabled=false,tss.forceMockSignatures=false,blockStream.blockPeriod=1s,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true",
        "hapiTestSmartContract" to
            "tss.historyEnabled=false,hedera.transaction.maximumPermissibleUnhealthySeconds=5",
        "hapiTestSmartContractSerial" to "tss.historyEnabled=false",
        "hapiTestRestart" to
            "tss.hintsEnabled=true,tss.forceHandoffs=true,tss.forceMockSignatures=false,blockStream.blockPeriod=1s,quiescence.enabled=true,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true",
        "hapiTestWrapsDownload" to
            "tss.hintsEnabled=true,tss.forceHandoffs=true,tss.initialCrsParties=16,blockStream.blockPeriod=1s,quiescence.enabled=true,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true,tss.wrapsProvingKeyDownloadEnabled=true,tss.wrapsProvingKeyPath=testfiles/valid-wraps-proving-key.tar.gz,tss.wrapsProvingKeyHash=da83f3ae5eaa8575f5bedf583de2826ccfa5bff80bd6f58a54b0bf7e934e98919b5bcdaa074b3ae248f161317b87a22a",
        "hapiTestMisc" to
            "nodes.nodeRewardsEnabled=false,quiescence.enabled=true,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true,hedera.transaction.maximumPermissibleUnhealthySeconds=5",
        "hapiTestMiscSerial" to
            "nodes.nodeRewardsEnabled=false,quiescence.enabled=true,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true",
        "hapiTestTimeConsuming" to
            "nodes.nodeRewardsEnabled=false,quiescence.enabled=true,hedera.transaction.maximumPermissibleUnhealthySeconds=5",
        "hapiTestWraps" to
            "tss.hintsEnabled=true,tss.historyEnabled=true,tss.wrapsEnabled=true,tss.forceMockSignatures=false,staking.periodMins=16",
        // Superseded by the entry below which adds tss.initialCrsParties=8; the original
        // buildMap had two put() calls for hapiTestCutover and the second silently overwrote the
        // first. Kept here for reference in case tss.forceMockSignatures=false needs to be
        // restored.
        // "hapiTestCutover" to
        //
        // "tss.hintsEnabled=false,tss.historyEnabled=false,tss.wrapsEnabled=false,tss.forceMockSignatures=false,staking.periodMins=16",
        "hapiTestCutover" to
            "tss.hintsEnabled=false,tss.historyEnabled=false,tss.wrapsEnabled=false,tss.initialCrsParties=8,staking.periodMins=16",
        "hapiTestTimeConsumingSerial" to "nodes.nodeRewardsEnabled=false,quiescence.enabled=true",
        "hapiTestStateThrottling" to "nodes.nodeRewardsEnabled=false,quiescence.enabled=true",
        "hapiTestMiscRecords" to
            "blockStream.streamMode=RECORDS,nodes.nodeRewardsEnabled=false,quiescence.enabled=true,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true,hedera.transaction.maximumPermissibleUnhealthySeconds=5",
        "hapiTestMiscRecordsSerial" to
            "blockStream.streamMode=RECORDS,nodes.nodeRewardsEnabled=false,quiescence.enabled=true,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true",
        "hapiTestSimpleFees" to
            "fees.simpleFeesEnabled=true,hedera.transaction.maximumPermissibleUnhealthySeconds=5",
        "hapiTestSimpleFeesSerial" to "fees.simpleFeesEnabled=true",
        "hapiTestNDReconnect" to
            "blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true",
        "hapiTestAtomicBatch" to
            "nodes.nodeRewardsEnabled=false,quiescence.enabled=true,hedera.transaction.maximumPermissibleUnhealthySeconds=5",
        "hapiTestAtomicBatchSerial" to "nodes.nodeRewardsEnabled=false,quiescence.enabled=true",
    )
val prCheckPrepareUpgradeOffsets = mapOf("hapiTestAdhoc" to "PT300S")
val prCheckAssertAtLeastOneWraps = setOf("hapiTestWraps", "hapiTestCutover")
// (FUTURE) Determine what the TSS_LIB_WRAPS_ARTIFACTS_PATH will be for each task in CI; set it here
val prCheckTssLibWrapsArtifactsPaths =
    mapOf("hapiTestWraps" to "", "hapiTestCutover" to "", "hapiTestWrapsDownload" to "data/keys")
// Use to override the default network size for a specific test task
val prCheckNetSizeOverrides =
    mapOf(
        "hapiTestAdhoc" to "3",
        "hapiTestCrypto" to "3",
        "hapiTestCryptoSerial" to "3",
        "hapiTestToken" to "3",
        "hapiTestSimpleFees" to "3",
        "hapiTestSimpleFeesSerial" to "3",
        "hapiTestTokenSerial" to "3",
        "hapiTestSmartContract" to "3",
        "hapiTestSmartContractSerial" to "3",
        "hapiTestAtomicBatch" to "3",
        "hapiTestAtomicBatchSerial" to "3",
        "hapiTestStateThrottling" to "3",
    )

tasks {
    prCheckTags.forEach { (taskName, _) ->
        register(taskName) {
            getByName(taskName).group = "hapi-test"
            dependsOn(
                if (
                    (taskName.contains("Crypto") ||
                        taskName.contains("Token") ||
                        taskName.contains("Misc") ||
                        taskName.contains("TimeConsuming") ||
                        taskName.contains("SimpleFees") ||
                        taskName.contains("AtomicBatch") ||
                        taskName.contains("SmartContract")) && !taskName.contains("Serial")
                )
                    "testSubprocessConcurrent"
                else "testSubprocess"
            )
        }
    }
    remoteCheckTags.forEach { (taskName, _) -> register(taskName) { dependsOn("testRemote") } }
}

tasks.register<Test>("testSubprocess") {
    testClassesDirs = sourceSets.main.get().output.classesDirs
    classpath = configurations.runtimeClasspath.get().plus(files(tasks.jar))
    outputs.upToDateWhen { false } // Don't skip execution of hapi test tasks

    val ciTagExpression =
        gradle.startParameter.taskNames
            .stream()
            .map { prCheckTags[it] ?: "" }
            .filter { it.isNotBlank() }
            .toList()
            .joinToString("|")
    useJUnitPlatform {
        includeTags(
            if (ciTagExpression.isBlank()) "none()|!(EMBEDDED|REPEATABLE)"
            // We don't want to run typical stream or log validation for ISS or BLOCK_NODE
            // cases
            else if (ciTagExpression.contains("ISS") || ciTagExpression.contains("BLOCK_NODE"))
                "(${ciTagExpression})&!(EMBEDDED|REPEATABLE)"
            else "(${ciTagExpression}|STREAM_VALIDATION|LOG_VALIDATION)&!(EMBEDDED|REPEATABLE)"
        )
        excludeTags("CONCURRENT_SUBPROCESS_VALIDATION")
    }

    // Choose a different initial port for each test task if running as PR check
    val initialPort =
        gradle.startParameter.taskNames
            .stream()
            .map { prCheckStartPorts[it] ?: "" }
            .filter { it.isNotBlank() }
            .findFirst()
            .orElse("")
    systemProperty("hapi.spec.initial.port", initialPort)
    // There's nothing special about shard/realm 11.12, except that they are non-zero values.
    // We want to run all tests that execute as part of `testSubprocess`–that is to say,
    // the majority of the hapi tests - with a nonzero shard/realm
    // to maintain confidence that we haven't fallen back into the habit of assuming 0.0
    systemProperty("hapi.spec.default.shard", 11)
    systemProperty("hapi.spec.default.realm", 12)

    // Gather overrides into a single comma‐separated list
    val testOverrides =
        gradle.startParameter.taskNames
            .mapNotNull { prCheckPropOverrides[it] }
            .joinToString(separator = ",")
    // Only set the system property if non-empty
    if (testOverrides.isNotBlank()) {
        systemProperty("hapi.spec.test.overrides", testOverrides)
    }

    if (gradle.startParameter.taskNames.any(prCheckAssertAtLeastOneWraps::contains)) {
        systemProperty("hapi.spec.assertAtLeastOneWraps", "true")
    }
    gradle.startParameter.taskNames
        .firstOrNull(prCheckTssLibWrapsArtifactsPaths::containsKey)
        ?.let {
            systemProperty(
                "hapi.spec.tssLibWrapsArtifactsPath",
                prCheckTssLibWrapsArtifactsPaths.getValue(it),
            )
        }

    val prepareUpgradeOffsets =
        gradle.startParameter.taskNames
            .mapNotNull { prCheckPrepareUpgradeOffsets[it] }
            .joinToString(",")
    if (prepareUpgradeOffsets.isNotEmpty()) {
        systemProperty("hapi.spec.prepareUpgradeOffsets", prepareUpgradeOffsets)
    }

    val networkSize =
        gradle.startParameter.taskNames
            .stream()
            .map { prCheckNetSizeOverrides[it] ?: "" }
            .filter { it.isNotBlank() }
            .findFirst()
            .orElse("4")
    systemProperty("hapi.spec.network.size", networkSize)

    // Note the 1/4 threshold for the restart check; DabEnabledUpgradeTest is a chaotic
    // churn of fast upgrades with heavy use of override networks, and there is a node
    // removal step that happens without giving enough time for the next hinTS scheme
    // to be completed, meaning a 1/3 threshold in the *actual* roster only accounts for
    // 1/4 total weight in the out-of-date hinTS verification key,
    val hintsThresholdDenominator =
        if (gradle.startParameter.taskNames.contains("hapiTestRestart")) "4" else "3"
    systemProperty("hapi.spec.hintsThresholdDenominator", hintsThresholdDenominator)
    systemProperty("hapi.spec.block.stateproof.verification", "false")

    // Default quiet mode is "false" unless we are running in CI or set it explicitly to "true"
    systemProperty(
        "hapi.spec.quiet.mode",
        System.getProperty("hapi.spec.quiet.mode")
            ?: if (ciTagExpression.isNotBlank()) "true" else "false",
    )
    systemProperty("junit.jupiter.execution.parallel.enabled", false)
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    // Surprisingly, the Gradle JUnitPlatformTestExecutionListener fails to gather result
    // correctly if test classes run in parallel (concurrent execution WITHIN a test class
    // is fine). So we need to force the test classes to run in the same thread. Luckily this
    // is not a huge limitation, as our test classes generally have enough non-leaky tests to
    // get a material speed up. See https://github.com/gradle/gradle/issues/6453.
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "same_thread")
    systemProperty(
        "junit.jupiter.testclass.order.default",
        "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
    )

    // Scale heap and processor count to match available resources
    maxHeapSize = testMaxHeap
    // Limit forked node JVM heap to avoid overcommitting container/runner memory
    systemProperty("hapi.spec.node.poolMib", "$nodePoolMib")
    // Fix testcontainers module system access to commons libraries
    // testcontainers 2.0.2 is a named module but doesn't declare its module-info dependencies
    jvmArgs(
        "-XX:ActiveProcessorCount=$testProcessorCount",
        "--add-reads=org.testcontainers=org.apache.commons.lang3",
        "--add-reads=org.testcontainers=org.apache.commons.compress",
        "--add-reads=org.testcontainers=org.apache.commons.io",
        "--add-reads=org.testcontainers=org.apache.commons.codec",
    )
    maxParallelForks = 1
}

tasks.register<Test>("testSubprocessConcurrent") {
    testClassesDirs = sourceSets.main.get().output.classesDirs
    classpath = configurations.runtimeClasspath.get().plus(files(tasks.jar))
    outputs.upToDateWhen { false } // Don't skip execution of hapi test tasks

    val ciTagExpression =
        gradle.startParameter.taskNames
            .stream()
            .map { prCheckTags[it] ?: "" }
            .filter { it.isNotBlank() }
            .toList()
            .joinToString("|")
    useJUnitPlatform {
        includeTags(
            if (ciTagExpression.isBlank()) "none()|!(EMBEDDED|REPEATABLE|ISS)"
            // We don't want to run typical stream or log validation for ISS or BLOCK_NODE
            // cases
            else if (ciTagExpression.contains("ISS") || ciTagExpression.contains("BLOCK_NODE"))
                "(${ciTagExpression})&!(EMBEDDED|REPEATABLE)"
            else "(${ciTagExpression}|CONCURRENT_SUBPROCESS_VALIDATION)&!(EMBEDDED|REPEATABLE|ISS)"
        )
        // Exclude SERIAL tests except CONCURRENT_SUBPROCESS_VALIDATION which runs validation last
        // via @Isolated
        excludeTags("SERIAL&!CONCURRENT_SUBPROCESS_VALIDATION")
    }

    // Choose a different initial port for each test task if running as PR check
    val initialPort =
        gradle.startParameter.taskNames
            .stream()
            .map { prCheckStartPorts[it] ?: "" }
            .filter { it.isNotBlank() }
            .findFirst()
            .orElse("")
    systemProperty("hapi.spec.initial.port", initialPort)
    // There's nothing special about shard/realm 11.12, except that they are non-zero values.
    // We want to run all tests that execute as part of `testSubprocess`–that is to say,
    // the majority of the hapi tests - with a nonzero shard/realm
    // to maintain confidence that we haven't fallen back into the habit of assuming 0.0
    systemProperty("hapi.spec.default.shard", 11)
    systemProperty("hapi.spec.default.realm", 12)

    // Gather overrides into a single comma‐separated list
    val testOverrides =
        gradle.startParameter.taskNames
            .mapNotNull { prCheckPropOverrides[it] }
            .joinToString(separator = ",")
    // Only set the system property if non-empty
    if (testOverrides.isNotBlank()) {
        systemProperty("hapi.spec.test.overrides", testOverrides)
    }

    if (gradle.startParameter.taskNames.any(prCheckAssertAtLeastOneWraps::contains)) {
        systemProperty("hapi.spec.assertAtLeastOneWraps", "true")
    }
    gradle.startParameter.taskNames
        .firstOrNull(prCheckTssLibWrapsArtifactsPaths::containsKey)
        ?.let {
            systemProperty(
                "hapi.spec.tssLibWrapsArtifactsPath",
                prCheckTssLibWrapsArtifactsPaths.getValue(it),
            )
        }

    val prepareUpgradeOffsets =
        gradle.startParameter.taskNames
            .mapNotNull { prCheckPrepareUpgradeOffsets[it] }
            .joinToString(",")
    if (prepareUpgradeOffsets.isNotEmpty()) {
        systemProperty("hapi.spec.prepareUpgradeOffsets", prepareUpgradeOffsets)
    }

    val networkSize =
        gradle.startParameter.taskNames
            .stream()
            .map { prCheckNetSizeOverrides[it] ?: "" }
            .filter { it.isNotBlank() }
            .findFirst()
            .orElse("4")
    systemProperty("hapi.spec.network.size", networkSize)

    // Note the 1/4 threshold for the restart check; DabEnabledUpgradeTest is a chaotic
    // churn of fast upgrades with heavy use of override networks, and there is a node
    // removal step that happens without giving enough time for the next hinTS scheme
    // to be completed, meaning a 1/3 threshold in the *actual* roster only accounts for
    // 1/4 total weight in the out-of-date hinTS verification key,
    val hintsThresholdDenominator =
        if (gradle.startParameter.taskNames.contains("hapiTestRestart")) "4" else "3"
    systemProperty("hapi.spec.hintsThresholdDenominator", hintsThresholdDenominator)
    systemProperty("hapi.spec.block.stateproof.verification", "false")

    // Default quiet mode is "false" unless we are running in CI or set it explicitly to "true"
    systemProperty(
        "hapi.spec.quiet.mode",
        System.getProperty("hapi.spec.quiet.mode")
            ?: if (ciTagExpression.isNotBlank()) "true" else "false",
    )
    // Signal to SharedNetworkLauncherSessionListener that this is subprocess concurrent mode,
    // so it arms the validation latch for ConcurrentSubprocessValidationTest
    systemProperty("hapi.spec.subprocess.concurrent", "true")
    systemProperty("junit.jupiter.execution.parallel.enabled", true)
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
    // Limit concurrent test classes to prevent transaction backlog
    // Use fixed strategy with parallelism based on node count: 3 nodes → 3 threads, 4 nodes → 2
    // threads
    val testParallelism = if ((networkSize.toIntOrNull() ?: 4) <= 3) 3 else 2
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", "$testParallelism")
    systemProperty(
        "junit.jupiter.testclass.order.default",
        "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
    )

    // Scale heap and processor count to match available resources
    maxHeapSize = testMaxHeap
    // Limit forked node JVM heap to avoid overcommitting container/runner memory
    systemProperty("hapi.spec.node.poolMib", "$nodePoolMib")
    // Fix testcontainers module system access to commons libraries
    // testcontainers 2.0.2 is a named module but doesn't declare its module-info dependencies
    jvmArgs(
        "-XX:ActiveProcessorCount=$testProcessorCount",
        "--add-reads=org.testcontainers=org.apache.commons.lang3",
        "--add-reads=org.testcontainers=org.apache.commons.compress",
        "--add-reads=org.testcontainers=org.apache.commons.io",
        "--add-reads=org.testcontainers=org.apache.commons.codec",
    )
    maxParallelForks = 1
}

tasks.register<Test>("testRemote") {
    testClassesDirs = sourceSets.main.get().output.classesDirs
    classpath = configurations.runtimeClasspath.get().plus(files(tasks.jar))
    outputs.upToDateWhen { false } // Don't skip execution of hapi test tasks

    systemProperty("hapi.spec.remote", "true")
    // Support overriding a single remote target network for all executing specs
    System.getenv("REMOTE_TARGET")?.let { systemProperty("hapi.spec.nodes.remoteYml", it) }

    val ciTagExpression =
        gradle.startParameter.taskNames
            .stream()
            .map { remoteCheckTags[it] ?: "" }
            .filter { it.isNotBlank() }
            .toList()
            .joinToString("|")
    useJUnitPlatform {
        includeTags(
            if (ciTagExpression.isBlank()) "none()|!(EMBEDDED|REPEATABLE)"
            else "(${ciTagExpression}&!(EMBEDDED|REPEATABLE))"
        )
    }

    if (gradle.startParameter.taskNames.any(prCheckAssertAtLeastOneWraps::contains)) {
        systemProperty("hapi.spec.assertAtLeastOneWraps", "true")
    }
    gradle.startParameter.taskNames
        .firstOrNull(prCheckTssLibWrapsArtifactsPaths::containsKey)
        ?.let {
            systemProperty(
                "hapi.spec.tssLibWrapsArtifactsPath",
                prCheckTssLibWrapsArtifactsPaths.getValue(it),
            )
        }

    val prepareUpgradeOffsets =
        gradle.startParameter.taskNames
            .mapNotNull { prCheckPrepareUpgradeOffsets[it] }
            .joinToString(",")
    if (prepareUpgradeOffsets.isNotEmpty()) {
        systemProperty("hapi.spec.prepareUpgradeOffsets", prepareUpgradeOffsets)
    }

    // Default quiet mode is "false" unless we are running in CI or set it explicitly to "true"
    systemProperty(
        "hapi.spec.quiet.mode",
        System.getProperty("hapi.spec.quiet.mode")
            ?: if (ciTagExpression.isNotBlank()) "true" else "false",
    )
    systemProperty("junit.jupiter.execution.parallel.enabled", true)
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    // Surprisingly, the Gradle JUnitPlatformTestExecutionListener fails to gather result
    // correctly if test classes run in parallel (concurrent execution WITHIN a test class
    // is fine). So we need to force the test classes to run in the same thread. Luckily this
    // is not a huge limitation, as our test classes generally have enough non-leaky tests to
    // get a material speed up. See https://github.com/gradle/gradle/issues/6453.
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "same_thread")
    systemProperty(
        "junit.jupiter.testclass.order.default",
        "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
    )

    // Scale heap and processor count to match available resources
    maxHeapSize = testMaxHeap
    jvmArgs("-XX:ActiveProcessorCount=$testProcessorCount")
    maxParallelForks = 1
}

val embeddedTasks =
    setOf(
        "hapiTestCryptoEmbedded",
        "hapiTestMiscEmbedded",
        "hapiTestSimpleFeesEmbedded",
        "hapiTestAtomicBatchEmbedded",
    )

val embeddedBaseTags =
    mapOf(
        "hapiTestMiscEmbedded" to "EMBEDDED&!(SIMPLE_FEES|CRYPTO|ATOMIC_BATCH)",
        "hapiTestSimpleFeesEmbedded" to "EMBEDDED&SIMPLE_FEES",
        "hapiTestCryptoEmbedded" to "EMBEDDED&CRYPTO",
        "hapiTestAtomicBatchEmbedded" to "EMBEDDED&ATOMIC_BATCH",
    )

val prEmbeddedCheckTags = embeddedBaseTags.mapValues { (_, tags) -> "($tags)" }

tasks {
    prEmbeddedCheckTags.forEach { (taskName, _) ->
        register(taskName) {
            getByName(taskName).group = "hapi-test-embedded"
            dependsOn("testEmbedded")
        }
    }
}

// Runs tests against an embedded network that supports concurrent tests
tasks.register<Test>("testEmbedded") {
    testClassesDirs = sourceSets.main.get().output.classesDirs
    classpath = configurations.runtimeClasspath.get().plus(files(tasks.jar))
    outputs.upToDateWhen { false } // Don't skip execution of hapi test tasks

    val ciTagExpression =
        gradle.startParameter.taskNames
            .stream()
            .map { prEmbeddedCheckTags[it] ?: "" }
            .filter { it.isNotBlank() }
            .toList()
            .joinToString("|")
    useJUnitPlatform {
        includeTags(
            if (ciTagExpression.isBlank())
                "none()|!(RESTART|ND_RECONNECT|UPGRADE|REPEATABLE|ONLY_SUBPROCESS|ISS)"
            else "(${ciTagExpression}|STREAM_VALIDATION|LOG_VALIDATION)&!(INTEGRATION|ISS)"
        )
    }

    systemProperty("junit.jupiter.execution.parallel.enabled", true)
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    // Surprisingly, the Gradle JUnitPlatformTestExecutionListener fails to gather result
    // correctly if test classes run in parallel (concurrent execution WITHIN a test class
    // is fine). So we need to force the test classes to run in the same thread. Luckily this
    // is not a huge limitation, as our test classes generally have enough non-leaky tests to
    // get a material speed up. See https://github.com/gradle/gradle/issues/6453.
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "same_thread")
    systemProperty(
        "junit.jupiter.testclass.order.default",
        "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
    )
    // Tell our launcher to target a concurrent embedded network
    systemProperty("hapi.spec.embedded.mode", "concurrent")
    // Running all the tests that are executed in testEmbedded with 0 for shard and realm,
    // so we can maintain confidence that there are no regressions in the code.
    systemProperty("hapi.spec.default.shard", 0)
    systemProperty("hapi.spec.default.realm", 0)

    if (gradle.startParameter.taskNames.contains("hapiTestSimpleFeesEmbedded")) {
        systemProperty("fees.createSimpleFeeSchedule", "true")
        systemProperty("fees.simpleFeesEnabled", "true")
    }

    // Scale heap and processor count to match available resources
    maxHeapSize = testMaxHeap
    jvmArgs("-XX:ActiveProcessorCount=$testProcessorCount")
}

val repeatableBaseTags = mapOf("hapiTestMiscRepeatable" to "REPEATABLE&!CRYPTO")

val prRepeatableCheckTags = repeatableBaseTags.mapValues { (_, tags) -> "($tags)" }

tasks {
    prRepeatableCheckTags.forEach { (taskName, _) ->
        register(taskName) { dependsOn("testRepeatable") }
    }
}

// Runs tests against an embedded network that achieves repeatable results by running tests in a
// single thread
tasks.register<Test>("testRepeatable") {
    testClassesDirs = sourceSets.main.get().output.classesDirs
    classpath = configurations.runtimeClasspath.get().plus(files(tasks.jar))
    outputs.upToDateWhen { false } // Don't skip execution of hapi test tasks

    val ciTagExpression =
        gradle.startParameter.taskNames
            .stream()
            .map { prRepeatableCheckTags[it] ?: "" }
            .filter { it.isNotBlank() }
            .toList()
            .joinToString("|")
    useJUnitPlatform {
        includeTags(
            if (ciTagExpression.isBlank())
                "none()|!(RESTART|ND_RECONNECT|UPGRADE|EMBEDDED|NOT_REPEATABLE|ONLY_SUBPROCESS|ISS)"
            else "(${ciTagExpression}|STREAM_VALIDATION|LOG_VALIDATION)&!(INTEGRATION|ISS|EMBEDDED)"
        )
    }

    // Disable all parallelism
    systemProperty("junit.jupiter.execution.parallel.enabled", false)
    systemProperty(
        "junit.jupiter.testclass.order.default",
        "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
    )
    // Tell our launcher to target a repeatable embedded network
    systemProperty("hapi.spec.embedded.mode", "repeatable")

    // Scale heap and processor count to match available resources
    maxHeapSize = testMaxHeap
    jvmArgs("-XX:ActiveProcessorCount=$testProcessorCount")
}

application.mainClass = "com.hedera.services.bdd.suites.SuiteRunner"

tasks.shadowJar { archiveFileName.set("SuiteRunner.jar") }

val rcdiffJar =
    tasks.register<ShadowJar>("rcdiffJar") {
        from(sourceSets["main"].output)
        from(sourceSets["rcdiff"].output)
        destinationDirectory = layout.projectDirectory.dir("rcdiff")
        archiveFileName = "rcdiff.jar"
        configurations = listOf(project.configurations["rcdiffRuntimeClasspath"])

        manifest { attributes("Main-Class" to "com.hedera.services.rcdiff.RcDiffCmdWrapper") }
    }
