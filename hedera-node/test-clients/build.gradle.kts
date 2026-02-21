// SPDX-License-Identifier: Apache-2.0
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.shadow")
}

description = "Hedera Services Test Clients for End to End Tests (EET)"

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

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
}

val miscTags =
    "!(INTEGRATION|CRYPTO|TOKEN|RESTART|UPGRADE|SMART_CONTRACT|ND_RECONNECT|LONG_RUNNING|ISS|BLOCK_NODE|SIMPLE_FEES|ATOMIC_BATCH)"
val matsSuffix = "MATS"

val basePrCheckTags =
    mapOf(
        "hapiTestAdhoc" to "ADHOC",
        "hapiTestCrypto" to "CRYPTO",
        "hapiTestCryptoSerial" to "(CRYPTO&SERIAL)",
        "hapiTestToken" to "TOKEN",
        "hapiTestRestart" to "RESTART|UPGRADE",
        "hapiTestSmartContract" to "SMART_CONTRACT",
        "hapiTestNDReconnect" to "ND_RECONNECT",
        "hapiTestTimeConsuming" to "LONG_RUNNING",
        "hapiTestIss" to "ISS",
        "hapiTestBlockNodeCommunication" to "BLOCK_NODE",
        "hapiTestMisc" to miscTags,
        "hapiTestMiscRecords" to miscTags,
        "hapiTestSimpleFees" to "SIMPLE_FEES",
        "hapiTestAtomicBatch" to "ATOMIC_BATCH",
    )

val cryptoTasks = setOf("hapiTestCrypto", "hapiTestCryptoSerial")

val prCheckTags =
    buildMap<String, String> {
        basePrCheckTags.forEach { (task, tags) ->

            // XTS task → explicitly EXCLUDE MATS
            put(task, "($tags)&(!MATS)")

            // MATS task → explicitly REQUIRE MATS
            if (task !in cryptoTasks) {
                put("$task$matsSuffix", "($tags)&MATS")
            }
        }
    }

val remoteCheckTags =
    prCheckTags
        .filterNot {
            it.key in
                listOf(
                    "hapiTestIss",
                    "hapiTestIssMATS",
                    "hapiTestRestart",
                    "hapiTestRestartMATS",
                    "hapiTestToken",
                    "hapiTestTokenMATS",
                )
        }
        .mapKeys { (key, _) -> key.replace("hapiTest", "remoteTest") }
val prCheckStartPorts =
    buildMap<String, String> {
        put("hapiTestAdhoc", "25000")
        put("hapiTestCrypto", "25200")
        put("hapiTestToken", "25400")
        put("hapiTestRestart", "25600")
        put("hapiTestSmartContract", "25800")
        put("hapiTestNDReconnect", "26000")
        put("hapiTestTimeConsuming", "26200")
        put("hapiTestIss", "26400")
        put("hapiTestMisc", "26800")
        put("hapiTestBlockNodeCommunication", "27000")
        put("hapiTestMiscRecords", "27200")
        put("hapiTestAtomicBatch", "27400")
        put("hapiTestCryptoSerial", "27600")

        // Create the MATS variants
        val originalEntries = toMap() // Create a snapshot of current entries
        originalEntries.forEach { (taskName: String, port: String) ->
            if (taskName !in cryptoTasks) put("$taskName$matsSuffix", port)
        }
    }
val prCheckPropOverrides =
    buildMap<String, String> {
        put(
            "hapiTestAdhoc",
            "tss.hintsEnabled=true,tss.historyEnabled=true,tss.wrapsEnabled=true,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true",
        )
        put(
            "hapiTestCrypto",
            "tss.hintsEnabled=true,tss.historyEnabled=true,tss.wrapsEnabled=false,blockStream.blockPeriod=1s,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true",
        )
        put(
            "hapiTestCryptoSerial",
            "tss.hintsEnabled=true,tss.historyEnabled=true,tss.wrapsEnabled=false,blockStream.blockPeriod=1s,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true",
        )
        put("hapiTestSmartContract", "tss.historyEnabled=false")
        put(
            "hapiTestRestart",
            "tss.hintsEnabled=true,tss.forceHandoffs=true,tss.initialCrsParties=16,blockStream.blockPeriod=1s,quiescence.enabled=true,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true",
        )
        put(
            "hapiTestMisc",
            "nodes.nodeRewardsEnabled=false,quiescence.enabled=true,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true",
        )
        put("hapiTestTimeConsuming", "nodes.nodeRewardsEnabled=false,quiescence.enabled=true")
        put(
            "hapiTestMiscRecords",
            "blockStream.streamMode=RECORDS,nodes.nodeRewardsEnabled=false,quiescence.enabled=true,blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true",
        )
        put("hapiTestSimpleFees", "fees.simpleFeesEnabled=true")
        put(
            "hapiTestNDReconnect",
            "blockStream.enableStateProofs=true,block.stateproof.verification.enabled=true",
        )
        put("hapiTestAtomicBatch", "nodes.nodeRewardsEnabled=false,quiescence.enabled=true")

        val originalEntries = toMap() // Create a snapshot of current entries
        originalEntries.forEach { (taskName: String, overrides: String) ->
            if (taskName !in cryptoTasks) put("$taskName$matsSuffix", overrides)
        }
    }
