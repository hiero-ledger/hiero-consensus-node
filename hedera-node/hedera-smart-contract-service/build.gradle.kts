// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Hedera Smart Contract Service API"

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
var compilerArgsExtra = ""

if (JavaVersion.current() >= JavaVersion.VERSION_25) {
    compilerArgsExtra += ",-dangling-doc-comments"
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-exports" + compilerArgsExtra)
}
