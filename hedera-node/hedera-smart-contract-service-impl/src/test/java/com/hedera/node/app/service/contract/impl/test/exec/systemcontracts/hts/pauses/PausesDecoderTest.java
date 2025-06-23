// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.pauses;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.pauses.PausesDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.pauses.PausesTranslator;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class PausesDecoderTest {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private HederaNativeOperations nativeOperations;

    private final PausesDecoder subject = new PausesDecoder();

    @Test
    void pauseWorks() {
        final var encoded = PausesTranslator.PAUSE
                .encodeCallWithArgs(FUNGIBLE_TOKEN_HEADLONG_ADDRESS)
                .array();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var body = subject.decodePause(attempt);
        assertPausePresent(body, FUNGIBLE_TOKEN_ID);
    }

    @Test
    void unpauseWorks() {
        final var encoded = PausesTranslator.UNPAUSE
                .encodeCallWithArgs(NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS)
                .array();
        given(attempt.inputBytes()).willReturn(encoded);
        given(attempt.nativeOperations()).willReturn(nativeOperations);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var body = subject.decodeUnpause(attempt);
        assertUnpausePresent(body, NON_FUNGIBLE_TOKEN_ID);
    }

    private void assertPausePresent(@NonNull final TransactionBody body, @NonNull final TokenID tokenId) {
        final var pause = body.tokenPauseOrThrow();
        org.assertj.core.api.Assertions.assertThat(pause.token()).isEqualTo(tokenId);
    }

    private void assertUnpausePresent(@NonNull final TransactionBody body, @NonNull final TokenID tokenId) {
        final var unpause = body.tokenUnpauseOrThrow();
        org.assertj.core.api.Assertions.assertThat(unpause.token()).isEqualTo(tokenId);
    }
}
