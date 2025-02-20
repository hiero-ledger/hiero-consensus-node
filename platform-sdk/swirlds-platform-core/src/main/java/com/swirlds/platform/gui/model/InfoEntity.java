// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.model;

import javax.swing.JPanel;

/**
 * The class that all 4 levels of the hierarchy inherit from: InfoApp, InfoSwirld, InfoMember, InfoState. This holds
 * information common to all of them, such as the name, and the GUI component that represents it in the browser window.
 */
public class InfoEntity {

    /** name of this entity */
    private final String name;

    /** the JPanel that shows this entity in the browser window (Swirlds tab), or null if none */
    private JPanel panel;

    public InfoEntity(final String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public JPanel getPanel() {
        return panel;
    }

    public void setPanel(JPanel panel) {
        this.panel = panel;
    }
}
