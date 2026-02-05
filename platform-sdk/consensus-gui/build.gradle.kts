// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Consensus GUI - Hashgraph visualization and debugging tools"

// Suppress exports warnings - GUI classes expose types from hashgraph-impl which has targeted
// exports.
// Consumers of consensus-gui are expected to also require hashgraph-impl directly.
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-exports") }
