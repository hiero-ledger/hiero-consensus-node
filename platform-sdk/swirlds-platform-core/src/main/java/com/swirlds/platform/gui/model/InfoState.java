// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.model;

/**
 * Metadata about a state stored by a member in a swirld running on an app.
 */
class InfoState extends InfoEntity {

    public InfoState(InfoMember member, String name) {
        super(name);
        member.getStates().add(this);
    }
}
