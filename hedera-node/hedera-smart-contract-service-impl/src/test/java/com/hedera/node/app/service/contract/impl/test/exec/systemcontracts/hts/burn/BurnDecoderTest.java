// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.burn;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.VALUE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for burn decoder
 */
@ExtendWith(MockitoExtension.class)
class BurnDecoderTest {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private HederaNativeOperations hederaNativeOperations;

    private final BurnDecoder subject = new BurnDecoder();

    @Test
    void burnTokenHappyPathV1() {
        final var encoded = BurnTranslator.BURN_TOKEN_V1
                .encodeCallWithArgs(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, BigInteger.valueOf(VALUE), new long[] {})
                .array();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var burn = subject.decodeBurn(attempt).tokenBurnOrThrow();
        assertEquals(FUNGIBLE_TOKEN_ID, burn.token());
        assertEquals(VALUE, burn.amount());
    }

    @Test
    void burnTokenHappyPathV2() {
        final var encoded = BurnTranslator.BURN_TOKEN_V2
                .encodeCallWithArgs(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, VALUE, new long[] {})
                .array();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var burn = subject.decodeBurnV2(attempt).tokenBurnOrThrow();
        assertEquals(FUNGIBLE_TOKEN_ID, burn.token());
        assertEquals(VALUE, burn.amount());
    }
}
