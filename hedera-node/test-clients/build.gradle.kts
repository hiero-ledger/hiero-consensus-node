// SPDX-License-Identifier: Apache-2.0
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.lang.management.ManagementFactory
import org.hiero.gradle.environment.EnvAccess

plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.shadow")
}

description = "Hedera Services Test Clients for End to End Tests (EET)"

// Detect available resources and scale JVM settings accordingly
class TestResourceArgumentsProvider : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val logger =
            org.slf4j.LoggerFactory.getLogger(TestResourceArgumentsProvider::class.java) as Logger
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
                            File("/proc/meminfo").readLines().first { line ->
                                line.startsWith("MemTotal")
                            }
                        memLine.split("\\s+".toRegex())[1].toLong() / 1024.0 / 1024.0
                    }
                } else {
                    val os =
                        ManagementFactory.getOperatingSystemMXBean()
                            as com.sun.management.OperatingSystemMXBean
                    os.totalMemorySize / 1024.0 / 1024.0 / 1024.0
                }
            } catch (_: Exception) {
                16.0
            }

        // Use all available processors but cap at 8 to avoid excessive thread contention
        val testProcessorCount = availableCpus.coerceAtMost(8)
        // Parallelism is set per-task based on actual node count (see testSubprocessConcurrent
        // below)
        // Reserve ~half of total memory for the test client JVM, leave the rest for forked node
        // JVMs and OS
        val testClientHeapGib = (totalMemoryGib / 2).toInt().coerceIn(4, 8)
        val testMaxHeap = "${testClientHeapGib}g"
        // Pass remaining memory pool to ProcessUtils, which divides by actual node count at
        // runtime; HAPI_TEST_NODE_POOL_MIB overrides the computed pool for memory-hungry suites
        // whose peak usage is dominated by native (non-JVM) allocations
        val nodePoolMib =
            System.getenv("HAPI_TEST_NODE_POOL_MIB")?.trim()?.toIntOrNull()
                ?: ((totalMemoryGib - testClientHeapGib) * 1024 * 0.8).toInt().coerceAtLeast(2048)

        logger.lifecycle(
            "Test resource detection: cpus=$availableCpus, totalMem=${String.format("%.1f", totalMemoryGib)}GiB -> processorCount=$testProcessorCount, clientHeap=$testMaxHeap, nodePool=${nodePoolMib}m"
        )

        return listOf(
            // Scale heap and processor count to match available resources
            "-Xmx$testMaxHeap",
            "-XX:ActiveProcessorCount=$testProcessorCount",
            // Limit forked node JVM heap to avoid overcommitting container/runner memory
            "-Dhapi.spec.node.poolMib=$nodePoolMib",
        )
    }
}

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
    mainModule = application.mainModule
    mainClass = providers.gradleProperty("testClient")
}