val prCheckPrepareUpgradeOffsets =
    buildMap<String, String> {
        put("hapiTestAdhoc", "PT300S")

        val originalEntries = toMap() // Create a snapshot of current entries
        originalEntries.forEach { (taskName: String, offset: String) ->
            if (taskName !in cryptoTasks) put("$taskName$matsSuffix", offset)
        }
    }
// Note: no MATS variants needed for history proofs
val prCheckNumHistoryProofsToObserve = mapOf("hapiTestAdhoc" to "0", "hapiTestSmartContract" to "0")
// Use to override the default network size for a specific test task
val prCheckNetSizeOverrides =
    buildMap<String, String> {
        put("hapiTestAdhoc", "3")
        put("hapiTestCrypto", "3")
        put("hapiTestCryptoSerial", "3")
        put("hapiTestToken", "3")
        put("hapiTestSmartContract", "4")

        val originalEntries = toMap() // Create a snapshot of current entries
        originalEntries.forEach { (taskName: String, size: String) ->
            if (taskName !in cryptoTasks) put("$taskName$matsSuffix", size)
        }
    }

tasks {
    prCheckTags.forEach { (taskName, _) ->
        register(taskName) {
            getByName(taskName).group =
                "hapi-test${if (taskName.endsWith(matsSuffix)) "-mats" else ""}"
            dependsOn(
                if (taskName.contains("Crypto") && !taskName.contains("Serial"))
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

    val maxHistoryProofsToObserve =
        gradle.startParameter.taskNames
            .mapNotNull { prCheckNumHistoryProofsToObserve[it]?.toIntOrNull() }
            .maxOrNull()
    if (maxHistoryProofsToObserve != null) {
        systemProperty("hapi.spec.numHistoryProofsToObserve", maxHistoryProofsToObserve.toString())
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

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
    maxParallelForks = 1
    modularity.inferModulePath.set(false)
}

tasks.register<Test>("testSubprocessConcurrent") {
    testClassesDirs = sourceSets.main.get().output.classesDirs
    classpath = configurations.runtimeClasspath.get().plus(files(tasks.jar))

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

    val maxHistoryProofsToObserve =
        gradle.startParameter.taskNames
            .mapNotNull { prCheckNumHistoryProofsToObserve[it]?.toIntOrNull() }
            .maxOrNull()
    if (maxHistoryProofsToObserve != null) {
        systemProperty("hapi.spec.numHistoryProofsToObserve", maxHistoryProofsToObserve.toString())
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
    // Use fixed strategy with limited parallelism to balance speed and stability
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", "4")
    systemProperty(
        "junit.jupiter.testclass.order.default",
        "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
    )

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
    maxParallelForks = 1
    modularity.inferModulePath.set(false)
}

tasks.register<Test>("testRemote") {
    testClassesDirs = sourceSets.main.get().output.classesDirs
    classpath = configurations.runtimeClasspath.get().plus(files(tasks.jar))

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

    val maxHistoryProofsToObserve =
        gradle.startParameter.taskNames
            .mapNotNull { prCheckNumHistoryProofsToObserve[it]?.toIntOrNull() }
            .maxOrNull()
    if (maxHistoryProofsToObserve != null) {
        systemProperty("hapi.spec.numHistoryProofsToObserve", maxHistoryProofsToObserve.toString())
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

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
    maxParallelForks = 1
}

val embeddedCryptoTasks = setOf("hapiTestCryptoEmbedded")

val embeddedBaseTags =
    mapOf(
        "hapiEmbeddedMisc" to "EMBEDDED&!(SIMPLE_FEES|CRYPTO)",
        "hapiEmbeddedSimpleFees" to "EMBEDDED&SIMPLE_FEES",
        "hapiTestCryptoEmbedded" to "EMBEDDED&CRYPTO",
    )

val prEmbeddedCheckTags =
    buildMap<String, String> {
        embeddedBaseTags.forEach { (taskName, tags) ->
            // XTS embedded → all tests
            put(taskName, "($tags)")

            // Embedded MATS variant → REQUIRE MATS
            if (taskName !in embeddedCryptoTasks) {
                put("$taskName$matsSuffix", "($tags)&MATS")
            }
        }
    }

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

    if (
        gradle.startParameter.taskNames.contains("hapiEmbeddedSimpleFees") ||
            gradle.startParameter.taskNames.contains("hapiEmbeddedSimpleFeesMATS")
    ) {
        systemProperty("fees.createSimpleFeeSchedule", "true")
        systemProperty("fees.simpleFeesEnabled", "true")
    }

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
    modularity.inferModulePath.set(false)
}

val repeatableBaseTags = mapOf("hapiRepeatableMisc" to "REPEATABLE")

val prRepeatableCheckTags =
    buildMap<String, String> {
        repeatableBaseTags.forEach { (taskName, tags) ->

            // XTS repeatable → EXCLUDE MATS
            put(taskName, "($tags)&(!MATS)")

            // Repeatable MATS variant → REQUIRE MATS
            put("$taskName$matsSuffix", "($tags)&MATS")
        }
    }

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

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
    modularity.inferModulePath.set(false)
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
