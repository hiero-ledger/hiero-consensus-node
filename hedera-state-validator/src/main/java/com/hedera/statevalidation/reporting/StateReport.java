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

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("============\n");

        sb.append(String.format("Root Hash: %s\n", rootHash));
        sb.append(String.format("Calculated Hash: %s\n", calculatedHash));

        if (rootHash != null && calculatedHash != null) {
            boolean hashesMatch = rootHash.equals(calculatedHash);
            sb.append(String.format("Hashes Match: %s\n", hashesMatch ? "YES" : "NO"));
        }

        return sb.toString();
    }
}
