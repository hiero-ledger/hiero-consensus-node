// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNKNOWN;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.NODE_DUE_DILIGENCE_FAILURE;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.PRE_HANDLE_FAILURE;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.SO_FAR_SO_GOOD;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.UNKNOWN_FAILURE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.TransactionScenarioBuilder;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class PreHandleResultTest implements Scenarios {

    private static final long DEFAULT_CONFIG_VERSION = 1L;
    private static final Set<Key> DEFAULT_REQUIRED_KEYS = Collections.emptySet();
    private static final Map<Key, SignatureVerificationFuture> DEFAULT_VERIFICATION_RESULTS = Collections.emptyMap();
    private final PreHandleResult DEFAULT_RESULT = new PreHandleResult(
            ALICE.accountID(),
            ALICE.account().keyOrThrow(),
            SO_FAR_SO_GOOD,
            OK,
            new TransactionScenarioBuilder().txInfo(),
            Set.of(BOB.account().keyOrThrow()),
            Set.of(CAROL.account().keyOrThrow()),
            Set.of(ERIN.account()),
            Map.of(),
            null,
            1L);

    @Mock
    private PreHandleContext context;

    @Test
    void notReusableForContextWithDifferentPayerKey() {
        given(context.payerKey()).willReturn(BOB.account().keyOrThrow());
        assertThat(DEFAULT_RESULT.hasReusableVerificationResultsFor(context)).isFalse();
    }

    @Test
    void notReusableForContextWithDifferentRequiredKeys() {
        given(context.payerKey()).willReturn(ALICE.account().keyOrThrow());
        given(context.requiredNonPayerKeys()).willReturn(Set.of(ALICE.account().keyOrThrow()));
        assertThat(DEFAULT_RESULT.hasReusableVerificationResultsFor(context)).isFalse();
    }

    @Test
    void notReusableForContextWithDifferentOptionalKeys() {
        given(context.payerKey()).willReturn(ALICE.account().keyOrThrow());
        given(context.requiredNonPayerKeys()).willReturn(Set.of(BOB.account().keyOrThrow()));
        given(context.optionalNonPayerKeys()).willReturn(Set.of(ALICE.account().keyOrThrow()));
        assertThat(DEFAULT_RESULT.hasReusableVerificationResultsFor(context)).isFalse();
    }

    @Test
    void notReusableForContextWithDifferentHollowAccounts() {
        given(context.payerKey()).willReturn(ALICE.account().keyOrThrow());
        given(context.requiredNonPayerKeys()).willReturn(Set.of(BOB.account().keyOrThrow()));
        given(context.optionalNonPayerKeys()).willReturn(Set.of(CAROL.account().keyOrThrow()));
        given(context.requiredHollowAccounts()).willReturn(Collections.emptySet());
        assertThat(DEFAULT_RESULT.hasReusableVerificationResultsFor(context)).isFalse();
    }

    @Test
    void reusableForContextWithIdenticalLinkedKeys() {
        given(context.payerKey()).willReturn(ALICE.account().keyOrThrow());
        given(context.requiredNonPayerKeys()).willReturn(Set.of(BOB.account().keyOrThrow()));
        given(context.optionalNonPayerKeys()).willReturn(Set.of(CAROL.account().keyOrThrow()));
        given(context.requiredHollowAccounts()).willReturn(Set.of(ERIN.account()));
        assertThat(DEFAULT_RESULT.hasReusableVerificationResultsFor(context)).isTrue();
    }

    /**
     * Tests to verify the creation of the object. Simple null checks, and verifying that the different static
     * construction methods all creation proper objects.
     */
    @Nested
    @DisplayName("Testing Creation of PreHandleResult")
    @ExtendWith(MockitoExtension.class)
    final class CreationTests {
        /**
         * The {@link PreHandleResult#status()} must not be null.
         */
        @Test
        @DisplayName("The status must not be null")
        @SuppressWarnings("ConstantConditions")
        void statusMustNotBeNull(
                @Mock AccountID payer, @Mock TransactionInfo txInfo, @Mock PreHandleResult innerResult) {
            assertThatThrownBy(() -> new PreHandleResult(
                            payer,
                            Key.DEFAULT,
                            null,
                            OK,
                            txInfo,
                            DEFAULT_REQUIRED_KEYS,
                            Set.of(),
                            Set.of(),
                            DEFAULT_VERIFICATION_RESULTS,
                            List.of(innerResult),
                            DEFAULT_CONFIG_VERSION))
                    .isInstanceOf(NullPointerException.class);
        }

        /**
         * The {@link PreHandleResult#responseCode()} must not be null.
         */
        @Test
        @DisplayName("The response code must not be null")
        @SuppressWarnings("ConstantConditions")
        void responseCodeMustNotBeNull(
                @Mock AccountID payer, @Mock TransactionInfo txInfo, @Mock PreHandleResult innerResult) {
            assertThatThrownBy(() -> new PreHandleResult(
                            payer,
                            Key.DEFAULT,
                            SO_FAR_SO_GOOD,
                            null,
                            txInfo,
                            DEFAULT_REQUIRED_KEYS,
                            Set.of(),
                            Set.of(),
                            DEFAULT_VERIFICATION_RESULTS,
                            List.of(innerResult),
                            DEFAULT_CONFIG_VERSION))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Unknown failures set the status and response code and everything else is null")
        void unknownFailure() {
            final var result = PreHandleResult.unknownFailure();

            assertThat(result.status()).isEqualTo(UNKNOWN_FAILURE);
            assertThat(result.responseCode()).isEqualTo(UNKNOWN);
            assertThat(result.innerResults()).isNull();
            assertThat(result.payer()).isNull();
            assertThat(result.txInfo()).isNull();
            assertThat(result.requiredKeys()).isNull();
            assertThat(result.verificationResults()).isNull();
            assertThat(result.getOptionalKeys()).isEmpty();
        }

        @Test
        @DisplayName(
                "Node Diligence Failures only set the status and response code and the payer to be the node and the tx info")
        void nodeDiligenceFailure(@Mock TransactionInfo txInfo) {
            final var nodeAccountId = AccountID.newBuilder().accountNum(3).build();
            final var status = INVALID_PAYER_ACCOUNT_ID;
            final var result =
                    PreHandleResult.nodeDueDiligenceFailure(nodeAccountId, status, txInfo, DEFAULT_CONFIG_VERSION);

            assertThat(result.status()).isEqualTo(NODE_DUE_DILIGENCE_FAILURE);
            assertThat(result.responseCode()).isEqualTo(status);
            assertThat(result.innerResults()).isNull();
            assertThat(result.payer()).isEqualTo(nodeAccountId);
            assertThat(result.txInfo()).isSameAs(txInfo);
            assertThat(result.requiredKeys()).isNull();
            assertThat(result.verificationResults()).isNull();
        }

        @Test
        @DisplayName("Pre-Handle Failures set the payer, status, responseCode, and txInfo")
        void preHandleFailure(@Mock TransactionInfo txInfo) {
            final var payer = AccountID.newBuilder().accountNum(1001).build();
            final var responseCode = INVALID_PAYER_ACCOUNT_ID;
            final var result =
                    PreHandleResult.preHandleFailure(payer, null, responseCode, txInfo, null, null, null, null);

            assertThat(result.status()).isEqualTo(PRE_HANDLE_FAILURE);
            assertThat(result.responseCode()).isEqualTo(responseCode);
            assertThat(result.innerResults()).isNull();
            assertThat(result.payer()).isEqualTo(payer);
            assertThat(result.txInfo()).isSameAs(txInfo);
            assertThat(result.requiredKeys()).isNull();
            assertThat(result.verificationResults()).isNull();
        }
    }
}
