// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.METADATA_TOO_LONG;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.token.impl.validators.TokenSupplyChangeOpsValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenSupplyChangeOpsValidatorTest {
    private static final int MAX_BATCH_SIZE = 2;
    private static final int MAX_METADATA_BYTES = 10;
    private static final Bytes VALID_METADATA = Bytes.wrap(new byte[MAX_METADATA_BYTES]);
    private static final Bytes OVERSIZED_METADATA = Bytes.wrap(new byte[MAX_METADATA_BYTES + 1]);

    private TokenSupplyChangeOpsValidator subject;
    private TokensConfig tokensConfig;

    @BeforeEach
    void setUp() {
        subject = new TokenSupplyChangeOpsValidator();
        tokensConfig = HederaTestConfigBuilder.create()
                .withValue("tokens.nfts.maxBatchSizeMint", MAX_BATCH_SIZE)
                .withValue("tokens.nfts.maxBatchSizeBurn", MAX_BATCH_SIZE)
                .withValue("tokens.nfts.maxBatchSizeWipe", MAX_BATCH_SIZE)
                .withValue("tokens.nfts.maxMetadataBytes", MAX_METADATA_BYTES)
                .getOrCreateConfig()
                .getConfigData(TokensConfig.class);
    }

    @Test
    void mintRejectsFungibleAmountWithOversizedNftBatch() {
        final var metadata = List.of(OVERSIZED_METADATA, OVERSIZED_METADATA, OVERSIZED_METADATA);
        assertThatThrownBy(() -> subject.validateMint(1, metadata, tokensConfig))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void mintRejectsBatchOverLimit() {
        final var metadata = List.of(VALID_METADATA, VALID_METADATA, VALID_METADATA);
        assertThatThrownBy(() -> subject.validateMint(0, metadata, tokensConfig))
                .isInstanceOf(HandleException.class)
                .has(responseCode(BATCH_SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void mintRejectsOversizedMetadata() {
        final var metadata = List.of(VALID_METADATA, OVERSIZED_METADATA);
        assertThatThrownBy(() -> subject.validateMint(0, metadata, tokensConfig))
                .isInstanceOf(HandleException.class)
                .has(responseCode(METADATA_TOO_LONG));
    }

    @Test
    void mintAllowsFungibleOnly() {
        assertThatCode(() -> subject.validateMint(1_000, List.of(), tokensConfig))
                .doesNotThrowAnyException();
    }

    @Test
    void mintAllowsNftsWithinLimits() {
        final var metadata = List.of(VALID_METADATA, VALID_METADATA);
        assertThatCode(() -> subject.validateMint(0, metadata, tokensConfig)).doesNotThrowAnyException();
    }

    @Test
    void burnRejectsFungibleAmountWithOversizedNftBatch() {
        assertThatThrownBy(() -> subject.validateBurn(1, List.of(1L, 2L, 3L), tokensConfig))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void burnRejectsBatchOverLimit() {
        assertThatThrownBy(() -> subject.validateBurn(0, List.of(1L, 2L, 3L), tokensConfig))
                .isInstanceOf(HandleException.class)
                .has(responseCode(BATCH_SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void burnAllowsFungibleOnlyAndNftsWithinLimit() {
        assertThatCode(() -> subject.validateBurn(1_000, List.of(), tokensConfig))
                .doesNotThrowAnyException();
        assertThatCode(() -> subject.validateBurn(0, List.of(1L, 2L), tokensConfig))
                .doesNotThrowAnyException();
    }

    @Test
    void wipeRejectsFungibleAmountWithOversizedNftBatch() {
        assertThatThrownBy(() -> subject.validateWipe(1, List.of(1L, 2L, 3L), tokensConfig))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void wipeRejectsBatchOverLimit() {
        assertThatThrownBy(() -> subject.validateWipe(0, List.of(1L, 2L, 3L), tokensConfig))
                .isInstanceOf(HandleException.class)
                .has(responseCode(BATCH_SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void wipeAllowsFungibleOnlyAndNftsWithinLimit() {
        assertThatCode(() -> subject.validateWipe(1_000, List.of(), tokensConfig))
                .doesNotThrowAnyException();
        assertThatCode(() -> subject.validateWipe(0, List.of(1L, 2L), tokensConfig))
                .doesNotThrowAnyException();
    }
}