val miscTags =
    "!(INTEGRATION|CRYPTO|TOKEN|RESTART|UPGRADE|SMART_CONTRACT|ND_RECONNECT|LONG_RUNNING|STATE_THROTTLING|ISS|BLOCK_NODE|GENESIS_SUBPROCESS|SIMPLE_FEES|ATOMIC_BATCH|WRAPS_DOWNLOAD)"
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
            "tss.hintsEnabled=true,tss.historyEnabled=true,tss.wrapsEnabled=true,tss.forceMockSignatures=false,block.stateproof.verification.enabled=true",
        "hapiTestToken" to
            "hedera.transaction.maximumPermissibleUnhealthySeconds=5,platform.wiring.healthLogThreshold=5s",
        "hapiTestCrypto" to
            "tss.forceMockSignatures=false,blockStream.blockPeriod=1s,block.stateproof.verification.enabled=true,hedera.transaction.maximumPermissibleUnhealthySeconds=5,platform.wiring.healthLogThreshold=5s",
        "hapiTestCryptoSerial" to
            "tss.hintsEnabled=true,tss.historyEnabled=true,tss.forceMockSignatures=false,blockStream.blockPeriod=1s,block.stateproof.verification.enabled=true",
        "hapiTestSmartContract" to
            "blockStream.writerMode=FILE_AND_GRPC,blockStream.streamWrappedRecordBlocks=true,tss.historyEnabled=false,hedera.transaction.maximumPermissibleUnhealthySeconds=5",
        "hapiTestSmartContractSerial" to "tss.historyEnabled=false",
        // hapiTestRestart exercises repeated freeze/upgrade/restart cycles. On main this ran with
        // tss.historyEnabled=false by config default; with the new branch default of true the
        // genesis chain-of-trust proof (and its real-crypto signatures, since this test sets
        // forceMockSignatures=false) needs to make progress concurrently with multi-restart
        // pressure. The interaction is fragile enough that DabEnabledUpgradeTest's upgrade flows
        // start timing out as the network falls behind. Pin historyEnabled to its main-branch
        // value here to preserve the test's original (hints-only) TSS surface.
        "hapiTestRestart" to
            "tss.hintsEnabled=true,tss.historyEnabled=false,tss.forceHandoffs=true,tss.forceMockSignatures=false,blockStream.blockPeriod=1s,quiescence.enabled=true,block.stateproof.verification.enabled=true,hedera.transaction.maximumPermissibleUnhealthySeconds=5,platform.wiring.healthLogThreshold=5s",
        "hapiTestWrapsDownload" to
            "tss.wrapsEnabled=true,tss.hintsEnabled=true,tss.forceHandoffs=true,tss.initialCrsParties=16,blockStream.blockPeriod=1s,quiescence.enabled=true,block.stateproof.verification.enabled=true,tss.wrapsProvingKeyPath=data/keys/valid-wraps-proving-key.tar.gz,tss.wrapsProvingKeyHash=76bf521149f6b6a35590b8c9089c40bbd44034c4b30c17fa6ac3537a8a0b4143ebdbff25e156c8c4c1553c11f35769a1",
        "hapiTestMisc" to
            "blockStream.writerMode=FILE_AND_GRPC,blockStream.streamWrappedRecordBlocks=true,nodes.nodeRewardsEnabled=false,quiescence.enabled=true,block.stateproof.verification.enabled=true,hedera.transaction.maximumPermissibleUnhealthySeconds=5,platform.wiring.healthLogThreshold=5s",
        "hapiTestMiscSerial" to
            "nodes.nodeRewardsEnabled=false,quiescence.enabled=true,block.stateproof.verification.enabled=true",
        "hapiTestTimeConsuming" to
            "nodes.nodeRewardsEnabled=false,quiescence.enabled=true,hedera.transaction.maximumPermissibleUnhealthySeconds=5",
        "hapiTestWraps" to
            "tss.hintsEnabled=true,tss.historyEnabled=true,tss.wrapsEnabled=true,tss.forceMockSignatures=false,staking.periodMins=25",
        "hapiTestCutover" to
            "tss.hintsEnabled=false,tss.historyEnabled=false,tss.wrapsEnabled=false,tss.forceMockSignatures=false,tss.initialCrsParties=8,staking.periodMins=25",
        "hapiTestTimeConsumingSerial" to "nodes.nodeRewardsEnabled=false,quiescence.enabled=true",
        "hapiTestStateThrottling" to "nodes.nodeRewardsEnabled=false,quiescence.enabled=true",
        "hapiTestMiscRecords" to
            "blockStream.streamMode=RECORDS,nodes.nodeRewardsEnabled=false,quiescence.enabled=true,block.stateproof.verification.enabled=true,hedera.transaction.maximumPermissibleUnhealthySeconds=5,platform.wiring.healthLogThreshold=5s",
        "hapiTestMiscRecordsSerial" to
            "blockStream.streamMode=RECORDS,nodes.nodeRewardsEnabled=false,quiescence.enabled=true,block.stateproof.verification.enabled=true",
        "hapiTestSimpleFees" to
            "fees.simpleFeesEnabled=true,hedera.transaction.maximumPermissibleUnhealthySeconds=5,hooks.hooksEnabled=true",
        "hapiTestSimpleFeesSerial" to "fees.simpleFeesEnabled=true",
        "hapiTestNDReconnect" to "block.stateproof.verification.enabled=true",
        "hapiTestAtomicBatch" to
            "nodes.nodeRewardsEnabled=false,quiescence.enabled=true,hedera.transaction.maximumPermissibleUnhealthySeconds=5",
        "hapiTestAtomicBatchSerial" to "nodes.nodeRewardsEnabled=false,quiescence.enabled=true",
    )
