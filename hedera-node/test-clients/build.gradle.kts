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

val prCheckTags =
    mapOf(
        "hapiTestAdhoc" to "ADHOC",
        "hapiTestCrypto" to "CRYPTO",
        "hapiTestToken" to "TOKEN",
        "hapiTestRestart" to "RESTART|UPGRADE",
        "hapiTestSmartContract" to "SMART_CONTRACT",
        "hapiTestNDReconnect" to "ND_RECONNECT",
        "hapiTestTimeConsuming" to "LONG_RUNNING",
        "hapiTestIss" to "ISS",
        "hapiTestMisc" to
            "!(INTEGRATION|CRYPTO|TOKEN|RESTART|UPGRADE|SMART_CONTRACT|ND_RECONNECT|LONG_RUNNING|ISS|BLOCK_NODE_SIMULATOR)",
    )
val remoteCheckTags =
    prCheckTags
        .filterNot { it.key in listOf("hapiTestIss", "hapiTestRestart", "hapiTestToken") }
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
        "hapiTestIss" to "26400",
        "hapiTestMisc" to "26800",
    )
val prCheckPropOverrides =
    mapOf(
        "hapiTestAdhoc" to
            "tss.hintsEnabled=false,tss.forceHandoffs=false,tss.initialCrsParties=16,blockStream.blockPeriod=2s",
        "hapiTestCrypto" to "tss.hintsEnabled=true,blockStream.blockPeriod=1s",
        "hapiTestSmartContract" to "tss.historyEnabled=false",
        "hapiTestRestart" to
            "tss.hintsEnabled=true,tss.forceHandoffs=true,tss.initialCrsParties=16,blockStream.blockPeriod=1s",
        "hapiTestMisc" to "nodes.nodeRewardsEnabled=false",
        "hapiTestTimeConsuming" to "nodes.nodeRewardsEnabled=false",
    )
val prCheckPrepareUpgradeOffsets = mapOf("hapiTestAdhoc" to "PT300S")
val prCheckNumHistoryProofsToObserve = mapOf("hapiTestAdhoc" to "0", "hapiTestSmartContract" to "0")
// Use to override the default network size for a specific test task
val prCheckNetSizeOverrides =
    mapOf(
        "hapiTestAdhoc" to "3",
        "hapiTestCrypto" to "3",
        "hapiTestToken" to "3",
        "hapiTestSmartContract" to "4",
    )

tasks {
    prCheckTags.forEach { (taskName, _) ->
        register(taskName) {
            getByName(taskName).group = "hapi-test"
            dependsOn("testSubprocess")
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
            if (ciTagExpression.isBlank()) "none()|!(EMBEDDED|REPEATABLE|ISS)"
            // We don't want to run typical stream or log validation for an ISS case
            else if (ciTagExpression.contains("ISS")) "(${ciTagExpression})&!(EMBEDDED|REPEATABLE)"
            else "(${ciTagExpression}|STREAM_VALIDATION|LOG_VALIDATION)&!(EMBEDDED|REPEATABLE|ISS)"
        )
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

val prEmbeddedCheckTags = mapOf("hapiEmbeddedMisc" to "EMBEDDED")

tasks {
    prEmbeddedCheckTags.forEach { (taskName, _) ->
        register(taskName) { dependsOn("testEmbedded") }
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

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
    modularity.inferModulePath.set(false)
}

val prRepeatableCheckTags = mapOf("hapiRepeatableMisc" to "REPEATABLE")

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

// allow shadow Jar files to have more than 64k entries
tasks.withType<ShadowJar>().configureEach { isZip64 = true }

tasks.shadowJar { archiveFileName.set("SuiteRunner.jar") }

val rcdiffJar =
    tasks.register<ShadowJar>("rcdiffJar") {
        exclude(listOf("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF", "META-INF/INDEX.LIST"))
        from(sourceSets["main"].output)
        from(sourceSets["rcdiff"].output)
        destinationDirectory.set(project.file("rcdiff"))
        archiveFileName.set("rcdiff.jar")
        configurations = listOf(project.configurations.getByName("rcdiffRuntimeClasspath"))

        manifest { attributes("Main-Class" to "com.hedera.services.rcdiff.RcDiffCmdWrapper") }
    }

val validationJar =
    tasks.register<ShadowJar>("validationJar") {
        exclude(listOf("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF", "META-INF/INDEX.LIST"))
        from(sourceSets["main"].output)
        archiveFileName.set("ValidationScenarios.jar")

        manifest {
            attributes(
                "Main-Class" to
                    "com.hedera.services.bdd.suites.utils.validation.ValidationScenarios"
            )
        }
    }

val copyValidation =
    tasks.register<Copy>("copyValidation") {
        group = "copy"
        from(validationJar)
        into(project.file("validation-scenarios"))
    }

val cleanValidation =
    tasks.register<Delete>("cleanValidation") {
        group = "copy"
        delete(File(project.file("validation-scenarios"), "ValidationScenarios.jar"))
    }

tasks.clean { dependsOn(cleanValidation) }
