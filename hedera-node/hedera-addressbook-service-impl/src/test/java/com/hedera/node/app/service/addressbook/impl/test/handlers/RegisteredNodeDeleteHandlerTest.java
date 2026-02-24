// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ENTITY_NOT_ALLOWED_TO_DELETE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.addressbook.RegisteredNodeDeleteTransactionBody;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.ReadableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.WritableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.handlers.RegisteredNodeDeleteHandler;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegisteredNodeDeleteHandlerTest extends AddressBookTestBase {
    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ReadableRegisteredNodeStore readableRegisteredNodeStore;

    @Mock
    private WritableRegisteredNodeStore writableRegisteredNodeStore;

    @Mock
    private ReadableNodeStore readableNodeStore;

    private RegisteredNodeDeleteHandler subject;

    private final long registeredNodeId = 1234L;
    private RegisteredNode existing;

    @BeforeEach
    void setUp() {
        subject = new RegisteredNodeDeleteHandler();
        existing = new RegisteredNode.Builder()
                .registeredNodeId(registeredNodeId)
                .adminKey(key)
                .description("d")
                .serviceEndpoint(List.of())
                .build();
    }

    @Test
    void pureChecksFailsForNegativeId() {
        final var txn = txnWithOp(opBuilder().registeredNodeId(-1).build());
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(INVALID_NODE_ID, msg.responseCode());
    }

    @Test
    void preHandleBypassesAdminKeyForTreasuryPayer() throws PreCheckException {
        // payerId in base is 0.0.2 (treasury by default config)
        mockAccountLookup(anotherKey, payerId, accountStore);
        final var txn = txnWithOp(opBuilder().registeredNodeId(registeredNodeId).build());
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        final var ctx = new com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext(accountStore, txn, config);
        ctx.registerStore(ReadableRegisteredNodeStore.class, readableRegisteredNodeStore);
        given(readableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);

        assertDoesNotThrow(() -> subject.preHandle(ctx));
        assertThat(ctx.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    void preHandleRequiresAdminKeyForNonPrivilegedPayer() throws PreCheckException {
        final var nonPrivPayer = idFactory.newAccountId(3);
        mockAccountLookup(anotherKey, nonPrivPayer, accountStore);
        final var txn = TransactionBody.newBuilder()
                .transactionID(
                        TransactionID.newBuilder().accountID(nonPrivPayer).build())
                .registeredNodeDelete(
                        opBuilder().registeredNodeId(registeredNodeId).build())
                .build();
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        final var ctx = new com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext(accountStore, txn, config);
        ctx.registerStore(ReadableRegisteredNodeStore.class, readableRegisteredNodeStore);
        given(readableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);

        subject.preHandle(ctx);
        assertThat(ctx.requiredNonPayerKeys()).contains(key);
    }

    @Test
    void handleForbidsDeletionWhenReferenced() {
        final var txn = txnWithOp(opBuilder().registeredNodeId(registeredNodeId).build());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableRegisteredNodeStore.class)).willReturn(writableRegisteredNodeStore);
        given(storeFactory.readableStore(ReadableNodeStore.class)).willReturn(readableNodeStore);
        given(writableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);

        final var referencingNode = createNode()
                .copyBuilder()
                .associatedRegisteredNode(List.of(registeredNodeId))
                .build();
        given(readableNodeStore.keys())
                .willReturn(List.of(EntityNumber.newBuilder().number(1).build()));
        given(readableNodeStore.get(1)).willReturn(referencingNode);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ENTITY_NOT_ALLOWED_TO_DELETE, msg.getStatus());
    }

    @Test
    void handleDeletesWhenUnreferenced() {
        final var txn = txnWithOp(opBuilder().registeredNodeId(registeredNodeId).build());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableRegisteredNodeStore.class)).willReturn(writableRegisteredNodeStore);
        given(storeFactory.readableStore(ReadableNodeStore.class)).willReturn(readableNodeStore);
        given(writableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);
        given(readableNodeStore.keys()).willReturn(List.of());

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(writableRegisteredNodeStore).remove(registeredNodeId);
    }

    private TransactionBody txnWithOp(final RegisteredNodeDeleteTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId).build())
                .registeredNodeDelete(op)
                .build();
    }

    private RegisteredNodeDeleteTransactionBody.Builder opBuilder() {
        return RegisteredNodeDeleteTransactionBody.newBuilder().registeredNodeId(registeredNodeId);
    }
}