// hapiTestRestart reconnects the same node repeatedly; the 10m production throttle would starve it.
val prCheckPlatformOverrides =
    mapOf(
        "hapiTestRestart" to
            "platformStatus.observingStatusDelay=10s,reconnect.minimumTimeBetweenReconnects=10s"
    )
val prCheckPrepareUpgradeOffsets = mapOf("hapiTestAdhoc" to "PT300S")
val prCheckAssertAtLeastOneWraps =
    gradle.startParameter.taskNames.any { setOf("hapiTestWraps", "hapiTestCutover").contains(it) }
val prCheckIsSimpleFeesEmbedded =
    gradle.startParameter.taskNames.contains("hapiTestSimpleFeesEmbedded")
// Path to the extracted WRAPS proving-key artifacts (decider_pp.bin, decider_vp.bin,
// nova_pp.bin, nova_vp.bin); blank disables WRAPS proof assertions in the ceremony tests
val tssLibWrapsArtifactsPath = System.getenv("TSS_LIB_WRAPS_ARTIFACTS_PATH") ?: ""
val prCheckTssLibWrapsArtifactsPaths =
    mapOf(
        "hapiTestWraps" to tssLibWrapsArtifactsPath,
        "hapiTestCutover" to tssLibWrapsArtifactsPath,
        "hapiTestWrapsDownload" to "data/keys",
    )
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
        // Each node runs a native WRAPS prover during proof construction; 3 nodes keeps
        // peak memory within the dedicated runner pool's limits
        "hapiTestWraps" to "3",
        "hapiTestCutover" to "3",
        "hapiTestWrapsDownload" to "3",
    )

val embeddedBaseTags =
    mapOf(
        "hapiTestMiscEmbedded" to "EMBEDDED&!(SIMPLE_FEES|CRYPTO|ATOMIC_BATCH)",
        "hapiTestSimpleFeesEmbedded" to "EMBEDDED&SIMPLE_FEES",
        "hapiTestCryptoEmbedded" to "EMBEDDED&CRYPTO",
        "hapiTestAtomicBatchEmbedded" to "EMBEDDED&ATOMIC_BATCH",
    )
val prEmbeddedCheckTags = embeddedBaseTags.mapValues { (_, tags) -> "($tags)" }

val repeatableBaseTags = mapOf("hapiTestMiscRepeatable" to "REPEATABLE&!CRYPTO")
val prRepeatableCheckTags = repeatableBaseTags.mapValues { (_, tags) -> "($tags)" }

// Choose a different initial port for each test task if running as PR check
val initialPort =
    gradle.startParameter.taskNames
        .map { prCheckStartPorts[it] ?: "" }
        .filter { it.isNotBlank() }
        .firstOrNull() ?: ""

// Gather platform-level overrides (settings.txt) into a single comma-separated list
val platformOverrides =
    gradle.startParameter.taskNames
        .mapNotNull { prCheckPlatformOverrides[it] }
        .joinToString(separator = ",")

val networkSize =
    gradle.startParameter.taskNames
        .map { prCheckNetSizeOverrides[it] ?: "" }
        .filter { it.isNotBlank() }
        .firstOrNull() ?: "4"

val prepareUpgradeOffsets =
    gradle.startParameter.taskNames
        .mapNotNull { prCheckPrepareUpgradeOffsets[it] }
        .joinToString(",")

// Gather overrides into a single comma‐separated list
val testOverrides =
    gradle.startParameter.taskNames
        .mapNotNull { prCheckPropOverrides[it] }
        .joinToString(separator = ",")

