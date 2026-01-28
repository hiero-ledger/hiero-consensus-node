// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gui.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata about a swirld running on an app.
 */
public class InfoSwirld extends InfoEntity {

    private final InfoApp app; // parent

    private final List<InfoMember> members = new ArrayList<>(); // children

    public InfoSwirld(InfoApp app, byte[] swirldIdBytes) {
        super("Swirld " + new Reference(swirldIdBytes).to62Prefix());
        this.app = app;
        this.app.getSwirlds().add(this);
    }

    public InfoApp getApp() {
        return app;
    }

    public List<InfoMember> getMembers() {
        return members;
    }
}
