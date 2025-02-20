// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.testreader;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Uniquely identifies a test variant.
 *
 * @param panel the panel that the test belongs to
 * @param name  the name of the test
 */
public record JrsTestIdentifier(@NonNull String panel, @NonNull String name) implements Comparable<JrsTestIdentifier> {
    @Override
    public int compareTo(@NonNull final JrsTestIdentifier that) {
        if (this.panel.equals(that.panel)) {
            return this.name.compareTo(that.name);
        } else {
            return this.panel.compareTo(that.panel);
        }
    }
}
