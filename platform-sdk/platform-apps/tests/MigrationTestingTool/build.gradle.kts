// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.application") }

application.mainClass = "com.swirlds.demo.migration.MigrationTestingToolMain"

testModuleInfo {
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
}
