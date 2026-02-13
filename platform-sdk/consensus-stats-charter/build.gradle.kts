// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.application") }

application.mainClass = "org.hiero.consensus.stats.charter.StatsCharter"

extraJavaModuleInfo {
    module("org.jfree:jfreechart", "org.jfree.jfreechart") {
        requires("java.desktop")
        exportAllPackages()
    }
}
