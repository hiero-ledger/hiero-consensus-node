// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the property name to constant name conversion of {@link ConstantClassFactory}.
 * <p>
 * The generated constants are what the rest of the code refers to a property by, so a change here renames a name that
 * other code already uses. The mapping is therefore spelled out rather than left to be re-derived from the loop.
 */
class ConstantClassFactoryTest {

    /**
     * A camel case boundary, a dot and an underscore are all one and the same word separator, so all of these collapse
     * onto the same constant name. Two properties of one record that do that are reported by
     * {@link ConstantClassFactory#doWork} instead of generating a class that declares the same field twice, which
     * {@code NestedRecordProcessorTest#constantNameClashIsReported} covers end to end.
     */
    @Test
    void everyWordSeparatorProducesOneUnderscore() {
        assertEquals("FOO_BAR", ConstantClassFactory.toConstantName("fooBar"));
        assertEquals("FOO_BAR", ConstantClassFactory.toConstantName("foo.bar"));
        assertEquals("FOO_BAR", ConstantClassFactory.toConstantName("foo.Bar"));
        assertEquals("FOO_BAR", ConstantClassFactory.toConstantName("foo_bar"));
        assertEquals("FOO_BAR", ConstantClassFactory.toConstantName("foo_Bar"));
        assertEquals("FOO_BAR", ConstantClassFactory.toConstantName("FooBar"));
    }

    @Test
    void namesWithoutAmbiguityAreConverted() {
        assertEquals("FOO", ConstantClassFactory.toConstantName("foo"));
        assertEquals("A", ConstantClassFactory.toConstantName("a"));
        assertEquals("FOO_BAR_BAZ", ConstantClassFactory.toConstantName("fooBarBaz"));
        assertEquals("FOO_BAR_BAZ", ConstantClassFactory.toConstantName("foo.bar.baz"));
    }

    /**
     * An empty segment is not a name any property can have, so nothing has to be done about the double underscore it
     * produces. It is pinned only so that it is not changed by accident while changing the cases above.
     */
    @Test
    void anEmptySegmentKeepsBothUnderscores() {
        assertEquals("FOO__BAR", ConstantClassFactory.toConstantName("foo..bar"));
    }

    /**
     * Exactly one leading prefix is removed, and only when the separator follows it. A prefix that occurs again further
     * along the name is a segment of a property of a nested config data object and has to be kept.
     */
    @Test
    void onlyTheLeadingPrefixIsRemoved() {
        assertEquals("value", ConstantClassFactory.removePrefix("root.value", "root"));
        assertEquals("root.value", ConstantClassFactory.removePrefix("root.root.value", "root"));
        assertEquals("rootish.value", ConstantClassFactory.removePrefix("root.rootish.value", "root"));

        // the prefix is only removed where it is a whole leading segment
        assertEquals("rootish.value", ConstantClassFactory.removePrefix("rootish.value", "root"));
        assertEquals("value", ConstantClassFactory.removePrefix("value", "root"));

        // a record without a prefix has nothing to remove: dropping the dot would run the segments together
        assertEquals("root.value", ConstantClassFactory.removePrefix("root.value", ""));
    }
}
