// SPDX-License-Identifier: Apache-2.0
import org.gradle.internal.os.OperatingSystem
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradlex.javamodule.dependencies.dsl.GradleOnlyDirectives

plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-integration")
    id("org.hiero.gradle.feature.protobuf")
}

description = "Consensus Performance Framework"

testFixturesModuleInfo {
    runtimeOnly("io.netty.transport.epoll.linux.x86_64")
    runtimeOnly("io.netty.transport.epoll.linux.aarch_64")
    runtimeOnly("org.hiero.consensus.event.intake.concurrent")
    runtimeOnly("io.helidon.grpc.core")
    runtimeOnly("io.helidon.webclient")
    runtimeOnly("io.helidon.webclient.grpc")
    runtimeOnly("io.grpc.netty.shaded")
}

// ACCP classifier jars are self-contained JPMS modules that all declare the same module
// name (com.amazon.corretto.crypto.provider), so only ONE classifier can be on any given
// module path. We have two targets:
//   - test-worker JVM: host-matching classifier (selected at configure time)
//   - Docker child JVM: always Linux, shipped into build/data/lib/ via copyDockerizedApp
val hostAccpClassifier = run {
    val os = OperatingSystem.current()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch == "aarch64" || arch == "arm64"
    when {
        os.isMacOsX && isArm -> "osx-aarch_64"
        os.isMacOsX -> "osx-x86_64"
        os.isLinux && isArm -> "linux-aarch_64"
        os.isLinux -> "linux-x86_64"
        else -> error("Unsupported host OS for ACCP: ${os.name} $arch")
    }
}

// Docker classifier must match the container's CPU arch. Docker Desktop runs containers
// in the host's native arch by default (arm64 on Apple Silicon, x86_64 elsewhere). Override
// with -PaccpDockerClassifier=linux-x86_64 if you force qemu-emulated x86 on Apple Silicon.
val dockerAccpClassifier: String = providers.gradleProperty("accpDockerClassifier").orNull
    ?: when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "linux-aarch_64"
        else -> "linux-x86_64"
    }

// Separate resolvable configuration for the Docker bundle — decoupled from
// testFixturesRuntimeClasspath so it can carry a different classifier.
val accpDockerBundle by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    // Test-worker JVM: host-matching classifier so ACCP's Loader finds the right native lib.
    testFixturesRuntimeOnly("software.amazon.cryptools:AmazonCorrettoCryptoProvider:2.5.0:$hostAccpClassifier")

    // Docker child JVM: matches the container's arch (auto-detected from host arch).
    accpDockerBundle("software.amazon.cryptools:AmazonCorrettoCryptoProvider:2.5.0:$dockerAccpClassifier")
}

tasks.testFixturesJar {
    inputs.files(configurations.testFixturesRuntimeClasspath)
    manifest { attributes("Main-Class" to "org.hiero.sloth.fixtures.container.docker.DockerMain") }
    doFirst {
        manifest.attributes(
            "Class-Path" to
                inputs.files
                    .filter { it.extension == "jar" }
                    .map { "../lib/" + it.name }
                    .sorted()
                    .joinToString(separator = " ")
        )
    }
}

tasks.register<Sync>("copyDockerizedApp") {
    into(layout.buildDirectory.dir("data"))
    from(layout.projectDirectory.file("src/testFixtures/docker/Dockerfile"))
    into("apps") {
        from(tasks.testFixturesJar)
        rename { "DockerApp.jar" }
    }
    into("lib") {
        // Everything from the runtime classpath EXCEPT the host-classifier ACCP jar —
        // a macOS .dylib would not load inside the Linux container.
        from(configurations.testFixturesRuntimeClasspath) {
            exclude("AmazonCorrettoCryptoProvider-*.jar")
        }
        // Substitute in the Linux classifier for the container.
        from(accpDockerBundle)
    }
}

tasks.assemble { dependsOn("copyDockerizedApp") }

@Suppress("UnstableApiUsage")
testing {
    suites.register<JvmTestSuite>("testPerformance") {
        // Runs performance benchmarks against the container environment
        targets.configureEach {
            testTask {
                systemProperty("sloth.env", "container")
                dependsOn("copyDockerizedApp")
            }
        }
    }
}

testModuleInfo { requiresStatic("com.github.spotbugs.annotations") }

extensions.getByName<GradleOnlyDirectives>("testPerformanceModuleInfo").apply {
    runtimeOnly("io.grpc.netty.shaded")
}

// Fix testcontainers module system access to commons libraries
// testcontainers 2.0.2 is a named module but doesn't declare its module-info dependencies
// We need to grant it access to the commons modules via JVM arguments
// Note: automatic modules are named from their package names (org.apache.commons.io for commons-io
// JAR)
// This is applied to all Test tasks to work across all execution methods (local, CI, etc.)
tasks.withType<Test>().configureEach {
    maxHeapSize = "8g"
    jvmArgs(
        "--add-reads=org.testcontainers=org.apache.commons.lang3",
        "--add-reads=org.testcontainers=org.apache.commons.compress",
        "--add-reads=org.testcontainers=org.apache.commons.io",
        "--add-reads=org.testcontainers=org.apache.commons.codec",
    )
}

// This should probably not be necessary (Log4j issue?)
// https://github.com/apache/logging-log4j2/pull/3053
tasks.compileTestFixturesJava {
    options.compilerArgs.add("-Alog4j.graalvm.groupId=${project.group}")
    options.compilerArgs.add("-Alog4j.graalvm.artifactId=${project.name}")
}

// Disable Jacoco (code coverage) for performance tests to avoid overhead
// Also ensure performance tests always run (never cached/skipped)
tasks.named<Test>("testPerformance") {
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
    outputs.upToDateWhen { false } // Always run, never consider up-to-date

    // Allow filtering experiments via -PtestFilter="*.SomeExperiment"
    providers.gradleProperty("testFilter").orNull?.let { filter ->
        this.filter.includeTestsMatching(filter)
    }

    // Forward sloth.* project properties from the Gradle command line to the test JVM.
    // Allows overriding BenchmarkParameters from the command line, e.g.:
    //   ./gradlew :consensus-sloth:testPerformance -Psloth.tps=100 -Psloth.benchmarkTime=60s
    // sloth.env is excluded here as it is already set by the test suite configuration above.
    // Note: project properties (-P) are used instead of system properties (-D) because -D flags
    // are set on the Gradle client JVM and are not reliably forwarded to the daemon's
    // System.getProperties().
    project.properties
        .filterKeys { it.startsWith("sloth.") && it != "sloth.env" }
        .forEach { (key, value) -> systemProperty(key, value.toString()) }
}
