// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.GRPC_WEB_PROXY_NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GRPC_CERTIFICATE_HASH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_IPV4_ADDRESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UPDATE_NODE_ACCOUNT_NOT_ALLOWED;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_LABEL;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.addressbook.NodeUpdateTransactionBody;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeUpdateHandler;
import com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeUpdateHandlerTest extends AddressBookTestBase {

    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private AttributeValidator validator;

    private final AccountID newAccountId = idFactory.newAccountId(53);
    private TransactionBody txn;
    private NodeUpdateHandler subject;
    private static List<X509Certificate> certList;

    @BeforeAll
    static void beforeAll() {
        certList = generateX509Certificates(3);
    }

    @BeforeEach
    void setUp() {
        final var addressBookValidator = new AddressBookValidator();
        subject = new NodeUpdateHandler(addressBookValidator);
    }

    @Test
    @DisplayName("pureChecks fail when nodeId is negative")
    void nodeIdCannotNegative() {
        txn = new NodeUpdateBuilder().build();
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_NODE_ID);
    }

    @Test
    @DisplayName("pureChecks fail when gossipCaCertificate empty")
    void gossipCaCertificateCannotEmpty() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1)
                .withAccountId(accountId)
                .withGossipCaCertificate(Bytes.EMPTY)
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_GOSSIP_CA_CERTIFICATE);
    }

    @Test
    @DisplayName("pureChecks fail when grpcCertHash is empty")
    void grpcCertHashCannotEmpty() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1)
                .withAccountId(accountId)
                .withGrpcCertificateHash(Bytes.EMPTY)
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_GRPC_CERTIFICATE_HASH);
    }

    @Test
    @DisplayName("invalid adminKey fail")
    void adminKeyInvalid() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1)
                .withAccountId(accountId)
                .withAdminKey(invalidKey)
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_ADMIN_KEY);
    }

    @Test
    @DisplayName("pureChecks succeeds when expected attributes are specified")
    void pureCheckPass() throws CertificateEncodingException {
        txn = new NodeUpdateBuilder()
                .withNodeId(1)
                .withAccountId(accountId)
                .withGossipCaCertificate(Bytes.wrap(certList.get(1).getEncoded()))
                .withAdminKey(key)
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void nodeIdMustInState() {
        txn = new NodeUpdateBuilder().withNodeId(2L).build();
        given(handleContext.body()).willReturn(txn);
        refreshStoresWithCurrentNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.nodeMaxDescriptionUtf8Bytes", 10)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_NODE_ID, msg.getStatus());
    }

    @Test
    void accountIdMustInState() {
        txn = new NodeUpdateBuilder().withNodeId(1L).withAccountId(accountId).build();
        given(accountStore.contains(accountId)).willReturn(false);
        given(handleContext.body()).willReturn(txn);
        refreshStoresWithCurrentNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.nodeMaxDescriptionUtf8Bytes", 10)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID, msg.getStatus());
    }

    @Test
    void failsWhenDescriptionTooLarge() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withDescription("Description")
                .build();
        setupHandle();

        refreshStoresWithCurrentNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.nodeMaxDescriptionUtf8Bytes", 10)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_NODE_DESCRIPTION, msg.getStatus());
    }

    @Test
    void failsWhenDescriptionContainZeroByte() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withDescription("Des\0cription")
                .build();
        setupHandle();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.nodeMaxDescriptionUtf8Bytes", 12)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_NODE_DESCRIPTION, msg.getStatus());
    }

    @Test
    void failsWhenGossipEndpointTooLarge() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2, endpoint3))
                .build();
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.GOSSIP_ENDPOINTS_EXCEEDED_LIMIT, msg.getStatus());
    }

    @Test
    void failsWhenGossipEndpointHaveIPAndFQDN() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint4))
                .build();
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN, msg.getStatus());
    }

    @Test
    void failsWhenEndpointHaveEmptyIPAndFQDN() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint5))
                .build();
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenEndpointHaveZeroIp() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint6))
                .build();
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenServiceEndpointTooLarge() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint1, endpoint2, endpoint3))
                .build();
        setupHandle();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.maxGossipEndpoint", 2)
                .withValue("nodes.maxServiceEndpoint", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.SERVICE_ENDPOINTS_EXCEEDED_LIMIT, msg.getStatus());
    }

    @Test
    void failsWhenEndpointHaveIPAndFQDN() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint1, endpoint4))
                .build();
        setupHandle();

        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.maxGossipEndpoint", 2)
                .withValue("nodes.maxServiceEndpoint", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.IP_FQDN_CANNOT_BE_SET_FOR_SAME_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenEndpointFQDNTooLarge() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint1, endpoint3))
                .build();
        setupHandle();

        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.maxGossipEndpoint", 2)
                .withValue("nodes.maxServiceEndpoint", 2)
                .withValue("nodes.maxFqdnSize", 4)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.FQDN_SIZE_TOO_LARGE, msg.getStatus());
    }

    @Test
    void handleWorkAsExpected() throws CertificateEncodingException {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withAccountId(accountId)
                .withDescription("Description")
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint1, endpoint3))
                .withGossipCaCertificate(Bytes.wrap(certList.get(2).getEncoded()))
                .withGrpcCertificateHash(Bytes.wrap("hash"))
                .withAdminKey(key)
                .withDeclineReward(true)
                .build();
        given(handleContext.body()).willReturn(txn);
        refreshStoresWithMoreNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.nodeMaxDescriptionUtf8Bytes", 12)
                .withValue("nodes.maxGossipEndpoint", 4)
                .withValue("nodes.maxServiceEndpoint", 3)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);
        given(accountStore.contains(accountId)).willReturn(true);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(handleContext.attributeValidator()).willReturn(validator);

        assertDoesNotThrow(() -> subject.handle(handleContext));
        final var updatedNode = writableStore.get(1L);
        assertNotNull(updatedNode);
        assertEquals(1, updatedNode.nodeId());
        assertEquals(accountId, updatedNode.accountId());
        assertEquals("Description", updatedNode.description());
        assertArrayEquals(
                (List.of(endpoint1, endpoint2)).toArray(),
                updatedNode.gossipEndpoint().toArray());
        assertArrayEquals(
                (List.of(endpoint1, endpoint3)).toArray(),
                updatedNode.serviceEndpoint().toArray());
        assertArrayEquals(
                certList.get(2).getEncoded(), updatedNode.gossipCaCertificate().toByteArray());
        assertArrayEquals("hash".getBytes(), updatedNode.grpcCertificateHash().toByteArray());
        assertTrue(updatedNode.declineReward());
        assertEquals(key, updatedNode.adminKey());
    }

    @Test
    void nothingHappensIfUpdateHasNoop() {
        txn = new NodeUpdateBuilder().withNodeId(1L).build();
        given(handleContext.body()).willReturn(txn);
        refreshStoresWithCurrentNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.maxGossipEndpoint", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);

        assertDoesNotThrow(() -> subject.handle(handleContext));
        final var updatedNode = writableStore.get(1L);
        assertEquals(node, updatedNode);
    }

    @Test
    void preHandleRequiresAdminKeySigForNonAddressBookAdmin() throws PreCheckException {
        txn = new NodeUpdateBuilder()
                .withNodeId(nodeId.number())
                .withAccountId(newAccountId)
                .withAdminKey(key)
                .build();
        mockAccountLookup(aPrimitiveKey, newAccountId, accountStore);
        final var context = setupPreHandle(true, txn);
        subject.preHandle(context);
        assertThat(txn).isEqualTo(context.body());
        assertThat(context.payerKey()).isEqualTo(anotherKey);
        assertThat(context.requiredNonPayerKeys().size()).isEqualTo(2);
        assertThat(context.requiredNonPayerKeys()).contains(key, aPrimitiveKey);
    }

    @Test
    void preHandleFailedWhenAdminKeyInValid() throws PreCheckException {
        txn = new NodeUpdateBuilder()
                .withNodeId(nodeId.number())
                .withAdminKey(invalidKey)
                .build();
        final var context = setupPreHandle(true, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_ADMIN_KEY);
    }

    @Test
    void preHandleFailedWhenNodeNotExist() throws PreCheckException {
        txn = new NodeUpdateBuilder().withNodeId(2).build();
        final var context = setupPreHandle(true, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_NODE_ID);
    }

    @Test
    void preHandleFailedWhenNodeDeleted() throws PreCheckException {
        givenValidNode(true);
        refreshStoresWithCurrentNodeInReadable();
        txn = new NodeUpdateBuilder().withNodeId(nodeId.number()).build();
        final var context = setupPreHandle(true, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_NODE_ID);
    }

    @Test
    void preHandleFailedWhenOldAdminKeyInValid() throws PreCheckException {
        givenValidNodeWithAdminKey(invalidKey);
        refreshStoresWithCurrentNodeInReadable();
        txn = new NodeUpdateBuilder().withNodeId(nodeId.number()).build();
        final var context = setupPreHandle(true, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_ADMIN_KEY);
    }

    @Test
    void preHandleFailedWhenAccountIdNotGood() throws PreCheckException {
        txn = new NodeUpdateBuilder()
                .withNodeId(nodeId.number())
                .withAdminKey(key)
                .withAccountId(AccountID.DEFAULT)
                .build();
        final var context = setupPreHandle(true, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_NODE_ACCOUNT_ID);
    }

    @Test
    void preHandleFailedWhenAccountIdIsAlias() throws PreCheckException {
        txn = new NodeUpdateBuilder()
                .withNodeId(nodeId.number())
                .withAdminKey(key)
                .withAccountId(alias)
                .build();
        final var context = setupPreHandle(true, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_NODE_ACCOUNT_ID);
    }

    @Test
    void preHandleFailedWhenUpdateAccountIdNotAllowed() throws PreCheckException {
        txn = new NodeUpdateBuilder()
                .withNodeId(nodeId.number())
                .withAdminKey(key)
                .withAccountId(newAccountId)
                .build();
        final var context = setupPreHandle(false, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), UPDATE_NODE_ACCOUNT_NOT_ALLOWED);
    }

    @Test
    @DisplayName("check that fees are 1 for delete node trx")
    void testCalculateFeesInvocations() {
        final var feeCtx = mock(FeeContext.class);
        final var feeCalcFact = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFact);
        given(feeCalcFact.feeCalculator(any())).willReturn(feeCalc);
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.enableDAB", true)
                .getOrCreateConfig();
        given(feeCtx.configuration()).willReturn(config);

        given(feeCalc.addVerificationsPerTransaction(anyLong())).willReturn(feeCalc);
        given(feeCalc.calculate()).willReturn(new Fees(1, 0, 0));

        assertThat(subject.calculateFees(feeCtx)).isEqualTo(new Fees(1, 0, 0));
    }

    @Test
    void preHandleSpecialCaseWhenOnlyUpdatingAccountId() throws PreCheckException {
        // Setup existing node with an account ID
        givenValidNode();
        final var newAccountId = idFactory.newAccountId(4);

        // Create a node with existing account ID
        Node nodeWithAccount = Node.newBuilder()
                .nodeId(nodeId.number())
                .accountId(accountId)
                .adminKey(bPrimitiveKey)
                .build();

        // Set up the readable node state with our node - using correct builder pattern
        readableNodeState =
                emptyReadableNodeStateBuilder().value(nodeId, nodeWithAccount).build();

        given(readableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(readableNodeState);
        readableStore = new ReadableNodeStoreImpl(readableStates, readableEntityCounters);

        // Create transaction that only updates accountId
        txn = new NodeUpdateBuilder()
                .withNodeId(nodeId.number())
                .withAccountId(newAccountId)
                .build();

        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.updateAccountIdAllowed", true)
                .getOrCreateConfig();
        mockAccountLookup(aPrimitiveKey, newAccountId, accountStore);
        mockAccountKeyOrThrow(aPrimitiveKey, accountId, accountStore);
        mockAccountLookup(anotherKey, payerId, accountStore);
        final var context = new FakePreHandleContext(accountStore, txn, config);
        context.registerStore(ReadableNodeStore.class, readableStore);

        // Execute the method
        subject.preHandle(context);

        // Verify that either admin key or account key was accepted (via threshold key)
        // Check if any of the required keys is a threshold key
        boolean hasThresholdKey = context.requiredNonPayerKeys().stream().anyMatch(Key::hasThresholdKey);
        assertTrue(hasThresholdKey);

        // Find the threshold key and verify its properties
        context.requiredNonPayerKeys().stream()
                .filter(Key::hasThresholdKey)
                .findFirst()
                .ifPresent(key -> {
                    assertThat(key.thresholdKeyOrThrow().threshold()).isEqualTo(1);
                    assertThat(key.thresholdKeyOrThrow().keys().keys().size()).isEqualTo(2);
                    assertThat(key.thresholdKeyOrThrow().keys().keys())
                            .containsExactlyInAnyOrder(aPrimitiveKey, bPrimitiveKey);
                });
    }

    @Test
    void preHandleSpecialCaseWhenOnlyUpdatingAccountIdWithoutExistingAccount() throws PreCheckException {
        // Setup existing node without an account ID
        givenValidNode();

        // Create a node state with a node that has no account ID
        Node nodeWithoutAccount =
                Node.newBuilder().nodeId(nodeId.number()).adminKey(key).build();

        // Set up the readable node state with our modified node
        readableNodeState = emptyReadableNodeStateBuilder()
                .value(nodeId, nodeWithoutAccount)
                .build();

        given(readableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(readableNodeState);

        // Refresh the store with our configured state
        readableStore = new ReadableNodeStoreImpl(readableStates, readableEntityCounters);

        // Create transaction that only updates accountId
        txn = new NodeUpdateBuilder()
                .withNodeId(nodeId.number())
                .withAccountId(newAccountId)
                .build();
        mockAccountLookup(aPrimitiveKey, newAccountId, accountStore);
        final var context = setupPreHandle(true, txn);

        // Execute the method
        subject.preHandle(context);

        // Verify that only admin key was required
        assertThat(context.requiredNonPayerKeys().size()).isEqualTo(2);
        assertThat(context.requiredNonPayerKeys()).contains(key, aPrimitiveKey);
    }

    @Test
    void testHandleProxyEndpointDisabled() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withGrpcProxyEndpoint(endpoint1)
                .build();

        given(handleContext.body()).willReturn(txn);
        refreshStoresWithCurrentNodeInWritable();

        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.webProxyEndpointsEnabled", false)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(GRPC_WEB_PROXY_NOT_SUPPORTED, msg.getStatus());
    }

    @Test
    void testHandleProxyEndpointSentinelValueResetsProxy() {
        // Set up a transaction with sentinel proxy endpoint
        ServiceEndpoint sentinelEndpoint = ServiceEndpoint.DEFAULT;
        txn = new NodeUpdateBuilder()
                .withNodeId(nodeId.number())
                .withGrpcProxyEndpoint(sentinelEndpoint)
                .build();

        // Create a node with existing proxy endpoint
        Node nodeWithProxy = Node.newBuilder()
                .nodeId(nodeId.number())
                .grpcProxyEndpoint(endpoint1)
                .build();

        setupWritableNodeStore(nodeWithProxy);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("nodes.webProxyEndpointsEnabled", true)
                        .getOrCreateConfig());
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);

        // Execute the method
        assertDoesNotThrow(() -> subject.handle(handleContext));

        // Verify the proxy endpoint was set to null
        assertNull(writableStore.get(nodeId.number()).grpcProxyEndpoint());
    }

    @Test
    void testHandleDeclineRewardUpdate() {
        // Setup a transaction that only updates decline reward
        txn = new NodeUpdateBuilder()
                .withNodeId(nodeId.number())
                .withDeclineReward(true)
                .build();

        // Create a node with decline reward set to false
        Node nodeWithoutDeclineReward =
                Node.newBuilder().nodeId(nodeId.number()).declineReward(false).build();

        setupWritableNodeStore(nodeWithoutDeclineReward);
        setupMinimalHandle();

        // Execute the method
        assertDoesNotThrow(() -> subject.handle(handleContext));
        // Verify decline reward was updated to true
        assertTrue(writableStore.get(nodeId.number()).declineReward());
    }

    @Test
    void testCalculateFeesWithDifferentNumSignatures() {
        // Test with 3 signatures
        FeeContext feeCtx = mock(FeeContext.class);
        FeeCalculatorFactory feeCalcFact = mock(FeeCalculatorFactory.class);
        FeeCalculator feeCalc = mock(FeeCalculator.class);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFact);
        given(feeCalcFact.feeCalculator(any())).willReturn(feeCalc);
        given(feeCtx.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("nodes.enableDAB", true)
                        .getOrCreateConfig());
        given(feeCtx.numTxnSignatures()).willReturn(3);
        given(feeCalc.addVerificationsPerTransaction(2L)).willReturn(feeCalc);
        given(feeCalc.calculate()).willReturn(new Fees(3, 0, 0));

        Fees result = subject.calculateFees(feeCtx);

        // Verify that addVerificationsPerTransaction was called with 2 (3-1)
        verify(feeCalc).addVerificationsPerTransaction(2L);
        assertThat(result).isEqualTo(new Fees(3, 0, 0));
    }

    @Test
    void testOneOfHelperCreatesThresholdKey() throws PreCheckException {
        // Setup existing node with an account ID
        givenValidNode();
        var newAccountId = idFactory.newAccountId(4);

        // Create transaction that only updates accountId
        txn = new NodeUpdateBuilder()
                .withNodeId(nodeId.number())
                .withAccountId(newAccountId)
                .build();

        final var context = setupPreHandle(true, txn);
        mockAccountLookup(aPrimitiveKey, newAccountId, accountStore);
        mockAccountKeyOrThrow(bPrimitiveKey, accountId, accountStore);

        // Execute preHandle
        subject.preHandle(context);

        // Check if any of the required keys is a threshold key
        boolean hasThresholdKey = context.requiredNonPayerKeys().stream().anyMatch(Key::hasThresholdKey);
        assertTrue(hasThresholdKey);

        // Find the threshold key and verify its properties
        context.requiredNonPayerKeys().stream()
                .filter(Key::hasThresholdKey)
                .findFirst()
                .ifPresent(key -> {
                    assertThat(key.thresholdKeyOrThrow().threshold()).isEqualTo(1);
                    assertThat(key.thresholdKeyOrThrow().keys().keys().size()).isEqualTo(2);
                });
    }

    @Test
    void testHandleInvalidEndpointWithNullIpAddress() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withGossipEndpoint(List.of(endpoint7))
                .build();
        // Create a node with existing proxy endpoint
        Node nodeWithProxy = Node.newBuilder()
                .nodeId(nodeId.number())
                .grpcProxyEndpoint(endpoint1)
                .build();

        setupWritableNodeStore(nodeWithProxy);
        setupMinimalHandle();

        final var exception = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_ENDPOINT, exception.getStatus());
    }

    @Test
    void testHandleInvalidEndpointWithInvalidIpAddress() {
        txn = new NodeUpdateBuilder()
                .withNodeId(1L)
                .withGossipEndpoint(List.of(endpoint8))
                .build();
        Node node = Node.newBuilder().nodeId(1L).grpcProxyEndpoint(endpoint1).build();
        setupWritableNodeStore(node);
        setupMinimalHandle();

        final var exception = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INVALID_IPV4_ADDRESS, exception.getStatus());
    }

    @Test
    void testHandleValidEmptyNodeUpdate() {
        // Setup a transaction that doesn't update any fields
        txn = new NodeUpdateBuilder().withNodeId(nodeId.number()).build();

        given(handleContext.body()).willReturn(txn);

        // Make a copy of the original node for comparison
        Node originalNode = node;

        // Set up writable store with our node
        writableNodeState = MapWritableKVState.<EntityNumber, Node>builder(NODES_STATE_ID, NODES_STATE_LABEL)
                .value(nodeId, originalNode)
                .build();

        given(writableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(writableNodeState);
        writableStore = spy(new WritableNodeStore(writableStates, writableEntityCounters));

        given(handleContext.configuration())
                .willReturn(HederaTestConfigBuilder.create().getOrCreateConfig());
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);

        // Execute the method
        assertDoesNotThrow(() -> subject.handle(handleContext));

        // Verify the node wasn't modified - should remain the same as original
        assertThat(writableStore.get(nodeId.number())).isEqualTo(originalNode);
    }

    @Test
    void testHandleMultipleFieldsUpdate() {
        // Setup transaction updating multiple fields together
        txn = new NodeUpdateBuilder()
                .withNodeId(nodeId.number())
                .withDescription("New Description")
                .withDeclineReward(true)
                .withAccountId(newAccountId)
                .build();

        given(handleContext.body()).willReturn(txn);

        // Set up writable store with our node
        writableNodeState = MapWritableKVState.<EntityNumber, Node>builder(NODES_STATE_ID, NODES_STATE_LABEL)
                .value(nodeId, node)
                .build();

        given(writableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(writableNodeState);
        writableStore = new WritableNodeStore(writableStates, writableEntityCounters);

        given(handleContext.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("nodes.nodeMaxDescriptionUtf8Bytes", 20)
                        .getOrCreateConfig());
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.contains(newAccountId)).willReturn(true);

        // Execute the method
        assertDoesNotThrow(() -> subject.handle(handleContext));

        // Verify all fields were updated
        final var stateNode = writableStore.get(nodeId.number());
        assertNotNull(stateNode);
        assertThat(stateNode.description()).isEqualTo("New Description");
        assertThat(stateNode.declineReward()).isTrue();
        assertThat(stateNode.accountId()).isEqualTo(newAccountId);
    }

    @Test
    void preHandleDisallowsAccountUpdateWhenDisabled() throws PreCheckException {
        txn = new NodeUpdateBuilder()
                .withNodeId(nodeId.number())
                .withAccountId(newAccountId)
                .build();

        final var context = setupPreHandle(false, txn);

        // Execute preHandle with updateAccountIdAllowed = false
        assertThrowsPreCheck(() -> subject.preHandle(context), UPDATE_NODE_ACCOUNT_NOT_ALLOWED);
    }

    private void setupWritableNodeStore(final Node node) {
        // Create a node with existing proxy endpoint
        final var entityNum = EntityNumber.newBuilder().number(node.nodeId()).build();

        // Set up writable store with our node
        writableNodeState = MapWritableKVState.<EntityNumber, Node>builder(NODES_STATE_ID, NODES_STATE_LABEL)
                .value(entityNum, node)
                .build();

        given(writableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(writableNodeState);
        writableStore = new WritableNodeStore(writableStates, writableEntityCounters);
    }

    private void setupMinimalHandle() {
        given(handleContext.body()).willReturn(txn);
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.maxGossipEndpoint", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);
    }

    private void setupHandle() {
        given(handleContext.body()).willReturn(txn);
        refreshStoresWithCurrentNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.maxGossipEndpoint", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);
        given(accountStore.contains(accountId)).willReturn(true);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
    }

    private PreHandleContext setupPreHandle(boolean updateAccountIdAllowed, TransactionBody txn)
            throws PreCheckException {
        return setupPreHandle(updateAccountIdAllowed, txn, payerId);
    }

    private PreHandleContext setupPreHandle(
            boolean updateAccountIdAllowed, TransactionBody txn, AccountID contextPayerId) throws PreCheckException {
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.updateAccountIdAllowed", updateAccountIdAllowed)
                .getOrCreateConfig();
        mockAccountLookup(anotherKey, contextPayerId, accountStore);
        final var context = new FakePreHandleContext(accountStore, txn, config);
        context.registerStore(ReadableNodeStore.class, readableStore);
        return context;
    }

    private class NodeUpdateBuilder {
        private long nodeId = -1L;
        private AccountID accountId = null;
        private String description = null;
        private List<ServiceEndpoint> gossipEndpoint = null;
        private List<ServiceEndpoint> serviceEndpoint = null;
        private ServiceEndpoint grpcProxyEndpoint = null;

        private Bytes gossipCaCertificate = null;

        private Bytes grpcCertificateHash = null;
        private Key adminKey = null;
        private AccountID contextPayerId = payerId;
        private Optional<Boolean> declineReward = Optional.empty();

        private NodeUpdateBuilder() {}

        public TransactionBody build() {
            final var txnId =
                    TransactionID.newBuilder().accountID(contextPayerId).transactionValidStart(consensusTimestamp);
            final var op = NodeUpdateTransactionBody.newBuilder();
            op.nodeId(nodeId);
            if (accountId != null) {
                op.accountId(accountId);
            }
            if (description != null) {
                op.description(description);
            }
            if (gossipEndpoint != null) {
                op.gossipEndpoint(gossipEndpoint);
            }
            if (serviceEndpoint != null) {
                op.serviceEndpoint(serviceEndpoint);
            }
            if (grpcProxyEndpoint != null) {
                op.grpcProxyEndpoint(grpcProxyEndpoint);
            }
            if (gossipCaCertificate != null) {
                op.gossipCaCertificate(gossipCaCertificate);
            }
            if (grpcCertificateHash != null) {
                op.grpcCertificateHash(grpcCertificateHash);
            }
            if (adminKey != null) {
                op.adminKey(adminKey);
            }
            declineReward.ifPresent(op::declineReward);

            return TransactionBody.newBuilder()
                    .transactionID(txnId)
                    .nodeUpdate(op.build())
                    .build();
        }

        public NodeUpdateBuilder withNodeId(final long nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public NodeUpdateBuilder withAccountId(final AccountID accountId) {
            this.accountId = accountId;
            return this;
        }

        public NodeUpdateBuilder withPayerId(final AccountID overridePayerId) {
            this.contextPayerId = overridePayerId;
            return this;
        }

        public NodeUpdateBuilder withDescription(final String description) {
            this.description = description;
            return this;
        }

        public NodeUpdateBuilder withGossipEndpoint(final List<ServiceEndpoint> gossipEndpoint) {
            this.gossipEndpoint = gossipEndpoint;
            return this;
        }

        public NodeUpdateBuilder withServiceEndpoint(final List<ServiceEndpoint> serviceEndpoint) {
            this.serviceEndpoint = serviceEndpoint;
            return this;
        }

        public NodeUpdateBuilder withGrpcProxyEndpoint(final ServiceEndpoint grpcProxyEndpoint) {
            this.grpcProxyEndpoint = grpcProxyEndpoint;
            return this;
        }

        public NodeUpdateBuilder withGossipCaCertificate(final Bytes gossipCaCertificate) {
            this.gossipCaCertificate = gossipCaCertificate;
            return this;
        }

        public NodeUpdateBuilder withGrpcCertificateHash(final Bytes grpcCertificateHash) {
            this.grpcCertificateHash = grpcCertificateHash;
            return this;
        }

        public NodeUpdateBuilder withAdminKey(final Key adminKey) {
            this.adminKey = adminKey;
            return this;
        }

        public NodeUpdateBuilder withDeclineReward(final boolean declineReward) {
            this.declineReward = Optional.of(declineReward);
            return this;
        }
    }
}
