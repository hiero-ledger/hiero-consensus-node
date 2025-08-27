// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.util;

import static com.hedera.services.bdd.spec.HapiPropertySource.asDotDelimitedLongArray;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isIdLiteral;
import static com.hedera.services.yahcli.suites.Utils.mismatchedShardRealmMsg;

import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.output.YahcliOutput;
import java.util.Arrays;

public class ParseUtils {

    private ParseUtils() {
        // Utility class
    }

    /**
     * Parses the key type–ED25519 or SECP256K1–from a string (typically a command line parameter)
     *
     * @param output the YahcliOutput to use for any user messaging
     * @param keyType the key type string
     * @return the corresponding SigControl value, or null if the key type is not one of the expected values
     */
    public static SigControl keyTypeFromParam(YahcliOutput output, final String keyType) {
        final SigControl sigType;
        if ("SECP256K1".equalsIgnoreCase(keyType)) {
            sigType = SigControl.SECP256K1_ON;
        } else if ("ED25519".equalsIgnoreCase(keyType)) {
            sigType = SigControl.ED25519_ON;
        } else {
            output.warn("Invalid key type: " + keyType + ". Must be 'ED25519' or 'SECP256K1'");
            sigType = null;
        }

        return sigType;
    }

    public static void main(String[] args) {
        normalizePossibleIdLiteral(null, null);
    }

    /**
     * Takes a string and, IF it is a fully-qualified entity ID, converts the ID to ONLY the third part.
     * For any other values (including `null`), the value passed in will be the value returned.
     * <p>
     * E.g.:
     * <ul>
     *     <li>`"1.2.3"` will return `"3"`</li>
     *     <li>`"5"` will return `"5"`</li>
     *     <li>`null` will return `null`</li>
     *     <li>`"null"` will return `"null"`</li>
     *     <li>`"whatever value you want"` will return `"whatever value you want"`</li>
     * </ul>
     *
     * @param config the yahcli configuration, specifically the target shard and realm.
     *                  Included only to indicate any mismatch to the end user (via console message)
     *
     * @param idLiteral the entity ID literal to (possibly) normalize
     * @return the normalized ID literal, which is a string representation of the entity number
     */
    public static String normalizePossibleIdLiteral(ConfigManager config, String idLiteral) {
        if (idLiteral != null && isIdLiteral(idLiteral)) {
            final var entityIdParts = asDotDelimitedLongArray(idLiteral);
            if (entityIdParts[0] != config.shard().getShardNum()
                    || entityIdParts[1] != config.realm().getRealmNum()) {
                mismatchedShardRealmMsg(config, Arrays.toString(entityIdParts));
            }

            return Long.toString(entityIdParts[2]);
        } else {
            // (FUTURE) Also handle null, empty values
            return idLiteral;
        }
    }
}
