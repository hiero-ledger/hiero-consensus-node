// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Metadata about a member in a swirld running on an app.
 */
public class InfoMember extends InfoEntity {

    private final List<InfoState> states = new ArrayList<>(); // children

    public InfoMember(@NonNull final InfoSwirld swirld, @NonNull final String name) {
        super(name);
        swirld.getMembers().add(this);
    }

    public List<InfoState> getStates() {
        return states;
    }
}
