// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_DESCRIPTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT_ADDRESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT_TYPE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.KEY_REQUIRED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REGISTERED_ENDPOINTS_EXCEEDED_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.addressbook.RegisteredNodeCreateTransactionBody;
import com.hedera.hapi.node.addressbook.RegisteredServiceEndpoint;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.impl.WritableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.handlers.RegisteredNodeCreateHandler;
import com.hedera.node.app.service.addressbook.impl.records.RegisteredNodeCreateStreamBuilder;
import com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator;
import com.hedera.node.app.service.entityid.NodeIdGenerator;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private com.hedera.node.app.service.token.ReadableAccountStore accountStore;

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
    @DisplayName("handle fails for description > 100 utf-8 bytes")
    void handleFailsForTooLongDescription() {
        final var longDesc = "a".repeat(101);
        final var txn = txnWithOp(opBuilder().description(longDesc).build());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(newConfig());
        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertThat(msg.getStatus()).isEqualTo(INVALID_NODE_DESCRIPTION);
    }

    @Test
    @DisplayName("pureChecks fails for empty service endpoints")
    void pureChecksFailsForEmptyEndpoints() {
        final var txn =
                txnWithOp(opBuilder().serviceEndpoint(java.util.List.of()).build());
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_REGISTERED_ENDPOINT);
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
    @DisplayName("pureChecks fails for empty admin key (KEY_REQUIRED)")
    void pureChecksFailsForEmptyAdminKey() {
        final var emptyKey = Key.newBuilder().keyList(KeyList.DEFAULT).build();
        final var txn = txnWithOp(opBuilder().adminKey(emptyKey).build());
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(KEY_REQUIRED);
    }

    @Test
    @DisplayName("pureChecks passes for valid transaction")
    void pureChecksPassesForValidInput() {
        final var txn = txnWithOp(opBuilder().build());
        given(pureChecksContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    @DisplayName("handle fails when attributeValidator rejects admin key")
    void handleFailsWhenAttributeValidatorRejectsKey() {
        final var attributeValidator = mock(AttributeValidator.class);
        final var txn = txnWithOp(opBuilder().adminKey(key).build());

        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(newConfig());
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        doThrow(new HandleException(INVALID_ADMIN_KEY))
                .when(attributeValidator)
                .validateKey(eq(key), eq(INVALID_ADMIN_KEY));

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertThat(msg.getStatus()).isEqualTo(INVALID_ADMIN_KEY);
    }

    @Test
    @DisplayName("handle fails when endpoint count exceeds config max")
    void handleFailsForTooManyEndpoints() {
        final var endpoints = new ArrayList<RegisteredServiceEndpoint>();
        for (int i = 0; i < 51; i++) {
            endpoints.add(validEndpoint());
        }
        final var txn = txnWithOp(opBuilder().serviceEndpoint(endpoints).build());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(newConfig());

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertThat(msg.getStatus()).isEqualTo(REGISTERED_ENDPOINTS_EXCEEDED_LIMIT);
    }

    @Test
    @DisplayName("handle fails for endpoint with invalid IP length")
    void handleFailsForInvalidIpLength() {
        final var badEndpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {127, 0, 0}))
                .port(443)
                .blockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .endpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS)
                        .build())
                .build();
        final var txn =
                txnWithOp(opBuilder().serviceEndpoint(List.of(badEndpoint)).build());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(newConfig());

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertThat(msg.getStatus()).isEqualTo(INVALID_REGISTERED_ENDPOINT_ADDRESS);
    }

    @Test
    @DisplayName("handle fails for endpoint missing endpoint type")
    void handleFailsForMissingEndpointType() {
        final var badEndpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {10, 0, 0, 1}))
                .port(443)
                .build();
        final var txn =
                txnWithOp(opBuilder().serviceEndpoint(List.of(badEndpoint)).build());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(newConfig());

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertThat(msg.getStatus()).isEqualTo(INVALID_REGISTERED_ENDPOINT_TYPE);
    }

    @Test
    @DisplayName("handle succeeds with empty description (optional field)")
    void handleSucceedsWithEmptyDescription() {
        final var stack = mock(HandleContext.SavepointStack.class);
        final var attributeValidator = mock(AttributeValidator.class);
        final long newId = 42L;
        final var txn = txnWithOp(opBuilder().description("").build());

        givenHandleContext(txn, newId, stack, attributeValidator);

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(writableRegisteredNodeStore).put(any());
        verify(recordBuilder).registeredNodeID(newId);
    }

    @Test
    @DisplayName("handle succeeds with null description (optional field)")
    void handleSucceedsWithNullDescription() {
        final var stack = mock(HandleContext.SavepointStack.class);
        final var attributeValidator = mock(AttributeValidator.class);
        final long newId = 42L;
        final var txn = txnWithOp(RegisteredNodeCreateTransactionBody.newBuilder()
                .adminKey(key)
                .serviceEndpoint(List.of(validEndpoint()))
                .build());

        givenHandleContext(txn, newId, stack, attributeValidator);

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(writableRegisteredNodeStore).put(any());
    }

    @Test
    @DisplayName("handle fails for description containing zero byte")
    void handleFailsForDescriptionWithZeroByte() {
        final var descWithNull = "desc\0ription";
        final var txn = txnWithOp(opBuilder().description(descWithNull).build());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(newConfig());
        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertThat(msg.getStatus()).isEqualTo(INVALID_NODE_DESCRIPTION);
    }

    @Test
    @DisplayName("handle succeeds with description at exactly 100 UTF-8 bytes")
    void handleSucceedsWithDescriptionAtExactLimit() {
        final var stack = mock(HandleContext.SavepointStack.class);
        final var attributeValidator = mock(AttributeValidator.class);
        final long newId = 99L;
        final var exactly100 = "a".repeat(100);
        final var txn = txnWithOp(opBuilder().description(exactly100).build());

        givenHandleContext(txn, newId, stack, attributeValidator);

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(writableRegisteredNodeStore).put(any());
    }

    @Test
    @DisplayName("handle persists RegisteredNode with correct field values")
    void handlePersistsCorrectNodeFields() {
        final var stack = mock(HandleContext.SavepointStack.class);
        final var attributeValidator = mock(AttributeValidator.class);
        final long newId = 5678L;
        final var description = "my block node";
        final var endpoint = validEndpoint();
        final var txn = txnWithOp(opBuilder()
                .adminKey(key)
                .description(description)
                .serviceEndpoint(List.of(endpoint))
                .build());

        givenHandleContext(txn, newId, stack, attributeValidator);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        final var persisted = captor.getValue();

        assertEquals(newId, persisted.registeredNodeId());
        assertEquals(key, persisted.adminKey());
        assertEquals(description, persisted.description());
        assertEquals(List.of(endpoint), persisted.serviceEndpoint());

        verify(recordBuilder).registeredNodeID(newId);
    }

    @Test
    @DisplayName("handle succeeds with domain-name based endpoint")
    void handleSucceedsWithDomainEndpoint() {
        final var stack = mock(HandleContext.SavepointStack.class);
        final var attributeValidator = mock(AttributeValidator.class);
        final long newId = 100L;
        final var domainEndpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("block.example.com")
                .port(443)
                .requiresTls(true)
                .blockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .endpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.SUBSCRIBE_STREAM)
                        .build())
                .build();
        final var txn =
                txnWithOp(opBuilder().serviceEndpoint(List.of(domainEndpoint)).build());

        givenHandleContext(txn, newId, stack, attributeValidator);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        assertEquals(List.of(domainEndpoint), captor.getValue().serviceEndpoint());
    }

    @Test
    @DisplayName("handle succeeds with multiple mixed-type endpoints")
    void handleSucceedsWithMultipleMixedEndpoints() {
        final var stack = mock(HandleContext.SavepointStack.class);
        final var attributeValidator = mock(AttributeValidator.class);
        final long newId = 400L;
        final var blockEndpoint = validEndpoint();
        final var mirrorEndpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("mirror.example.com")
                .port(443)
                .mirrorNode(RegisteredServiceEndpoint.MirrorNodeEndpoint.DEFAULT)
                .build();
        final var rpcEndpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[16]))
                .port(8545)
                .rpcRelay(RegisteredServiceEndpoint.RpcRelayEndpoint.DEFAULT)
                .build();
        final var txn = txnWithOp(opBuilder()
                .serviceEndpoint(List.of(blockEndpoint, mirrorEndpoint, rpcEndpoint))
                .build());

        givenHandleContext(txn, newId, stack, attributeValidator);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        assertEquals(3, captor.getValue().serviceEndpoint().size());
    }

    @Test
    @DisplayName("handle fails for endpoint with missing address (no IP or domain)")
    void handleFailsForMissingAddress() {
        final var badEndpoint = RegisteredServiceEndpoint.newBuilder()
                .port(443)
                .blockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .endpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS)
                        .build())
                .build();
        final var txn =
                txnWithOp(opBuilder().serviceEndpoint(List.of(badEndpoint)).build());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(newConfig());

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertThat(msg.getStatus()).isEqualTo(INVALID_REGISTERED_ENDPOINT);
    }

    private void givenHandleContext(
            final TransactionBody txn,
            final long newId,
            final HandleContext.SavepointStack stack,
            final AttributeValidator attributeValidator) {
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(newConfig());
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableRegisteredNodeStore.class)).willReturn(writableRegisteredNodeStore);
        given(handleContext.nodeIdGenerator()).willReturn(nodeIdGenerator);
        given(nodeIdGenerator.newNodeId()).willReturn(newId);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        given(stack.getBaseBuilder(any())).willReturn(recordBuilder);
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

    private static Configuration newConfig() {
        return new TestConfigBuilder().withConfigDataType(NodesConfig.class).getOrCreateConfig();
    }
}
