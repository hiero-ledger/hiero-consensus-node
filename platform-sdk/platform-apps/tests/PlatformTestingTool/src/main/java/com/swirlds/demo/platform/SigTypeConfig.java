// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import com.swirlds.demo.platform.fs.stresstest.proto.AppTransactionSignatureType;

/**
 * Configuration for generating payload with each different type of signature
 */
public class SigTypeConfig {
    /** Signing algorithm used to sign a payload */
    public AppTransactionSignatureType signatureType;
    /** percentage of total traffic of this kind of signing payload */
    public int percentage;

    public AppTransactionSignatureType getSignatureType() {
        return signatureType;
    }

    public void setSignatureType(AppTransactionSignatureType signatureType) {
        this.signatureType = signatureType;
    }

    public int getPercentage() {
        return percentage;
    }

    public void setPercentage(int percentage) {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("percentage");
        }

        this.percentage = percentage;
    }
}
