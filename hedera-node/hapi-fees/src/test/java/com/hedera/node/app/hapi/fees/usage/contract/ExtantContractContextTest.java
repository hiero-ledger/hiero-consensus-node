// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.contract;

import static com.hedera.node.app.hapi.fees.usage.contract.entities.ContractEntitySizes.CONTRACT_ENTITY_SIZES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.hapi.fees.usage.crypto.ExtantCryptoContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExtantContractContextTest {
    private static final int kvPairs = 64;
    private static final int bytecodeSize = 4096;
    private static final long nonBaseBytes = 666;

    @Mock
    private ExtantCryptoContext currentCryptoContext;

    private ExtantContractContext subject;

    @BeforeEach
    void setUp() {
        subject = new ExtantContractContext(kvPairs, currentCryptoContext);
    }

    @Test
    void getsExpectedRb() {
        given(currentCryptoContext.currentNonBaseRb()).willReturn(nonBaseBytes);

        final var expectedRb = CONTRACT_ENTITY_SIZES.fixedBytesInContractRepr() + nonBaseBytes;

        assertEquals(expectedRb, subject.currentRb());
    }

    @Test
    void getsExpectedKvPairs() {
        assertEquals(kvPairs, subject.currentNumKvPairs());
    }
}
