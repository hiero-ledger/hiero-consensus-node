// SPDX-License-Identifier: Apache-2.0
package org.hiero.gradle.feature;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.initialization.Settings;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradlex.javamodule.moduleinfo.ExtraJavaModuleInfoPluginExtension;
import org.hiero.gradle.transforms.BesuNativePatchTransform;

@SuppressWarnings("UnstableApiUsage")
public abstract class BesuNativePatchPlugin implements Plugin<Settings> {

    @Override
    public void apply(Settings settings) {
        settings.getGradle().getLifecycle().beforeProject(BesuNativePatchPlugin::configureProject);
    }

    private static void configureProject(Project project) {
        project.getPlugins().withId("org.hiero.gradle.base.jpms-modules", plugin -> {
            configureExtraModuleInfo(project);
            configureTransform(project);
        });
    }

    private static void configureExtraModuleInfo(Project project) {
        project.getExtensions().configure(ExtraJavaModuleInfoPluginExtension.class, ext -> {
            ext.module("io.consensys.tuweni:tuweni-bytes", "tuweni.bytes");
            ext.module("io.consensys.tuweni:tuweni-units", "tuweni.units");
            ext.module("io.vertx:vertx-core", "io.vertx.core");
        });
    }

    private static void configureTransform(Project project) {
        Attribute<Boolean> javaModule = Attribute.of("javaModule", Boolean.class);
        Attribute<Boolean> javaModulePatched = Attribute.of("javaModulePatched", Boolean.class);

        project.getDependencies().getArtifactTypes().getByName("jar")
                .getAttributes().attribute(javaModulePatched, false);

        project.getDependencies().registerTransform(BesuNativePatchTransform.class, spec -> {
            spec.getFrom()
                    .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
                    .attribute(javaModule, true)
                    .attribute(javaModulePatched, false);
            spec.getTo()
                    .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
                    .attribute(javaModule, true)
                    .attribute(javaModulePatched, true);
        });

        project.getPlugins().withId("java", p ->
                project.getExtensions().getByType(SourceSetContainer.class)
                        .matching(ss -> !ss.getName().equals("jmh"))
                        .configureEach(ss -> {
                            project.getConfigurations().getByName(ss.getRuntimeClasspathConfigurationName())
                                    .getAttributes().attribute(javaModulePatched, true);
                            project.getConfigurations().getByName(ss.getCompileClasspathConfigurationName())
                                    .getAttributes().attribute(javaModulePatched, true);
                            project.getConfigurations().getByName(ss.getAnnotationProcessorConfigurationName())
                                    .getAttributes().attribute(javaModulePatched, true);
                        })
        );
    }
}