// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.PENDING_CONNECTOR_COMMITMENTS_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprRegisterConnectorTransactionBody;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.impl.WritablePendingConnectorCommitmentStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprRegisterConnectorHandler;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprRegisterConnectorHandlerTest {

    private static final AccountID PAYER_ID =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(1001).build();
    private static final Bytes VALID_COMMITMENT = Bytes.wrap(new byte[32]);

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableStates writableStates;

    private ClprRegisterConnectorHandler subject;
    private WritablePendingConnectorCommitmentStore commitmentStore;

    @BeforeEach
    void setUp() {
        subject = new ClprRegisterConnectorHandler();

        final var writableCommitments = MapWritableKVState.<ProtoBytes, ProtoBytes>builder(
                        PENDING_CONNECTOR_COMMITMENTS_STATE_ID, "ClprService:PENDING_CONNECTOR_COMMITMENTS")
                .build();
        lenient()
                .when(writableStates.<ProtoBytes, ProtoBytes>get(PENDING_CONNECTOR_COMMITMENTS_STATE_ID))
                .thenReturn(writableCommitments);
        commitmentStore = new WritablePendingConnectorCommitmentStore(writableStates);
    }

    @Test
    @DisplayName("should reject when commitment is not 32 bytes")
    void rejectsWrongCommitmentLength() {
        final var op = ClprRegisterConnectorTransactionBody.newBuilder()
                .commitment(Bytes.wrap(new byte[16]))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when commitment is empty")
    void rejectsEmptyCommitment() {
        final var op = ClprRegisterConnectorTransactionBody.newBuilder()
                .commitment(Bytes.EMPTY)
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should pass pureChecks with 32-byte commitment")
    void passesWithValidCommitment() throws PreCheckException {
        given(pureChecksContext.body()).willReturn(validTxn());
        subject.pureChecks(pureChecksContext);
    }

    @Test
    @DisplayName("should reject when CLPR is not enabled")
    void rejectsWhenClprNotEnabled() {
        setupHandleContext(validTxn(), false);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("should store commitment in PENDING_CONNECTOR_COMMITMENTS")
    void storesCommitment() {
        setupHandleContext(validTxn(), true);
        subject.handle(handleContext);
        assertThat(commitmentStore.contains(VALID_COMMITMENT)).isTrue();
    }

    @Test
    @DisplayName("should be idempotent — re-registering same commitment does not throw")
    void isIdempotent() {
        setupHandleContext(validTxn(), true);
        subject.handle(handleContext);
        subject.handle(handleContext);
        assertThat(commitmentStore.contains(VALID_COMMITMENT)).isTrue();
    }

    private void setupHandleContext(final TransactionBody txn, final boolean clprEnabled) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", clprEnabled)
                .getOrCreateConfig();
        lenient().when(handleContext.body()).thenReturn(txn);
        lenient().when(handleContext.configuration()).thenReturn(config);
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient()
                .when(storeFactory.writableStore(WritablePendingConnectorCommitmentStore.class))
                .thenReturn(commitmentStore);
    }

    private TransactionBody validTxn() {
        return txnWith(ClprRegisterConnectorTransactionBody.newBuilder()
                .commitment(VALID_COMMITMENT)
                .build());
    }

    private TransactionBody txnWith(final ClprRegisterConnectorTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprRegisterConnector(op)
                .build();
    }
}
