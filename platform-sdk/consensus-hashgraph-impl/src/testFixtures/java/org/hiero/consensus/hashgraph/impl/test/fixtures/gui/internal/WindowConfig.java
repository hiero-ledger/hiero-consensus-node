// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal;

import java.awt.Insets;

public class WindowConfig {

    /**
     * the number of pixels between the edges of a window and interior region that can be used
     */
    private static Insets insets;

    private WindowConfig() {}

    /**
     * Get the number of pixels between the edges of a window and interior region that can be used.
     */
    public static Insets getInsets() {
        return insets;
    }

    /**
     * Set the number of pixels between the edges of a window and interior region that can be used.
     */
    public static void setInsets(final Insets insets) {
        WindowConfig.insets = insets;
    }
}
