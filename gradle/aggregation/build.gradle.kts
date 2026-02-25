// SPDX-License-Identifier: Apache-2.0
dependencies {
    // Products in this repository that are published to Maven Central.
    // For each product, the entry point app/service module needs to be listed here.
    // All other required modules of that product are automatically published as well.
    published(project(":app")) // products 'hedera-node' and 'platform-sdk'
    published(project(":hedera-protobuf-java-api")) // product 'hapi'
    published(project(":hiero-metrics")) // product 'hiero-observability'
    published(project(":openmetrics-httpserver"))

    // examples that also contain tests we would like to run
    implementation(project(":swirlds-platform-base-example"))
    // projects that only contain tests (and no production code)
    implementation(project(":test-clients"))
    implementation(project(":consensus-otter-docker-app"))
    implementation(project(":consensus-otter-tests"))
}

// HTML code coverage report containing complete repository coverage
tasks.testCodeCoverageReport {
    reports {
        html.required = true
        xml.required = false
    }
    classDirectories.setFrom(filteredClassFiles())
}

// XML code coverage report split into multiple files for codacy and codecov upload
val testCodeCoverageReportPartitioned = tasks.register("testCodeCoverageReportPartitioned")

javaModuleDependencies.allLocalModules().forEach { module ->
    val testCodeCoverageReportForModule =
        tasks.register<JacocoReport>("testCodeCoverageReport_" + module.moduleName) {
            reports {
                html.required = false
                xml.required = true
                // use a naming pattern expected by codecov: jacoco*.xml
                xml.outputLocation.set(
                    layout.buildDirectory.file("reports/jacoco-xml/jacoco_${module.moduleName}.xml")
                )
            }
            executionData.from(aggregatedExecutionData())
            classDirectories.from(filteredClassFiles(module.projectPath))
        }

    testCodeCoverageReportPartitioned { dependsOn(testCodeCoverageReportForModule) }
}

// Redo the setup done in 'JacocoReportAggregationPlugin', but gather the class files in the
// file tree and filter out selected classes by path.
fun filteredClassFiles(projectPath: String? = null) =
    configurations.aggregateCodeCoverageReportResults
        .get()
        .incoming
        .artifactView {
            componentFilter { id ->
                id is ProjectComponentIdentifier &&
                    (projectPath == null || projectPath == id.projectPath)
            }
            attributes.attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                objects.named(LibraryElements.CLASSES),
            )
        }
        .files
        .asFileTree
        .filter { file ->
            listOf("test-clients", "testFixtures", "example-apps").none { file.path.contains(it) }
        }

// execution data setup copied from
// https://github.com/gradle/gradle/blob/afb1ef26efebbb52509e70d20bc89b8964f5daa0/platforms/jvm/jacoco/src/main/java/org/gradle/testing/jacoco/plugins/JacocoReportAggregationPlugin.java#L109-L120
@Suppress("UnstableApiUsage")
fun aggregatedExecutionData() =
    configurations.aggregateCodeCoverageReportResults
        .get()
        .incoming
        .artifactView {
            withVariantReselection()
            componentFilter { id -> id is ProjectComponentIdentifier }
            attributes {
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.VERIFICATION))
                attribute(TestSuiteName.TEST_SUITE_NAME_ATTRIBUTE, objects.named("test"))
                attribute(
                    VerificationType.VERIFICATION_TYPE_ATTRIBUTE,
                    objects.named(VerificationType.JACOCO_RESULTS),
                )
                attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    ArtifactTypeDefinition.BINARY_DATA_TYPE,
                )
            }
        }
        .files
