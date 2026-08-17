// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.unreadable;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.NestedConfig;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.annotation.ConstraintMethod;
import com.swirlds.config.api.validation.annotation.Min;

/**
 * A config data object whose value can not be read by the config reflection.
 * <p>
 * This package is not exported to {@code com.swirlds.config.extensions}, where the reflection runs, which is what makes
 * every accessor below inaccessible to it. It is exported to {@code com.swirlds.config.impl} at runtime by the test, so
 * that the record is still created normally.
 */
@ConfigData("unreadable")
public record UnreadableConfig(UnreadableLeaf leaf) {

    /**
     * A nested config data object below the unreadable record. Its properties can only be found by walking into the
     * record, which is what the accessibility decides about.
     */
    @NestedConfig
    public record UnreadableLeaf(
            @ConfigProperty(defaultValue = "plain") String plain,
            @ConfigProperty(defaultValue = "2") long count) {}

    /**
     * The same shape with a constraint on one of the properties of the nested record. A constraint can only be checked
     * against a value, so this one can not be checked at all.
     */
    @ConfigData("constrained")
    public record ConstrainedConfig(ConstrainedLeaf leaf) {

        /**
         * @param constrained a property whose value has to be read for its constraint to be checked
         */
        @NestedConfig
        public record ConstrainedLeaf(
                @ConfigProperty(defaultValue = "1") @Min(0) long constrained) {}
    }

    /**
     * The same shape with a constraint method instead of a value constraint. A constraint method is invoked on the
     * record instance that declares the property, which is the very thing that can not be reached here, so it has to
     * fail for the same reason and with the same message as a constraint that reads a value.
     */
    @ConfigData("constrainedByMethod")
    public record ConstrainedByMethodConfig(ConstrainedByMethodLeaf leaf) {

        /**
         * @param constrained a property whose constraint is checked by a method of the record declaring it
         */
        @NestedConfig
        public record ConstrainedByMethodLeaf(
                @ConfigProperty(defaultValue = "1") @ConstraintMethod("checkConstrained")
                long constrained) {

            public ConfigViolation checkConstrained(final Configuration configuration) {
                return null;
            }
        }
    }

    /**
     * A config data object that defines a property name a readable record defines as well, so that the export has to
     * decide between the two rather than reporting both.
     * <p>
     * The property is a scalar of this record instead of one of a nested record, so it is the invocation of the accessor
     * that fails rather than the walk into a component. Both are ways for a value to be unreadable and only the latter
     * is covered by the rest of this fixture.
     */
    @ConfigData("shared")
    public record SharedUnreadableConfig(
            @ConfigProperty(defaultValue = "5") int value) {}
}
