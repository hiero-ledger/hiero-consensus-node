// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import com.swirlds.demo.platform.fs.stresstest.proto.AppTransactionSignatureType;

/**
 * Configuration used for setting up transaction pool
 */
public class TransactionPoolConfig {
    /** An array to define the percentage of each specific signing algorithm should be generated */
    private SigTypeConfig[] sigTypeConfigs;

    /** generate random signature type based on configuration */
    WeightedRandomBag<AppTransactionSignatureType> signatureTypeGenerator = new WeightedRandomBag<>();

    public SigTypeConfig[] getSigTypeConfigs() {
        return sigTypeConfigs;
    }

    public void setSigTypeConfigs(SigTypeConfig[] sigTypeConfigs) {
        this.sigTypeConfigs = sigTypeConfigs;
        for (SigTypeConfig item : sigTypeConfigs) {
            signatureTypeGenerator.addEntry(item.signatureType, item.percentage);
        }
    }

    /**
     * Return a random signing algorithm type
     */
    public AppTransactionSignatureType getRandomSigType() {
        if (sigTypeConfigs == null || sigTypeConfigs.length == 0) {
            // return default type if distribution not defined
            return AppTransactionSignatureType.ED25519;
        } else {
            return signatureTypeGenerator.getRandom();
        }
    }
}
