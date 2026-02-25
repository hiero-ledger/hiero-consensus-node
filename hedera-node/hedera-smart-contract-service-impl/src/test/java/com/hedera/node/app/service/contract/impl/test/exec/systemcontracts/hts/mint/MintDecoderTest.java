// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.mint;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.VALUE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintTranslator;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MintDecoderTest {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private HederaNativeOperations nativeOperations;

    private final MintDecoder subject = new MintDecoder();

    @Test
    void mintWorks() {
        final var encoded = MintTranslator.MINT
                .encodeCallWithArgs(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, BigInteger.valueOf(VALUE), new byte[][] {})
                .array();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var mint = subject.decodeMint(attempt).tokenMintOrThrow();
        assertEquals(FUNGIBLE_TOKEN_ID, mint.token());
        assertEquals(VALUE, mint.amount());
    }

    @Test
    void mintV2Works() {
        final var encoded = MintTranslator.MINT_V2
                .encodeCallWithArgs(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, VALUE, new byte[][] {})
                .array();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var mint = subject.decodeMintV2(attempt).tokenMintOrThrow();
        assertEquals(FUNGIBLE_TOKEN_ID, mint.token());
        assertEquals(VALUE, mint.amount());
    }
}
