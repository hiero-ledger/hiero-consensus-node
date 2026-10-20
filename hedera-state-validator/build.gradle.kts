// SPDX-License-Identifier: Apache-2.0
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer

plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.shadow")
}

mainModuleInfo { runtimeOnly("org.junit.jupiter.engine") }

// besu-util (via the contract service) ships its own Log4j2Plugins.dat; merge it with log4j-core's
// instead of letting the duplicates strategy keep whichever jar comes first on the classpath.
tasks.withType<ShadowJar>().configureEach {
    // The convention plugin fails on unexpected duplicates; let both copies of the log4j
    // plugin cache through so the transformer below can merge them into one.
    filesMatching("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    transform(Log4j2PluginsCacheFileTransformer::class.java)
}

application.mainClass = "com.hedera.statevalidation.StateOperatorCommand"
