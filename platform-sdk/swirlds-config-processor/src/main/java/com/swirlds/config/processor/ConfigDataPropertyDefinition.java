// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Metadata for a config property definition.
 * @param fieldName   the field name like "maxSize". For a property of a nested config data object this is the component
 *                    of the config data record that leads to the nested record, which is what the expansion looks the
 *                    component up by, so it is not the component that declares the property. Use
 *                    {@code declaringComponent} to name the property itself.
 * @param name         the full name like "com.swirlds.config.foo.bar"
 * @param type        the type like "int"
 * @param defaultValue the default value like "100"
 * @param description the description like "the maximum size"
 * @param declaringComponent the record component that declares the property, or null when the config data record
 *                    that is being processed declares it itself
 */
public record ConfigDataPropertyDefinition(
        @NonNull String fieldName,
        @NonNull String name,
        @NonNull String type,
        @Nullable String defaultValue,
        @Nullable String description,
        @Nullable DeclaringComponent declaringComponent) {

    /**
     * The record component that declares a property, which is what a generated constant refers to.
     * <p>
     * A component holding a nested config data object is not a property of its own, so a property of such an object is
     * declared by the nested record rather than by the config data record that uses it. The same nested record can be
     * used several times below one config data record, each time under a different property name, so the declaring
     * component alone does not identify a property either - it is only what the property is documented as.
     *
     * @param recordClassName the qualified name of the record that declares the property, like "com.example.LeafConfig"
     * @param componentName   the name of the record component that declares the property, like "maxSize"
     */
    public record DeclaringComponent(
            @NonNull String recordClassName, @NonNull String componentName) {}
}
