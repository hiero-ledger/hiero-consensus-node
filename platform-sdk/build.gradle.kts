// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.application") }

// Disable checkModuleDirectivesScope task if it exists
afterEvaluate { tasks.findByName("checkModuleDirectivesScope")?.enabled = false }
