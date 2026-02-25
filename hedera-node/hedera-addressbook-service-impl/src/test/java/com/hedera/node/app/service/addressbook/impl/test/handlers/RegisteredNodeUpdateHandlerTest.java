// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.addressbook.RegisteredNodeUpdateTransactionBody;
import com.hedera.hapi.node.addressbook.RegisteredServiceEndpoint;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.ReadableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.WritableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.handlers.RegisteredNodeUpdateHandler;
import com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegisteredNodeUpdateHandlerTest extends AddressBookTestBase {
    @Mock
    private HandleContext handleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableRegisteredNodeStore writableRegisteredNodeStore;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ReadableRegisteredNodeStore readableRegisteredNodeStore;

    private RegisteredNodeUpdateHandler subject;

    private final long registeredNodeId = 1234L;
    private RegisteredNode existing;

    @BeforeEach
    void setUp() {
        subject = new RegisteredNodeUpdateHandler(new AddressBookValidator());
        existing = new RegisteredNode.Builder()
                .registeredNodeId(registeredNodeId)
                .adminKey(key)
                .description("old")
                .serviceEndpoint(List.of(validEndpoint()))
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
    void preHandleRequiresOldAdminKeyAndNewKeyIfRotating() throws PreCheckException {
        mockAccountLookup(aPrimitiveKey, payerId, accountStore);
        final var txn = txnWithOp(opBuilder()
                .registeredNodeId(registeredNodeId)
                .adminKey(anotherKey)
                .build());

        final var ctx = new com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext(accountStore, txn);
        ctx.registerStore(ReadableRegisteredNodeStore.class, readableRegisteredNodeStore);
        given(readableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);

        subject.preHandle(ctx);
        assertThat(ctx.requiredNonPayerKeys()).contains(key, anotherKey);
    }

    @Test
    void handleUpdatesNodeAccountAndAllowsRemovalSentinel() {
        final var activeAccount = mock(com.hedera.hapi.node.state.token.Account.class);
        given(activeAccount.deleted()).willReturn(false);
        given(accountStore.getAccountById(accountId)).willReturn(activeAccount);

        final var txn = txnWithOp(opBuilder().registeredNodeId(registeredNodeId).build());

        given(handleContext.body()).willReturn(txn);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableRegisteredNodeStore.class)).willReturn(writableRegisteredNodeStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(writableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        // Removal sentinel: 0.0.0
        final var sentinel =
                AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(0).build();
        final var txn2 =
                txnWithOp(opBuilder().registeredNodeId(registeredNodeId).build());
        given(handleContext.body()).willReturn(txn2);

        assertDoesNotThrow(() -> subject.handle(handleContext));
    }

    @Test
    void handleFailsIfNodeAccountDeleted() {
        final var deletedAccount = mock(com.hedera.hapi.node.state.token.Account.class);
        given(deletedAccount.deleted()).willReturn(true);
        given(accountStore.getAccountById(accountId)).willReturn(deletedAccount);

        final var txn = txnWithOp(opBuilder().registeredNodeId(registeredNodeId).build());

        given(handleContext.body()).willReturn(txn);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableRegisteredNodeStore.class)).willReturn(writableRegisteredNodeStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(writableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ACCOUNT_DELETED, msg.getStatus());
    }

    @Test
    void handleFailsIfTargetMissing() {
        final var txn = txnWithOp(opBuilder().registeredNodeId(registeredNodeId).build());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableRegisteredNodeStore.class)).willReturn(writableRegisteredNodeStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(writableRegisteredNodeStore.get(registeredNodeId)).willReturn(null);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INVALID_NODE_ID, msg.getStatus());
    }

    private TransactionBody txnWithOp(final RegisteredNodeUpdateTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId).build())
                .registeredNodeUpdate(op)
                .build();
    }

    private RegisteredNodeUpdateTransactionBody.Builder opBuilder() {
        return RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .description("new")
                .serviceEndpoint(List.of(validEndpoint()));
    }

    private static RegisteredServiceEndpoint validEndpoint() {
        return RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {127, 0, 0, 1}))
                .port(443)
                .requiresTls(true)
                .blockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .endpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS)
                        .build())
                .build();
    }
}
