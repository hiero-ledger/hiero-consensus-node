// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.gradlex.javamodule.moduleinfo.ExtraJavaModuleInfoPluginExtension
import org.hiero.gradle.transforms.BesuNativePatchTransform

@Suppress("UnstableApiUsage")
gradle.lifecycle.beforeProject {
    plugins.withId("org.hiero.gradle.base.jpms-modules") {
        configure<ExtraJavaModuleInfoPluginExtension> {
            // with the upgrade, besu requires the consensys' fork of the tuweni libraries
            module("io.consensys.tuweni:tuweni-units", "tuweni.units")
            module("io.consensys.tuweni:tuweni-bytes", "tuweni.bytes")
            module("io.vertx:vertx-core", "io.vertx.core")
        }

        val javaModule = Attribute.of("javaModule", Boolean::class.javaObjectType)
        val javaModulePatched = Attribute.of("javaModulePatched", Boolean::class.javaObjectType)

        dependencies.artifactTypes["jar"].attributes.attribute(javaModulePatched, false)

        dependencies.registerTransform(BesuNativePatchTransform::class) {
            from.attribute(ARTIFACT_TYPE_ATTRIBUTE, "jar")
                .attribute(javaModule, true)
                .attribute(javaModulePatched, false)
            to.attribute(ARTIFACT_TYPE_ATTRIBUTE, "jar")
                .attribute(javaModule, true)
                .attribute(javaModulePatched, true)
        }

        the<SourceSetContainer>()
            .named { it != "jmh" }
            .configureEach {
                configurations[runtimeClasspathConfigurationName]
                    .attributes
                    .attribute(javaModulePatched, true)
                configurations[compileClasspathConfigurationName]
                    .attributes
                    .attribute(javaModulePatched, true)
                configurations[annotationProcessorConfigurationName]
                    .attributes
                    .attribute(javaModulePatched, true)
            }
    }
}
