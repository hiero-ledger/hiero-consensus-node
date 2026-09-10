// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.PENDING_COMMITMENTS_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprRegisterChannelTransactionBody;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.impl.WritablePendingCommitmentStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprRegisterChannelHandler;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprRegisterChannelHandlerTest {

    private static final AccountID PAYER_ID =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(1001).build();
    private static final Bytes COMMITMENT = Bytes.wrap(new byte[32]);

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableStates writableStates;

    private ClprRegisterChannelHandler subject;
    private WritablePendingCommitmentStore commitmentStore;

    @BeforeEach
    void setUp() {
        subject = new ClprRegisterChannelHandler();

        final var writableCommitments = MapWritableKVState.<ProtoBytes, ProtoBytes>builder(
                        PENDING_COMMITMENTS_STATE_ID, "ClprService:PENDING_COMMITMENTS")
                .build();
        lenient()
                .when(writableStates.<ProtoBytes, ProtoBytes>get(PENDING_COMMITMENTS_STATE_ID))
                .thenReturn(writableCommitments);
        commitmentStore = new WritablePendingCommitmentStore(writableStates);
    }

    // ========== pureChecks tests ==========

    @Test
    @DisplayName("should reject when ownership_commitment is not 32 bytes")
    void rejectsWrongCommitmentLength() {
        final var op = ClprRegisterChannelTransactionBody.newBuilder()
                .ownershipCommitment(Bytes.wrap(new byte[16]))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should pass pureChecks with valid 32-byte commitment")
    void passesWithValidCommitment() throws PreCheckException {
        given(pureChecksContext.body()).willReturn(validTxn());
        subject.pureChecks(pureChecksContext);
    }

    // ========== handle tests ==========

    @Test
    @DisplayName("should reject when CLPR is not enabled")
    void rejectsWhenClprNotEnabled() {
        final var disabledConfig = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", false)
                .getOrCreateConfig();
        setupHandleContext(validTxn(), disabledConfig);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("should accept duplicate commitment idempotently")
    void acceptsDuplicateCommitmentIdempotently() {
        commitmentStore.put(COMMITMENT);
        setupHandleContext(validTxn());

        // Should not throw — re-submitting is a no-op
        subject.handle(handleContext);

        assertThat(commitmentStore.contains(COMMITMENT)).isTrue();
    }

    @Test
    @DisplayName("should successfully store commitment")
    void successfullyStoresCommitment() {
        setupHandleContext(validTxn());

        subject.handle(handleContext);

        assertThat(commitmentStore.contains(COMMITMENT)).isTrue();
    }

    // ========== Helper methods ==========

    private void setupHandleContext(final TransactionBody txn) {
        final var enabledConfig =
                HederaTestConfigBuilder.create().withValue("clpr.enabled", true).getOrCreateConfig();
        setupHandleContext(txn, enabledConfig);
    }

    private void setupHandleContext(final TransactionBody txn, final Configuration configuration) {
        lenient().when(handleContext.body()).thenReturn(txn);
        lenient().when(handleContext.payer()).thenReturn(PAYER_ID);
        lenient().when(handleContext.configuration()).thenReturn(configuration);
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient()
                .when(storeFactory.writableStore(WritablePendingCommitmentStore.class))
                .thenReturn(commitmentStore);
    }

    private TransactionBody validTxn() {
        final var op = ClprRegisterChannelTransactionBody.newBuilder()
                .ownershipCommitment(COMMITMENT)
                .build();
        return txnWith(op);
    }

    private TransactionBody txnWith(final ClprRegisterChannelTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprRegisterChannel(op)
                .build();
    }
}
