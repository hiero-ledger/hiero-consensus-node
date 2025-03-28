// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.util;

import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

import com.hedera.services.bdd.spec.keys.SigControl;

public class ParseUtils {

    private ParseUtils() {
        // Utility class
    }

    /**
     * Parses the key type–ED25519 or SECP256K1–from a string (typically a command line parameter)
     * @param keyType the key type string
     * @return the corresponding SigControl value, or null if the key type is not one of the expected values
     */
    public static SigControl keyTypeFromParam(final String keyType) {
        final SigControl sigType;
        if ("SECP256K1".equalsIgnoreCase(keyType)) {
            sigType = SigControl.SECP256K1_ON;
        } else if ("ED25519".equalsIgnoreCase(keyType)) {
            sigType = SigControl.ED25519_ON;
        } else {
            COMMON_MESSAGES.warn("Invalid key type: " + keyType + ". Must be 'ED25519' or 'SECP256K1'");
            sigType = null;
        }

        return sigType;
    }
}
