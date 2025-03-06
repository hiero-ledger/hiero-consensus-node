// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import java.util.List;

public class StateChange {
    private String contractID;
    private List<StorageChange> storageChanges;

    private StateChange(String contractID) {
        this.contractID = contractID;
    }

    public static com.hedera.services.bdd.spec.assertions.StateChange stateChangeFor(String contractID) {
        return new com.hedera.services.bdd.spec.assertions.StateChange(contractID);
    }

    public StateChange withStorageChanges(StorageChange... changes) {
        this.storageChanges = List.of(changes);
        return this;
    }

    public String getContractID() {
        return contractID;
    }

    public List<StorageChange> getStorageChanges() {
        return storageChanges;
    }
}
