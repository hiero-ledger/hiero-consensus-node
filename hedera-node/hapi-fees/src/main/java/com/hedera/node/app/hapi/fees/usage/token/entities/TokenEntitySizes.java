// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token.entities;

import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BOOL_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;

public enum TokenEntitySizes {
    TOKEN_ENTITY_SIZES;

    /* { deleted, accountsFrozenByDefault, accountsKycGrantedByDefault } */
    static final int NUM_FLAGS_IN_BASE_TOKEN_REPRESENTATION = 3;
    /* { decimals, tokenType, supplyType } */
    static final int NUM_INT_FIELDS_IN_BASE_TOKEN_REPRESENTATION = 3;
    /* { expiry, maxSupply, totalSupply, autoRenewPeriod, currentSerialNum } */
    static final int NUM_LONG_FIELDS_IN_BASE_TOKEN_REPRESENTATION = 5;
    /* { treasury } */
    static final int NUM_ENTITY_ID_FIELDS_IN_BASE_TOKEN_REPRESENTATION = 1;

    public int fixedBytesInTokenRepr() {
        return NUM_FLAGS_IN_BASE_TOKEN_REPRESENTATION * BOOL_SIZE
                + NUM_INT_FIELDS_IN_BASE_TOKEN_REPRESENTATION * INT_SIZE
                + NUM_LONG_FIELDS_IN_BASE_TOKEN_REPRESENTATION * LONG_SIZE
                + NUM_ENTITY_ID_FIELDS_IN_BASE_TOKEN_REPRESENTATION * BASIC_ENTITY_ID_SIZE;
    }

    public int totalBytesInTokenReprGiven(final String symbol, final String name) {
        return fixedBytesInTokenRepr() + symbol.length() + name.length();
    }

    public int bytesUsedToRecordTokenTransfers(
            final int numTokens, final int fungibleNumTransfers, final int uniqueNumTransfers) {
        return numTokens * BASIC_ENTITY_ID_SIZE
                + fungibleNumTransfers * USAGE_PROPERTIES.accountAmountBytes()
                + uniqueNumTransfers * USAGE_PROPERTIES.nftTransferBytes();
    }

    public long bytesUsedForUniqueTokenTransfers(final int numOwnershipChanges) {
        return numOwnershipChanges * (2L * BASIC_ENTITY_ID_SIZE + LONG_SIZE);
    }

    public int bytesUsedPerAccountRelationship() {
        return 3 * BASIC_ENTITY_ID_SIZE + LONG_SIZE + 3 * BOOL_SIZE + INT_SIZE;
    }
}
