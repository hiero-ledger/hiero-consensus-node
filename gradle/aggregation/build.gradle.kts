// SPDX-License-Identifier: Apache-2.0
dependencies {
    // Products in this repository that are published to Maven Central.
    // For each product, the entry point app/service module needs to be listed here.
    // All other required modules of that product are automatically published as well.
    published(project(":app")) // products 'hedera-node' and 'platform-sdk'
    published(project(":hedera-protobuf-java-api")) // product 'hapi'
    published(project(":openmetrics-httpserver")) // product 'hiero-observability'

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
