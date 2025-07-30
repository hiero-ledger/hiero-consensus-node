// SPDX-License-Identifier: Apache-2.0
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
    demoApp(project(":ConsistencyTestingTool"))
    demoApp(project(":ISSTestingTool"))
    demoApp(project(":MigrationTestingTool"))
    demoApp(project(":PlatformTestingTool"))
    demoApp(project(":StatsDemo"))
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
