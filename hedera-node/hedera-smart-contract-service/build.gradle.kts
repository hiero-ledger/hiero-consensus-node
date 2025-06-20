// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Hedera Smart Contract Service API"

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-exports") }
