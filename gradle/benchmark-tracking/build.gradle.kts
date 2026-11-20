// SPDX-License-Identifier: Apache-2.0
plugins { `kotlin-dsl` }

repositories { gradlePluginPortal() }

dependencies {
    // the JMH plugin that org.hiero.gradle.feature.benchmark applies; the versions must match
    implementation("me.champeau.jmh:jmh-gradle-plugin:0.7.3")
}
