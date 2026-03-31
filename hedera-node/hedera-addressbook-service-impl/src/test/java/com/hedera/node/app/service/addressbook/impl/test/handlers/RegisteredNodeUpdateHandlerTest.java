// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_DESCRIPTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT_ADDRESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT_TYPE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_REGISTERED_NODE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.KEY_REQUIRED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REGISTERED_ENDPOINTS_EXCEEDED_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.addressbook.RegisteredNodeUpdateTransactionBody;
import com.hedera.hapi.node.addressbook.RegisteredServiceEndpoint;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.ReadableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.WritableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.handlers.RegisteredNodeUpdateHandler;
import com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.store.StoreFactory;
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
class RegisteredNodeUpdateHandlerTest extends AddressBookTestBase {
    @Mock
    private HandleContext handleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private com.hedera.node.app.service.token.ReadableAccountStore accountStore;

    @Mock
    private WritableRegisteredNodeStore writableRegisteredNodeStore;

    @Mock
    private ReadableRegisteredNodeStore readableRegisteredNodeStore;

    private RegisteredNodeUpdateHandler subject;

    private final long registeredNodeId = 1234L;
    private RegisteredNode existing;
    private final RegisteredServiceEndpoint existingEndpoint = validEndpoint();

    @BeforeEach
    void setUp() {
        subject = new RegisteredNodeUpdateHandler(new AddressBookValidator());
        existing = new RegisteredNode.Builder()
                .registeredNodeId(registeredNodeId)
                .adminKey(key)
                .description("old")
                .serviceEndpoint(List.of(existingEndpoint))
                .build();
    }

    // ========== pureChecks tests ==========

    @Test
    @DisplayName("pureChecks fails for negative node ID")
    void pureChecksFailsForNegativeId() {
        final var txn = txnWithOp(opBuilder().registeredNodeId(-1).build());
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(INVALID_REGISTERED_NODE_ID, msg.responseCode());
    }

    @Test
    @DisplayName("pureChecks passes for valid input")
    void pureChecksPassesForValidInput() {
        final var txn = txnWithOp(opBuilder().build());
        given(pureChecksContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    @DisplayName("pureChecks passes with minimal op (only registeredNodeId)")
    void pureChecksPassesWithMinimalOp() {
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .build());
        given(pureChecksContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
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
    @DisplayName("pureChecks fails for empty admin key (KEY_REQUIRED)")
    void pureChecksFailsForEmptyAdminKey() {
        final var emptyKey = Key.newBuilder().keyList(KeyList.DEFAULT).build();
        final var txn = txnWithOp(opBuilder().adminKey(emptyKey).build());
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(KEY_REQUIRED);
    }

    @Test
    @DisplayName("pureChecks passes when no admin key is set (not rotating)")
    void pureChecksPassesWhenNoAdminKeySet() {
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .description("new desc")
                .build());
        given(pureChecksContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    // ========== preHandle tests ==========

    @Test
    @DisplayName("preHandle requires both old and new admin key when rotating")
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
    @DisplayName("preHandle requires only old admin key when not rotating")
    void preHandleRequiresOnlyOldAdminKeyWhenNotRotating() throws PreCheckException {
        mockAccountLookup(aPrimitiveKey, payerId, accountStore);
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .description("new desc")
                .build());

        final var ctx = new com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext(accountStore, txn);
        ctx.registerStore(ReadableRegisteredNodeStore.class, readableRegisteredNodeStore);
        given(readableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);

        subject.preHandle(ctx);
        assertThat(ctx.requiredNonPayerKeys()).contains(key);
        assertThat(ctx.requiredNonPayerKeys()).doesNotContain(anotherKey);
    }

    @Test
    @DisplayName("preHandle fails when node not found")
    void preHandleFailsWhenNodeNotFound() throws PreCheckException {
        mockAccountLookup(aPrimitiveKey, payerId, accountStore);
        final var txn = txnWithOp(opBuilder().build());

        final var ctx = new com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext(accountStore, txn);
        ctx.registerStore(ReadableRegisteredNodeStore.class, readableRegisteredNodeStore);
        given(readableRegisteredNodeStore.get(registeredNodeId)).willReturn(null);

        final var msg = assertThrows(PreCheckException.class, () -> subject.preHandle(ctx));
        assertEquals(INVALID_REGISTERED_NODE_ID, msg.responseCode());
    }

    // ========== handle — validation failure tests ==========

    @Test
    @DisplayName("handle fails if target node is missing")
    void handleFailsIfTargetMissing() {
        final var txn = txnWithOp(opBuilder().registeredNodeId(registeredNodeId).build());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(newConfig());
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableRegisteredNodeStore.class)).willReturn(writableRegisteredNodeStore);
        given(writableRegisteredNodeStore.get(registeredNodeId)).willReturn(null);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INVALID_REGISTERED_NODE_ID, msg.getStatus());
    }

