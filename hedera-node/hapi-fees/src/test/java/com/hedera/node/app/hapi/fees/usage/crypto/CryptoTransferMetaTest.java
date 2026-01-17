// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hederahashgraph.api.proto.java.SubType;
import org.junit.jupiter.api.Test;

class CryptoTransferMetaTest {

    @Test
    void setterWith4ParamsWorks() {
        final var subject = new CryptoTransferMeta(1, 2, 3, 4, false);

        // when:
        subject.setCustomFeeHbarTransfers(10);
        subject.setCustomFeeTokenTransfers(5);
        subject.setCustomFeeTokensInvolved(2);

        // then:
        assertEquals(1, subject.getTokenMultiplier());
        assertEquals(3, subject.getNumFungibleTokenTransfers());
        assertEquals(2, subject.getNumTokensInvolved());
        assertEquals(4, subject.getNumNftOwnershipChanges());
        assertEquals(2, subject.getCustomFeeTokensInvolved());
        assertEquals(5, subject.getCustomFeeTokenTransfers());
        assertEquals(10, subject.getCustomFeeHbarTransfers());
    }

    @Test
    void getSubTypePrioritizesNFT() {
        var subject = new CryptoTransferMeta(1, 2, 3, 4, false);

        assertEquals(SubType.TOKEN_NON_FUNGIBLE_UNIQUE, subject.getSubType());

        subject.setCustomFeeHbarTransfers(0);
        subject.setCustomFeeTokenTransfers(5);
        assertEquals(SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES, subject.getSubType());

        subject.setCustomFeeHbarTransfers(10);
        subject.setCustomFeeTokenTransfers(0);
        assertEquals(SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES, subject.getSubType());

        subject = new CryptoTransferMeta(1, 2, 3, 0, false);

        assertEquals(SubType.TOKEN_FUNGIBLE_COMMON, subject.getSubType());

        subject.setCustomFeeHbarTransfers(0);
        subject.setCustomFeeTokenTransfers(5);
        assertEquals(SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES, subject.getSubType());

        subject.setCustomFeeHbarTransfers(10);
        subject.setCustomFeeTokenTransfers(0);
        assertEquals(SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES, subject.getSubType());

        subject = new CryptoTransferMeta(1, 0, 0, 0, false);

        assertEquals(SubType.DEFAULT, subject.getSubType());
    }
}
