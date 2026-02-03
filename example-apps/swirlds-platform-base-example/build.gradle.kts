// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.application") }

mainModuleInfo {
    annotationProcessor("com.swirlds.config.processor")
    runtimeOnly("com.swirlds.config.impl")
}

application.mainClass = "com.swirlds.platform.base.example.Application"