    @Test
    @DisplayName("handle fails for description > 100 UTF-8 bytes")
    void handleFailsForTooLongDescription() {
        final var longDesc = "a".repeat(101);
        final var txn = txnWithOp(opBuilder().description(longDesc).build());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(newConfig());

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertThat(msg.getStatus()).isEqualTo(INVALID_NODE_DESCRIPTION);
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

    // ========== handle — successful update tests ==========

    @Test
    @DisplayName("handle updates only description, preserving admin key and endpoints")
    void handleSucceedsUpdatingOnlyDescription() {
        final var newDesc = "updated description";
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .description(newDesc)
                .build());
        givenHandleContextWithExisting(txn);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        final var persisted = captor.getValue();

        assertEquals(newDesc, persisted.description());
        assertEquals(existing.adminKey(), persisted.adminKey());
        assertEquals(existing.serviceEndpoint(), persisted.serviceEndpoint());
        assertEquals(registeredNodeId, persisted.registeredNodeId());
    }

    @Test
    @DisplayName("handle updates only admin key, preserving description and endpoints")
    void handleSucceedsUpdatingOnlyAdminKey() {
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .adminKey(anotherKey)
                .build());
        givenHandleContextWithExisting(txn);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        final var persisted = captor.getValue();

        assertEquals(anotherKey, persisted.adminKey());
        assertEquals(existing.description(), persisted.description());
        assertEquals(existing.serviceEndpoint(), persisted.serviceEndpoint());
    }

    @Test
    @DisplayName("handle updates only endpoints, preserving description and admin key")
    void handleSucceedsUpdatingOnlyEndpoints() {
        final var newEndpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {10, 0, 0, 1}))
                .port(8080)
                .blockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .endpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.PUBLISH)
                        .build())
                .build();
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .serviceEndpoint(List.of(newEndpoint))
                .build());
        givenHandleContextWithExisting(txn);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        final var persisted = captor.getValue();

        assertEquals(List.of(newEndpoint), persisted.serviceEndpoint());
        assertEquals(existing.description(), persisted.description());
        assertEquals(existing.adminKey(), persisted.adminKey());
    }

    @Test
    @DisplayName("handle updates all fields simultaneously")
    void handleSucceedsUpdatingAllFields() {
        final var newDesc = "brand new";
        final var newEndpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("new.example.com")
                .port(443)
                .mirrorNode(RegisteredServiceEndpoint.MirrorNodeEndpoint.DEFAULT)
                .build();
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .adminKey(anotherKey)
                .description(newDesc)
                .serviceEndpoint(List.of(newEndpoint))
                .build());
        givenHandleContextWithExisting(txn);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        final var persisted = captor.getValue();

        assertEquals(anotherKey, persisted.adminKey());
        assertEquals(newDesc, persisted.description());
        assertEquals(List.of(newEndpoint), persisted.serviceEndpoint());
        assertEquals(registeredNodeId, persisted.registeredNodeId());
    }

    @Test
    @DisplayName("handle with empty endpoint list preserves existing endpoints")
    void handleSucceedsWithEmptyEndpointListPreservingExisting() {
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .description("desc only")
                .build());
        givenHandleContextWithExisting(txn);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        assertEquals(existing.serviceEndpoint(), captor.getValue().serviceEndpoint());
    }

    @Test
    @DisplayName("handle succeeds with empty description (clears it)")
    void handleSucceedsWithEmptyDescription() {
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .description("")
                .build());
        givenHandleContextWithExisting(txn);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        assertEquals("", captor.getValue().description());
    }

    @Test
    @DisplayName("handle succeeds with description at exactly 100 UTF-8 bytes")
    void handleSucceedsWithDescriptionAtExactLimit() {
        final var exactly100 = "a".repeat(100);
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .description(exactly100)
                .build());
        givenHandleContextWithExisting(txn);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        assertEquals(exactly100, captor.getValue().description());
    }

    @Test
    @DisplayName("handle replaces endpoints entirely rather than appending")
    void handleEndpointReplacesRatherThanAppends() {
        final var newEndpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {(byte) 192, (byte) 168, 0, 1}))
                .port(9090)
                .rpcRelay(RegisteredServiceEndpoint.RpcRelayEndpoint.DEFAULT)
                .build();
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .serviceEndpoint(List.of(newEndpoint))
                .build());
        givenHandleContextWithExisting(txn);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        final var persisted = captor.getValue();

        assertEquals(1, persisted.serviceEndpoint().size());
        assertEquals(newEndpoint, persisted.serviceEndpoint().getFirst());
        assertThat(persisted.serviceEndpoint()).doesNotContain(existingEndpoint);
    }

    @Test
    @DisplayName("handle preserves registeredNodeId (immutable)")
    void handlePreservesRegisteredNodeId() {
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .description("changed")
                .build());
        givenHandleContextWithExisting(txn);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        assertEquals(registeredNodeId, captor.getValue().registeredNodeId());
    }

    @Test
    @DisplayName("handle succeeds with domain-name based endpoint")
    void handleSucceedsWithDomainEndpoint() {
        final var domainEndpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("block.example.com")
                .port(443)
                .requiresTls(true)
                .blockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .endpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.SUBSCRIBE_STREAM)
                        .build())
                .build();
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .serviceEndpoint(List.of(domainEndpoint))
                .build());
        givenHandleContextWithExisting(txn);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        assertEquals(List.of(domainEndpoint), captor.getValue().serviceEndpoint());
    }

    @Test
    @DisplayName("handle succeeds with multiple mixed-type endpoints")
    void handleSucceedsWithMultipleMixedEndpoints() {
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
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .serviceEndpoint(List.of(blockEndpoint, mirrorEndpoint, rpcEndpoint))
                .build());
        givenHandleContextWithExisting(txn);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        assertEquals(3, captor.getValue().serviceEndpoint().size());
    }

    @Test
    @DisplayName("handle succeeds with IPv6 endpoint")
    void handleSucceedsWithIpv6Endpoint() {
        final var ipv6Endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[16]))
                .port(443)
                .blockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .endpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATE_PROOF)
                        .build())
                .build();
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .serviceEndpoint(List.of(ipv6Endpoint))
                .build());
        givenHandleContextWithExisting(txn);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        assertEquals(List.of(ipv6Endpoint), captor.getValue().serviceEndpoint());
    }

    @Test
    @DisplayName("handle with no optional fields leaves node unchanged")
    void handleNoOpUpdateLeavesNodeUnchanged() {
        final var txn = txnWithOp(RegisteredNodeUpdateTransactionBody.newBuilder()
                .registeredNodeId(registeredNodeId)
                .build());
        givenHandleContextWithExisting(txn);

        assertDoesNotThrow(() -> subject.handle(handleContext));

        final var captor = ArgumentCaptor.forClass(RegisteredNode.class);
        verify(writableRegisteredNodeStore).put(captor.capture());
        final var persisted = captor.getValue();

        assertEquals(existing.adminKey(), persisted.adminKey());
        assertEquals(existing.description(), persisted.description());
        assertEquals(existing.serviceEndpoint(), persisted.serviceEndpoint());
        assertEquals(existing.registeredNodeId(), persisted.registeredNodeId());
    }

    // ========== calculateFees tests ==========

    @Test
    @DisplayName("calculateFees uses signature-based pricing with SubType.DEFAULT")
    void calculateFeesUsesSignatureBasedPricing() {
        final var feeCtx = mock(FeeContext.class);
        final var feeCalcFact = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        final var expectedFees = new Fees(1, 0, 0);
        given(feeCtx.configuration()).willReturn(newConfig());
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFact);
        given(feeCalcFact.feeCalculator(SubType.DEFAULT)).willReturn(feeCalc);
        given(feeCtx.numTxnSignatures()).willReturn(3);
        given(feeCalc.addVerificationsPerTransaction(2L)).willReturn(feeCalc);
        given(feeCalc.calculate()).willReturn(expectedFees);

        assertThat(subject.calculateFees(feeCtx)).isEqualTo(expectedFees);
        verify(feeCalc).resetUsage();
        verify(feeCalc).addVerificationsPerTransaction(2L);
    }

    @Test
    @DisplayName("calculateFees with zero signatures adds zero verifications")
    void calculateFeesWithZeroSignatures() {
        final var feeCtx = mock(FeeContext.class);
        final var feeCalcFact = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        final var expectedFees = new Fees(1, 0, 0);
        given(feeCtx.configuration()).willReturn(newConfig());
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFact);
        given(feeCalcFact.feeCalculator(SubType.DEFAULT)).willReturn(feeCalc);
        given(feeCtx.numTxnSignatures()).willReturn(0);
        given(feeCalc.addVerificationsPerTransaction(0L)).willReturn(feeCalc);
        given(feeCalc.calculate()).willReturn(expectedFees);

        assertThat(subject.calculateFees(feeCtx)).isEqualTo(expectedFees);
        verify(feeCalc).addVerificationsPerTransaction(0L);
    }

    @Test
    @DisplayName("calculateFees with multiple signatures adds n-1 verifications")
    void calculateFeesWithMultipleSignatures() {
        final var feeCtx = mock(FeeContext.class);
        final var feeCalcFact = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        final var expectedFees = new Fees(1, 0, 0);
        given(feeCtx.configuration()).willReturn(newConfig());
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFact);
        given(feeCalcFact.feeCalculator(SubType.DEFAULT)).willReturn(feeCalc);
        given(feeCtx.numTxnSignatures()).willReturn(5);
        given(feeCalc.addVerificationsPerTransaction(4L)).willReturn(feeCalc);
        given(feeCalc.calculate()).willReturn(expectedFees);

        assertThat(subject.calculateFees(feeCtx)).isEqualTo(expectedFees);
        verify(feeCalc).addVerificationsPerTransaction(4L);
    }

    @Test
    @DisplayName("calculateFees throws NOT_SUPPORTED when registeredNodesEnabled is false")
    void calculateFeesThrowsWhenDisabled() {
        final var feeCtx = mock(FeeContext.class);
        final var config = new TestConfigBuilder()
                .withConfigDataType(NodesConfig.class)
                .withValue("nodes.registeredNodesEnabled", false)
                .getOrCreateConfig();
        given(feeCtx.configuration()).willReturn(config);

        final var ex = assertThrows(HandleException.class, () -> subject.calculateFees(feeCtx));
        assertEquals(NOT_SUPPORTED, ex.getStatus());
    }

    // ========== helper methods ==========

    private void givenHandleContextWithExisting(final TransactionBody txn) {
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration()).willReturn(newConfig());
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableRegisteredNodeStore.class)).willReturn(writableRegisteredNodeStore);
        given(writableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);
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

    private static Configuration newConfig() {
        return new TestConfigBuilder().withConfigDataType(NodesConfig.class).getOrCreateConfig();
    }
}
