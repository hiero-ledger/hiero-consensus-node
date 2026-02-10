// SPDX-License-Identifier: Apache-2.0
dependencies {
    implementation(project(":hiero-metrics"))
    implementation(project(":openmetrics-httpserver"))
    published(project(":app"))
    published(project(":hedera-protobuf-java-api"))
    published(project(":app-service-contract-impl"))
    // examples that also contain tests we would like to run
    implementation(project(":swirlds-platform-base-example"))
    // projects that only contain tests (and no production code)
    implementation(project(":test-clients"))
    implementation(project(":consensus-otter-docker-app"))
    implementation(project(":consensus-otter-tests"))
}

tasks.testCodeCoverageReport {
    // Redo the setup done in 'JacocoReportAggregationPlugin', but gather the class files in the
    // file tree and filter out selected classes by path.
    val filteredClassFiles =
        configurations.aggregateCodeCoverageReportResults
            .get()
            .incoming
            .artifactView {
                componentFilter { id -> id is ProjectComponentIdentifier }
                attributes.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements.CLASSES),
                )
            }
            .files
            .asFileTree
            .filter { file ->
                listOf("test-clients", "testFixtures", "example-apps").none {
                    file.path.contains(it)
                }
            }
    classDirectories.setFrom(filteredClassFiles)
}
