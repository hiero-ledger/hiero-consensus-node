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
        put("hapiTestClpr", "CLPR")
        put("hapiTestMultiNetwork", "MULTINETWORK")
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
        put("hapiTestClpr", "27800")
        put("hapiTestMultiNetwork", "28000")

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
        put(
            "hapiTestClpr",
            "clpr.clprEnabled=true,clpr.devModeEnabled=true,clpr.connectionFrequency=100",
        )
        put("hapiTestMultiNetwork", "clpr.clprEnabled=true,clpr.devModeEnabled=true")

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
val normalizedInvokedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }

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
        normalizedInvokedTaskNames
            .map { prCheckTags[it] ?: "" }
            .filter { it.isNotBlank() }
            .joinToString("|")
    useJUnitPlatform {
        includeTags(
            if (ciTagExpression.isBlank()) "none()|!(EMBEDDED|REPEATABLE|ISS)"
            // We don't want to run typical stream or log validation for ISS or BLOCK_NODE
            // cases
            else if (ciTagExpression.contains("ISS") || ciTagExpression.contains("BLOCK_NODE"))
                "(${ciTagExpression})&!(EMBEDDED|REPEATABLE)"
            else if (ciTagExpression.contains("CLPR")) "(CLPR)"
            else if (ciTagExpression.contains("MULTINETWORK")) "(MULTINETWORK)"
            else "(${ciTagExpression}|STREAM_VALIDATION|LOG_VALIDATION)&!(EMBEDDED|REPEATABLE|ISS)"
        )
        excludeTags("CONCURRENT_SUBPROCESS_VALIDATION")
    }
    val commandLineIncludePatterns =
        try {
            @Suppress("UNCHECKED_CAST")
            filter::class.java.getMethod("getCommandLineIncludePatterns").invoke(filter)
                as Set<String>
        } catch (_: Exception) {
            emptySet()
        }
    val filterPatterns = filter.includePatterns + commandLineIncludePatterns
    val taskArgs = gradle.startParameter.taskRequests.flatMap { it.args }
    fun containsClprPattern(value: String?) = value?.contains("CLPR", ignoreCase = true) == true
    val testFiltersClpr = filterPatterns.any(::containsClprPattern)
    val testsArgWithEquals =
        taskArgs
            .filter { it.startsWith("--tests=") }
            .map { it.substringAfter("=") }
            .any(::containsClprPattern)
    val testsArgWithSeparatePattern =
        taskArgs.withIndex().any { (index, arg) ->
            arg == "--tests" && containsClprPattern(taskArgs.getOrNull(index + 1))
        }
    val testSingleProperty =
        taskArgs
            .filter { it.startsWith("-Dtest.single=") }
            .map { it.substringAfter("=") }
            .any(::containsClprPattern)
    val commandLineRequestsClpr =
        testsArgWithEquals || testsArgWithSeparatePattern || testSingleProperty
    val shouldEnableClpr =
        ciTagExpression.contains("CLPR") || testFiltersClpr || commandLineRequestsClpr
    val shouldEnableClprDevMode = shouldEnableClpr || ciTagExpression.contains("MULTINETWORK")
    if (shouldEnableClprDevMode) {
        systemProperty("clpr.devModeEnabled", "true")
    }
    if (shouldEnableClpr) {
        systemProperty("clpr.clprEnabled", "true")
        systemProperty("clpr.publicizeNetworkAddresses", "true")
        systemProperty(
            "clpr.connectionFrequency",
            System.getProperty("clpr.connectionFrequency", "5000"),
        )
    }

    // Choose a different initial port for each test task if running as PR check
    val initialPort =
        normalizedInvokedTaskNames
            .map { prCheckStartPorts[it] ?: "" }
            .filter { it.isNotBlank() }
            .firstOrNull() ?: ""
    systemProperty("hapi.spec.initial.port", initialPort)
    // There's nothing special about shard/realm 11.12, except that they are non-zero values.
    // We want to run all tests that execute as part of `testSubprocess`–that is to say,
    // the majority of the hapi tests - with a nonzero shard/realm
    // to maintain confidence that we haven't fallen back into the habit of assuming 0.0
    systemProperty("hapi.spec.default.shard", 11)
    systemProperty("hapi.spec.default.realm", 12)

    // Gather overrides into a single comma‐separated list
    val testOverrides =
        normalizedInvokedTaskNames
            .mapNotNull { prCheckPropOverrides[it] }
            .joinToString(separator = ",")
    // If a CLPR-targeted test is selected from a non-CLPR task (for example hapiTestMultiNetwork +
    // --tests Clpr...),
    // don't force-disable CLPR in subprocess env overrides.
    val effectiveTestOverrides =
        if (!shouldEnableClpr) {
            testOverrides
        } else {
            testOverrides
                .split(",")
                .map { it.trim() }
                .filterNot { it.equals("clpr.clprEnabled=false", ignoreCase = true) }
                .joinToString(",")
        }
    // Only set the system property if non-empty
    if (effectiveTestOverrides.isNotBlank()) {
        systemProperty("hapi.spec.test.overrides", effectiveTestOverrides)
    }

    val maxHistoryProofsToObserve =
        normalizedInvokedTaskNames
            .mapNotNull { prCheckNumHistoryProofsToObserve[it]?.toIntOrNull() }
            .maxOrNull()
    if (maxHistoryProofsToObserve != null) {
        systemProperty("hapi.spec.numHistoryProofsToObserve", maxHistoryProofsToObserve.toString())
    }

    val prepareUpgradeOffsets =
        normalizedInvokedTaskNames.mapNotNull { prCheckPrepareUpgradeOffsets[it] }.joinToString(",")
    if (prepareUpgradeOffsets.isNotEmpty()) {
        systemProperty("hapi.spec.prepareUpgradeOffsets", prepareUpgradeOffsets)
    }

    val networkSize =
        normalizedInvokedTaskNames
            .map { prCheckNetSizeOverrides[it] ?: "" }
            .filter { it.isNotBlank() }
            .firstOrNull() ?: "4"
    systemProperty("hapi.spec.network.size", networkSize)

    // Note the 1/4 threshold for the restart check; DabEnabledUpgradeTest is a chaotic
    // churn of fast upgrades with heavy use of override networks, and there is a node
    // removal step that happens without giving enough time for the next hinTS scheme
    // to be completed, meaning a 1/3 threshold in the *actual* roster only accounts for
    // 1/4 total weight in the out-of-date hinTS verification key,
    val hintsThresholdDenominator =
        if (normalizedInvokedTaskNames.contains("hapiTestRestart")) "4" else "3"
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
        normalizedInvokedTaskNames
            .map { prCheckTags[it] ?: "" }
            .filter { it.isNotBlank() }
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
        normalizedInvokedTaskNames
            .map { prCheckStartPorts[it] ?: "" }
            .filter { it.isNotBlank() }
            .firstOrNull() ?: ""
    systemProperty("hapi.spec.initial.port", initialPort)
    // There's nothing special about shard/realm 11.12, except that they are non-zero values.
    // We want to run all tests that execute as part of `testSubprocess`–that is to say,
    // the majority of the hapi tests - with a nonzero shard/realm
    // to maintain confidence that we haven't fallen back into the habit of assuming 0.0
    systemProperty("hapi.spec.default.shard", 11)
    systemProperty("hapi.spec.default.realm", 12)

    // Gather overrides into a single comma‐separated list
    val testOverrides =
        normalizedInvokedTaskNames
            .mapNotNull { prCheckPropOverrides[it] }
            .joinToString(separator = ",")
    // Only set the system property if non-empty
    if (testOverrides.isNotBlank()) {
        systemProperty("hapi.spec.test.overrides", testOverrides)
    }

    val maxHistoryProofsToObserve =
        normalizedInvokedTaskNames
            .mapNotNull { prCheckNumHistoryProofsToObserve[it]?.toIntOrNull() }
            .maxOrNull()
    if (maxHistoryProofsToObserve != null) {
        systemProperty("hapi.spec.numHistoryProofsToObserve", maxHistoryProofsToObserve.toString())
    }

    val prepareUpgradeOffsets =
        normalizedInvokedTaskNames.mapNotNull { prCheckPrepareUpgradeOffsets[it] }.joinToString(",")
    if (prepareUpgradeOffsets.isNotEmpty()) {
        systemProperty("hapi.spec.prepareUpgradeOffsets", prepareUpgradeOffsets)
    }

    val networkSize =
        normalizedInvokedTaskNames
            .map { prCheckNetSizeOverrides[it] ?: "" }
            .filter { it.isNotBlank() }
            .firstOrNull() ?: "4"
    systemProperty("hapi.spec.network.size", networkSize)

    // Note the 1/4 threshold for the restart check; DabEnabledUpgradeTest is a chaotic
    // churn of fast upgrades with heavy use of override networks, and there is a node
    // removal step that happens without giving enough time for the next hinTS scheme
    // to be completed, meaning a 1/3 threshold in the *actual* roster only accounts for
    // 1/4 total weight in the out-of-date hinTS verification key,
    val hintsThresholdDenominator =
        if (normalizedInvokedTaskNames.contains("hapiTestRestart")) "4" else "3"
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
        normalizedInvokedTaskNames
            .map { remoteCheckTags[it] ?: "" }
            .filter { it.isNotBlank() }
            .joinToString("|")
    useJUnitPlatform {
        includeTags(
            if (ciTagExpression.isBlank()) "none()|!(EMBEDDED|REPEATABLE)"
            else "(${ciTagExpression}&!(EMBEDDED|REPEATABLE))"
        )
    }

    val maxHistoryProofsToObserve =
        normalizedInvokedTaskNames
            .mapNotNull { prCheckNumHistoryProofsToObserve[it]?.toIntOrNull() }
            .maxOrNull()
    if (maxHistoryProofsToObserve != null) {
        systemProperty("hapi.spec.numHistoryProofsToObserve", maxHistoryProofsToObserve.toString())
    }

    val prepareUpgradeOffsets =
        normalizedInvokedTaskNames.mapNotNull { prCheckPrepareUpgradeOffsets[it] }.joinToString(",")
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
        "hapiEmbeddedMisc" to "EMBEDDED&!(SIMPLE_FEES)",
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
        normalizedInvokedTaskNames
            .map { prEmbeddedCheckTags[it] ?: "" }
            .filter { it.isNotBlank() }
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
        normalizedInvokedTaskNames.contains("hapiEmbeddedSimpleFees") ||
            normalizedInvokedTaskNames.contains("hapiEmbeddedSimpleFeesMATS")
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
        normalizedInvokedTaskNames
            .map { prRepeatableCheckTags[it] ?: "" }
            .filter { it.isNotBlank() }
            .joinToString("|")
    useJUnitPlatform {
        includeTags(
            if (ciTagExpression.isBlank())
                "none()|!(RESTART|ND_RECONNECT|UPGRADE|EMBEDDED|NOT_REPEATABLE|ONLY_SUBPROCESS|ISS)"
            else "(${ciTagExpression}|STREAM_VALIDATION|LOG_VALIDATION)&!(INTEGRATION|ISS)"
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
