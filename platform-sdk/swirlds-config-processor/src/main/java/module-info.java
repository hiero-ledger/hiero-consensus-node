// SPDX-License-Identifier: Apache-2.0
module com.swirlds.config.processor {
    provides javax.annotation.processing.Processor with
            com.swirlds.config.processor.ConfigDataAnnotationProcessor;

    requires transitive org.antlr.antlr4.runtime;
    requires com.swirlds.config.api;
    requires com.squareup.javapoet;
    requires java.compiler;
    requires static transitive com.github.spotbugs.annotations;
    requires static transitive com.google.auto.service;
}
