// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.logging;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A CSS declaration.
 * <p>
 * A declaration consists of a property name, followed by a value: "property: value;". Multiple declarations can be
 * combined into a single CSS rule set.
 *
 * @param property the CSS property
 * @param value    the property value
 */
public record CssDeclaration(@NonNull String property, @NonNull String value) {
    @Override
    public String toString() {
        return property + ": " + value + ";";
    }
}
