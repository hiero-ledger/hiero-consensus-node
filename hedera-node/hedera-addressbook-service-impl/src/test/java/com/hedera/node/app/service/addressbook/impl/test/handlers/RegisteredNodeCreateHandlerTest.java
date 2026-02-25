// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_DESCRIPTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SERVICE_ENDPOINT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.addressbook.RegisteredNodeCreateTransactionBody;
import com.hedera.hapi.node.addressbook.RegisteredServiceEndpoint;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.impl.WritableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.handlers.RegisteredNodeCreateHandler;
import com.hedera.node.app.service.addressbook.impl.records.RegisteredNodeCreateStreamBuilder;
import com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator;
import com.hedera.node.app.service.entityid.NodeIdGenerator;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegisteredNodeCreateHandlerTest extends AddressBookTestBase {
    @Mock
    private HandleContext handleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private NodeIdGenerator nodeIdGenerator;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private WritableRegisteredNodeStore writableRegisteredNodeStore;

    @Mock
    private RegisteredNodeCreateStreamBuilder recordBuilder;

    private RegisteredNodeCreateHandler subject;

    @BeforeEach
    void setUp() {
        subject = new RegisteredNodeCreateHandler(new AddressBookValidator());
    }

    @Test
    @DisplayName("pureChecks fails for invalid admin key")
    void pureChecksFailsForInvalidAdminKey() {
        final var txn = txnWithOp(opBuilder().adminKey(invalidKey).build());
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_ADMIN_KEY);
    }

    @Test
    @DisplayName("pureChecks fails for description > 100 utf-8 bytes")
    void pureChecksFailsForTooLongDescription() {
        final var longDesc = "a".repeat(101);
        final var txn = txnWithOp(opBuilder().description(longDesc).build());
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_NODE_DESCRIPTION);
    }

    @Test
    @DisplayName("pureChecks fails for empty service endpoints")
    void pureChecksFailsForEmptyEndpoints() {
        final var txn =
                txnWithOp(opBuilder().serviceEndpoint(java.util.List.of()).build());
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_SERVICE_ENDPOINT);
    }

    @Test
    void preHandleRequiresAdminKeySignature() throws PreCheckException {
        mockAccountLookup(anotherKey, payerId, accountStore);
        final var txn = txnWithOp(opBuilder().adminKey(key).build());
        final var ctx = new com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext(accountStore, txn);
        subject.preHandle(ctx);
        assertThat(ctx.requiredNonPayerKeys()).contains(key);
    }

    @Test
    void handlePersistsRegisteredNodeAndSetsReceipt() {
        final long newId = 1234L;
        final var txn = txnWithOp(opBuilder().adminKey(key).build());

        given(handleContext.body()).willReturn(txn);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableRegisteredNodeStore.class)).willReturn(writableRegisteredNodeStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(handleContext.nodeIdGenerator()).willReturn(nodeIdGenerator);
        given(nodeIdGenerator.newNodeId()).willReturn(newId);
        final var stack = mock(HandleContext.SavepointStack.class);
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(any())).willReturn(recordBuilder);

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(writableRegisteredNodeStore).put(any());
        verify(recordBuilder).registeredNodeID(newId);
    }

    @Test
    void handleFailsIfNodeAccountDeleted() {
        final var deletedAccount = mock(com.hedera.hapi.node.state.token.Account.class);
        given(deletedAccount.deleted()).willReturn(true);
        given(accountStore.getAccountById(accountId)).willReturn(deletedAccount);

        final var op = opBuilder().adminKey(key).build();
        final var txn = txnWithOp(op);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableRegisteredNodeStore.class)).willReturn(writableRegisteredNodeStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ACCOUNT_DELETED, msg.getStatus());
    }

    @Test
    void handleFailsIfNodeAccountMissing() {
        given(accountStore.getAccountById(accountId)).willReturn(null);

        final var op = opBuilder().adminKey(key).build();
        final var txn = txnWithOp(op);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableRegisteredNodeStore.class)).willReturn(writableRegisteredNodeStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INVALID_NODE_ACCOUNT_ID, msg.getStatus());
    }

    private TransactionBody txnWithOp(final RegisteredNodeCreateTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId).build())
                .registeredNodeCreate(op)
                .build();
    }

    private RegisteredNodeCreateTransactionBody.Builder opBuilder() {
        return RegisteredNodeCreateTransactionBody.newBuilder()
                .adminKey(key)
                .description("desc")
                .serviceEndpoint(java.util.List.of(validEndpoint()));
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
