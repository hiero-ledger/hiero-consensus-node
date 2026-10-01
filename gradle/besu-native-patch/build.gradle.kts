// SPDX-License-Identifier: Apache-2.0
plugins { `java-gradle-plugin` }

repositories { gradlePluginPortal() }

dependencies {
    compileOnly("net.java.dev.jna:jna:5.19.1")
}

gradlePlugin {
    plugins {
        register("besuNativePatch") {
            id = "org.hiero.gradle.feature.besu-native-patch"
            implementationClass = "org.hiero.gradle.feature.BesuNativePatchPlugin"
        }
    }
}