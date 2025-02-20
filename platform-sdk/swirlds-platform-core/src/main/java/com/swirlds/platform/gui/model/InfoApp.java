// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata about an app that is installed locally.
 */
public class InfoApp extends InfoEntity {
    private List<InfoSwirld> swirlds = new ArrayList<>(); // children

    public InfoApp(String name) {
        super(name);
    }

    public List<InfoSwirld> getSwirlds() {
        return swirlds;
    }
}
