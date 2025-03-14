/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins { id("org.hiero.gradle.module.application") }

mainModuleInfo {
    runtimeOnly("com.swirlds.platform.core")
    runtimeOnly("com.swirlds.platform.core.test.fixtures")
    runtimeOnly("com.swirlds.merkle")
    runtimeOnly("com.swirlds.merkle.test.fixtures")
}

application.mainClass.set("com.swirlds.platform.Browser")

tasks.jar {
    // Gradle fails to track 'configurations.runtimeClasspath' as an input to the task if it is
    // only used in the 'manifest.attributes'. Hence, we explicitly add it as input.
    inputs.files(configurations.runtimeClasspath)

    archiveVersion.convention(null as String?)
    doFirst {
        manifest {
            attributes(
                "Class-Path" to
                    inputs.files
                        .filter { it.extension == "jar" }
                        .map { "data/lib/" + it.name }
                        .sorted()
                        .joinToString(separator = " ")
            )
        }
    }
}

// Copy this app () and the demo apps into 'sdk' folder
val demoApp = configurations.dependencyScope("demoApp")

dependencies {
    demoApp(project(":AddressBookTestingTool"))
    demoApp(project(":ConsistencyTestingTool"))
    demoApp(project(":CryptocurrencyDemo"))
    demoApp(project(":HelloSwirldDemo"))
    demoApp(project(":ISSTestingTool"))
    demoApp(project(":MigrationTestingTool"))
    demoApp(project(":PlatformTestingTool"))
    demoApp(project(":StatsDemo"))
    demoApp(project(":StatsSigningTestingTool"))
    demoApp(project(":StressTestingTool"))
}

val demoAppsRuntimeClasspath =
    configurations.resolvable("demoAppsRuntimeClasspath") {
        extendsFrom(demoApp.get())
        shouldResolveConsistentlyWith(configurations.mainRuntimeClasspath.get())
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attributes.attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            objects.named(LibraryElements.JAR),
        )
        attributes.attribute(Attribute.of("javaModule", Boolean::class.javaObjectType), true)
    }
val demoAppsJars =
    configurations.resolvable("demoAppsJars") {
        extendsFrom(demoApp.get(), configurations.internal.get())
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        isTransitive = false // only the application Jars, not the dependencies
    }

tasks.register<Copy>("copyApps") {
    destinationDir = layout.projectDirectory.dir("../sdk").asFile
    from(tasks.jar) // 'swirlds.jar' goes in directly into 'sdk'
    into("data/apps") {
        // Copy built jar into `data/apps` and rename
        from(demoAppsJars)
        rename { "${it.substring(0, it.indexOf("-"))}.jar" }
    }
    into("data/lib") {
        // Copy dependencies into `sdk/data/lib`
        from(project.configurations.runtimeClasspath)
        from(demoAppsRuntimeClasspath.get().minus(demoAppsJars.get()))
    }
}

tasks.assemble { dependsOn(tasks.named("copyApps")) }
