// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token.entities;

import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.NUM_ENTITY_ID_FIELDS_IN_BASE_TOKEN_REPRESENTATION;
import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.NUM_FLAGS_IN_BASE_TOKEN_REPRESENTATION;
import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.NUM_INT_FIELDS_IN_BASE_TOKEN_REPRESENTATION;
import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.NUM_LONG_FIELDS_IN_BASE_TOKEN_REPRESENTATION;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BOOL_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TokenEntitySizesTest {
    private final TokenEntitySizes subject = TokenEntitySizes.TOKEN_ENTITY_SIZES;

    @Test
    void sizesFixedAsExpected() {
        // setup:
        final long expected = NUM_FLAGS_IN_BASE_TOKEN_REPRESENTATION * BOOL_SIZE
                + NUM_INT_FIELDS_IN_BASE_TOKEN_REPRESENTATION * 4
                + NUM_LONG_FIELDS_IN_BASE_TOKEN_REPRESENTATION * 8
                + NUM_ENTITY_ID_FIELDS_IN_BASE_TOKEN_REPRESENTATION * BASIC_ENTITY_ID_SIZE;

        // given:
        final long actual = subject.fixedBytesInTokenRepr();

        // expect:
        assertEquals(expected, actual);
    }

    @Test
    void sizesAsExpected() {
        // setup:
        final var symbol = "ABCDEFGH";
        final var name = "WhyWouldINameItThis";
        final long expected = NUM_FLAGS_IN_BASE_TOKEN_REPRESENTATION * 4
                + NUM_INT_FIELDS_IN_BASE_TOKEN_REPRESENTATION * 4
                + NUM_LONG_FIELDS_IN_BASE_TOKEN_REPRESENTATION * 8
                + NUM_ENTITY_ID_FIELDS_IN_BASE_TOKEN_REPRESENTATION * BASIC_ENTITY_ID_SIZE
                + symbol.getBytes().length
                + name.getBytes().length;

        // given:
        final long actual = subject.totalBytesInTokenReprGiven(symbol, name);

        // expect:
        assertEquals(expected, actual);
    }

    @Test
    void understandsRecordTransfersSize() {
        // setup:
        final int numTokens = 3;
        final int fungibleNumTransfers = 8;
        final int uniqueNumTransfers = 2;

        // given:
        final var expected =
                3 * BASIC_ENTITY_ID_SIZE + 8 * (8 + BASIC_ENTITY_ID_SIZE) + 2 * (8 + 2 * BASIC_ENTITY_ID_SIZE);

        // then:
        assertEquals(
                expected, subject.bytesUsedToRecordTokenTransfers(numTokens, fungibleNumTransfers, uniqueNumTransfers));
    }

    @Test
    void returnsRequiredBytesForRel() {
        // expect:
        assertEquals(
                3 * BASIC_ENTITY_ID_SIZE + LONG_SIZE + 3 * BOOL_SIZE + INT_SIZE,
                subject.bytesUsedPerAccountRelationship());
    }

    @Test
    void returnsRequiredBytesForUniqueTokenTransfers() {
        assertEquals(3 * (2L * 24 + 8), subject.bytesUsedForUniqueTokenTransfers(3));
    }
}
