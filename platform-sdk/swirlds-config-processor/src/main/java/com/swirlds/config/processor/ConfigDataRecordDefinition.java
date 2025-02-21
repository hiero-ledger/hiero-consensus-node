// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Metadata of a config data record (a Java Record that is annotated by @ConfigData).
 *
 * @param packageName         the package name like "com.swirlds.config.foo"
 * @param simpleClassName     the simple class name like "FooConfig"
 * @param configDataName      the prefix that is used for config properties that are part of the config data like
 *                            "state" for properties that start with "state." like "state.maxSize".
 * @param propertyDefinitions the property definitions
 */
public record ConfigDataRecordDefinition(
        @NonNull String packageName,
        @NonNull String simpleClassName,
        @NonNull String configDataName,
        @NonNull Set<ConfigDataPropertyDefinition> propertyDefinitions) {}