tasks {
    prCheckTags.forEach { (taskName, _) ->
        register(taskName) {
            group = "hapi-test"
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
    prEmbeddedCheckTags.forEach { (taskName, _) ->
        register(taskName) {
            group = "hapi-test-embedded"
            dependsOn("testEmbedded")
        }
    }
    prRepeatableCheckTags.forEach { (taskName, _) ->
        register(taskName) { dependsOn("testRepeatable") }
    }
}

// Unlike other tests, these intentionally corrupt embedded state to test FAIL_INVALID
// code paths; hence we do not run LOG_VALIDATION after the test suite finishes
tasks.registerHapiTest(
    "test",
    emptyMap(),
    "(INTEGRATION|STREAM_VALIDATION)",
    embeddedMode = "per-class",
    junitParallelMode = "same_thread",
)

tasks.registerHapiTest(
    "testSubprocess",
    prCheckTags,
    "none()|!(EMBEDDED|REPEATABLE)",
    ciDefaultTags = "|CONCURRENT_SUBPROCESS_VALIDATION)&!(EMBEDDED|REPEATABLE|ISS",
    ciDefaultTagsWithoutStreamAndLogValidation = ")&!(EMBEDDED|REPEATABLE",
    excludeTags = "CONCURRENT_SUBPROCESS_VALIDATION",
    junitParallelMode = "same_thread",
    initialPort = initialPort,
    // There's nothing special about shard/realm 11.12, except that they are non-zero values.
    // We want to run all tests that execute as part of `testSubprocess`–that is to say,
    // the majority of the hapi tests - with a nonzero shard/realm
    // to maintain confidence that we haven't fallen back into the habit of assuming 0.0
    defaultShard = 11,
    defaultRealm = 12,
    // Note the 1/4 threshold for the restart check; DabEnabledUpgradeTest is a chaotic
    // churn of fast upgrades with heavy use of override networks, and there is a node
    // removal step that happens without giving enough time for the next hinTS scheme
    // to be completed, meaning a 1/3 threshold in the *actual* roster only accounts for
    // 1/4 total weight in the out-of-date hinTS verification key.
    hapiSpecHintsThresholdDenominator =
        if (gradle.startParameter.taskNames.contains("hapiTestRestart")) "4" else "3",
    hapiSpecBlockStateproofVerificationOff = true,
)

tasks.registerHapiTest(
    "testSubprocessConcurrent",
    prCheckTags,
    "none()|!(EMBEDDED|REPEATABLE|ISS)",
    ciDefaultTags = "|CONCURRENT_SUBPROCESS_VALIDATION)&!(EMBEDDED|REPEATABLE|ISS",
    ciDefaultTagsWithoutStreamAndLogValidation = ")&!(EMBEDDED|REPEATABLE",
    excludeTags = "SERIAL&!CONCURRENT_SUBPROCESS_VALIDATION",
    junitParallelMode = "concurrent",
    junitFixedParallelism = if (networkSize.toInt() <= 3) 3 else 2,
    initialPort = initialPort,
    // There's nothing special about shard/realm 11.12, except that they are non-zero values.
    // We want to run all tests that execute as part of `testSubprocess`–that is to say,
    // the majority of the hapi tests - with a nonzero shard/realm
    // to maintain confidence that we haven't fallen back into the habit of assuming 0.0
    defaultShard = 11,
    defaultRealm = 12,
    // Note the 1/4 threshold for the restart check; DabEnabledUpgradeTest is a chaotic
    // churn of fast upgrades with heavy use of override networks, and there is a node
    // removal step that happens without giving enough time for the next hinTS scheme
    // to be completed, meaning a 1/3 threshold in the *actual* roster only accounts for
    // 1/4 total weight in the out-of-date hinTS verification key.
    hapiSpecSubprocessConcurrent = true,
    hapiSpecHintsThresholdDenominator =
        if (gradle.startParameter.taskNames.contains("hapiTestRestart")) "4" else "3",
    hapiSpecBlockStateproofVerificationOff = true,
)

tasks.registerHapiTest(
    "testRemote",
    remoteCheckTags,
    "none()|!(EMBEDDED|REPEATABLE)",
    ciDefaultTags = "&!(EMBEDDED|REPEATABLE)",
    junitParallelMode = "same_thread",
    hapiSpecRemote = true,
)

// Runs tests against an embedded network that supports concurrent tests
tasks.registerHapiTest(
    "testEmbedded",
    prEmbeddedCheckTags,
    "none()|!(RESTART|ND_RECONNECT|UPGRADE|REPEATABLE|ONLY_SUBPROCESS|ISS)",
    ciDefaultTags = "|STREAM_VALIDATION|LOG_VALIDATION)&!(INTEGRATION|ISS",
    // Tell our launcher to target a concurrent embedded network
    embeddedMode = "concurrent",
    junitParallelMode = "same_thread",
    // Running all the tests that are executed in testEmbedded with 0 for shard and realm,
    // so we can maintain confidence that there are no regressions in the code.
    defaultShard = 0,
    defaultRealm = 0,
)

// Runs tests against an embedded network that achieves repeatable results by running tests in a
// single thread
tasks.registerHapiTest(
    "testRepeatable",
    prRepeatableCheckTags,
    "none()|!(RESTART|ND_RECONNECT|UPGRADE|EMBEDDED|NOT_REPEATABLE|ONLY_SUBPROCESS|ISS)",
    ciDefaultTags = "|STREAM_VALIDATION|LOG_VALIDATION)&!(INTEGRATION|ISS|EMBEDDED",
    embeddedMode = "repeatable",
)

fun TaskContainer.registerHapiTest(
    name: String,
    ciTags: Map<String, String>,
    defaultTags: String,
    ciDefaultTags: String? = null,
    ciDefaultTagsWithoutStreamAndLogValidation: String? = null,
    excludeTags: String? = null,
    embeddedMode: String? = null,
    // Surprisingly, the Gradle JUnitPlatformTestExecutionListener fails to gather result
    // correctly if test classes run in parallel (concurrent execution WITHIN a test class
    // is fine). So we need to force the test classes to run in the same thread. Luckily this
    // is not a huge limitation, as our test classes generally have enough non-leaky tests to
    // get a material speed up. See https://github.com/gradle/gradle/issues/6453.
    // That's why parallel mode is set to 'same_thread' for certain cases
    junitParallelMode: String? = null,
    junitFixedParallelism: Int? = null,
    initialPort: String? = null,
    defaultShard: Int? = null,
    defaultRealm: Int? = null,
    hapiSpecSubprocessConcurrent: Boolean = false,
    hapiSpecHintsThresholdDenominator: String? = null,
    hapiSpecBlockStateproofVerificationOff: Boolean = false,
    hapiSpecRemote: Boolean = false,
) {
    (if (name == "test") test else register<Test>(name)).configure {
        val ciTagExpression =
            gradle.startParameter.taskNames
                .map { ciTags[it] ?: "" }
                .filter { it.isNotBlank() }
                .toList()
                .joinToString("|")

        val subtaskName =
            gradle.startParameter.taskNames.firstOrNull { ciTags.containsKey(it) } ?: ""

        // Shared configuration of all test tasks
        testClassesDirs = sourceSets.main.get().output.classesDirs
        classpath = configurations.testRuntimeClasspath.get().plus(files(jar))

        if (!EnvAccess.isCiServer(providers))
            doNotTrackState("Don't skip execution of hapi test tasks locally")

        // Scale heap and processor count to match available resources
        jvmArgumentProviders.add(TestResourceArgumentsProvider())
        // Enable native access for hedera cryptography modules
        jvmArgs(
            "--enable-native-access=" +
                "com.hedera.common.nativesupport," +
                "com.hedera.cryptography.libsecp256k1," +
                "com.hedera.cryptography.libsodium"
        )
        // Isolate each subtask's working directory so logs are not overwritten
        if (subtaskName.isNotBlank()) {
            systemProperty("hapi.spec.subtask.name", subtaskName)
        }
        if (testOverrides.isNotBlank()) {
            systemProperty("hapi.spec.test.overrides", testOverrides)
        }
        if (platformOverrides.isNotBlank()) {
            systemProperty("hapi.spec.platform.overrides", platformOverrides)
        }
        if (prepareUpgradeOffsets.isNotBlank()) {
            systemProperty("hapi.spec.prepareUpgradeOffsets", prepareUpgradeOffsets)
        }
        if (prCheckAssertAtLeastOneWraps) {
            systemProperty("hapi.spec.assertAtLeastOneWraps", "true")
        }
        if (prCheckIsSimpleFeesEmbedded) {
            systemProperty("fees.createSimpleFeeSchedule", "true")
            systemProperty("fees.simpleFeesEnabled", "true")
        }
        systemProperty("hapi.spec.network.size", networkSize)
        // Default quiet mode is "false" unless we are running in CI or set it explicitly to "true"
        systemProperty(
            "hapi.spec.quiet.mode",
            providers
                .systemProperty("hapi.spec.quiet.mode")
                .getOrElse(if (ciTagExpression.isNotBlank()) "true" else "false"),
        )
        gradle.startParameter.taskNames
            .firstOrNull(prCheckTssLibWrapsArtifactsPaths::containsKey)
            ?.let {
                systemProperty(
                    "hapi.spec.tssLibWrapsArtifactsPath",
                    prCheckTssLibWrapsArtifactsPaths.getValue(it),
                )
            }
        // Pass a system property "KEY=VALUE" to the test JVM via "-PsysProp.KEY=VALUE"
        providers.gradlePropertiesPrefixedBy("sysProp.").get().forEach { (k, v) ->
            systemProperty(k.removePrefix("sysProp."), v)
        }

        // Configuration controlled by parameters
        useJUnitPlatform {
            if (ciDefaultTags == null) {
                includeTags(defaultTags)
            } else {
                includeTags(
                    if (ciTagExpression.isBlank()) defaultTags
                    // We don't want to run stream or log validation for ISS or BLOCK_NODE cases
                    else if (
                        ciDefaultTagsWithoutStreamAndLogValidation != null &&
                            (ciTagExpression.contains("ISS") ||
                                ciTagExpression.contains("BLOCK_NODE"))
                    )
                        "(${ciTagExpression}${ciDefaultTagsWithoutStreamAndLogValidation})"
                    else "(${ciTagExpression}${ciDefaultTags})"
                )
            }
            if (excludeTags != null) {
                excludeTags(excludeTags)
            }
        }
        if (junitParallelMode != null) {
            systemProperty("junit.jupiter.execution.parallel.enabled", true)
            systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
            systemProperty(
                "junit.jupiter.execution.parallel.mode.classes.default",
                junitParallelMode,
            )
            systemProperty(
                "junit.jupiter.testclass.order.default",
                "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
            )
        }
        if (junitFixedParallelism != null) {
            systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
            systemProperty(
                "junit.jupiter.execution.parallel.config.fixed.parallelism",
                "$junitFixedParallelism",
            )
        }
        if (embeddedMode != null) {
            systemProperty("hapi.spec.embedded.mode", embeddedMode)
        }
        if (initialPort != null) {
            systemProperty("hapi.spec.initial.port", initialPort)
        }
        if (defaultShard != null) {
            systemProperty("hapi.spec.default.shard", defaultShard)
        }
        if (defaultRealm != null) {
            systemProperty("hapi.spec.default.realm", defaultRealm)
        }
        if (hapiSpecSubprocessConcurrent) {
            systemProperty("hapi.spec.subprocess.concurrent", "true")
        }
        if (hapiSpecHintsThresholdDenominator != null) {
            systemProperty("hapi.spec.hintsThresholdDenominator", hapiSpecHintsThresholdDenominator)
        }
        if (hapiSpecBlockStateproofVerificationOff) {
            systemProperty("hapi.spec.block.stateproof.verification", "false")
        }
        if (hapiSpecRemote) {
            systemProperty("hapi.spec.remote", "true")
            // Support overriding a single remote target network for all executing specs
            System.getenv("REMOTE_TARGET")?.let { systemProperty("hapi.spec.nodes.remoteYml", it) }
        }
    }
}

application.mainClass = "com.hedera.services.bdd.suites.SuiteRunner"

tasks.shadowJar {
    archiveFileName.set("SuiteRunner.jar")
    // Declares JNI usage (netty's NativeLibraryUtil) so the JDK does not print a
    // restricted-method warning for callers in the unnamed module of this JAR
    // when launched via `java -jar`.
    manifest { attributes("Enable-Native-Access" to "ALL-UNNAMED") }
}

val rcdiffJar =
    tasks.register<ShadowJar>("rcdiffJar") {
        from(sourceSets["main"].output)
        from(sourceSets["rcdiff"].output)
        destinationDirectory = layout.projectDirectory.dir("rcdiff")
        archiveFileName = "rcdiff.jar"
        configurations = listOf(project.configurations["rcdiffRuntimeClasspath"])

        manifest {
            attributes(
                "Main-Class" to "com.hedera.services.rcdiff.RcDiffCmdWrapper",
                // Declares JNI usage (netty's NativeLibraryUtil) so the JDK does not print a
                // restricted-method warning for callers in the unnamed module of this JAR.
                "Enable-Native-Access" to "ALL-UNNAMED",
            )
        }
    }
