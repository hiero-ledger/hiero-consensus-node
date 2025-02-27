// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.application") }

val sdkDir = layout.projectDirectory.dir("sdk")

tasks.named<JavaExec>("run") {
    workingDir = sdkDir.asFile
    mainClass = "com.swirlds.platform.Browser"
    classpath = sdkDir.asFileTree.matching { include("*.jar") }
    jvmArgs = listOf("-agentlib:jdwp=transport=dt_socket,address=8888,server=y,suspend=n")
    maxHeapSize = "8g"

    // Build everything for the 'sdk' folder
    dependsOn(":swirlds:assemble")
}

val cleanRun =
    tasks.register<Delete>("cleanRun") {
        delete(
            sdkDir.asFileTree.matching {
                include("settingsUsed.txt")
                include("swirlds.jar")
                include("metricsDoc.tsv")
                include("*.csv")
                include("*.log")
            }
        )

        val dataDir = sdkDir.dir("data")
        delete(dataDir.dir("accountBalances"))
        delete(dataDir.dir("apps"))
        delete(dataDir.dir("lib"))
        delete(dataDir.dir("recordstreams"))
        delete(dataDir.dir("saved"))
    }

tasks.clean { dependsOn(cleanRun) }

tasks.checkModuleDirectivesScope { this.enabled = false }
