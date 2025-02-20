// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Metadata for a config property definition.
 * @param fieldName   the field name like "maxSize"
 * @param name         the full name like "com.swirlds.config.foo.bar"
 * @param type        the type like "int"
 * @param defaultValue the default value like "100"
 * @param description the description like "the maximum size"
 */
public record ConfigDataPropertyDefinition(
        @NonNull String fieldName,
        @NonNull String name,
        @NonNull String type,
        @Nullable String defaultValue,
        @Nullable String description) {}
