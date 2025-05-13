// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.reporting;

public class StateReport {

    @InvariantProperty
    private String rootHash;

    @InvariantProperty
    private String calculatedHash;

    public String getRootHash() {
        return rootHash;
    }

    public String getCalculatedHash() {
        return calculatedHash;
    }

    public void setRootHash(final String rootHash) {
        this.rootHash = rootHash;
    }

    public void setCalculatedHash(final String calculatedHash) {
        this.calculatedHash = calculatedHash;
    }
}
