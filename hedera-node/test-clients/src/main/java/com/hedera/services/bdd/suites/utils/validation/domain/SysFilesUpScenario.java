// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.validation.domain;

import java.util.ArrayList;
import java.util.List;

public class SysFilesUpScenario {
    List<UpdateAction> updates = new ArrayList<>();

    public List<UpdateAction> getUpdates() {
        return updates;
    }

    public void setUpdates(List<UpdateAction> updates) {
        this.updates = updates;
    }
}
