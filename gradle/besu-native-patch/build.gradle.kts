// SPDX-License-Identifier: Apache-2.0
plugins { `kotlin-dsl` }

repositories { gradlePluginPortal() }

dependencies {
    // Only needed for Gradle to compile the build configuration and the patched code.
    // The versions defined here are not actually used in a running build.
    compileOnly("org.gradlex:extra-java-module-info:1.13.1")
    compileOnly("net.java.dev.jna:jna:5.18.1")
}
