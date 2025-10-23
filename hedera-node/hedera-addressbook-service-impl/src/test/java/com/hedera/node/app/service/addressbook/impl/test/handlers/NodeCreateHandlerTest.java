// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SERVICE_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.KEY_REQUIRED;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.addressbook.NodeCreateTransactionBody;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.impl.WritableAccountNodeRelStore;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeCreateHandler;
import com.hedera.node.app.service.addressbook.impl.records.NodeCreateStreamBuilder;
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
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeCreateHandlerTest extends AddressBookTestBase {

    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private NodeCreateStreamBuilder recordBuilder;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private AttributeValidator validator;

    private TransactionBody txn;
    private NodeCreateHandler subject;

    private static List<X509Certificate> certList;

    @BeforeAll
    static void beforeAll() {
        certList = generateX509Certificates(3);
    }

    @BeforeEach
    void setUp() {
        final var addressBookValidator = new AddressBookValidator();
        subject = new NodeCreateHandler(addressBookValidator);
    }

    @Test
    @DisplayName("pureChecks fail when accountId is null")
    void accountIdCannotNull() {
        txn = new NodeCreateBuilder().build(payerId);
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_NODE_ACCOUNT_ID);
    }

    @Test
    @DisplayName("pureChecks fail when accountId not set")
    void accountIdNeedSet() {
        txn = new NodeCreateBuilder().withAccountId(AccountID.DEFAULT).build(payerId);
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_NODE_ACCOUNT_ID);
    }

    @Test
    @DisplayName("pureChecks fail when accountId is alias")
    void accountIdCannotAlias() {
        txn = new NodeCreateBuilder().withAccountId(alias).build(payerId);
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_NODE_ACCOUNT_ID);
    }

    @Test
    @DisplayName("pureChecks fail when gossip_endpoint not specified")
    void gossipEndpointNeedSet() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of())
                .build(payerId);
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_GOSSIP_ENDPOINT);
    }

    @Test
    @DisplayName("pureChecks fail when service_endpoint not specified")
    void serviceEndpointNeedSet() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1))
                .withServiceEndpoint(List.of())
                .build(payerId);
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_SERVICE_ENDPOINT);
    }

    @Test
    @DisplayName("pureChecks fail when gossipCaCertificate not specified")
    void gossipCaCertificateNeedSet() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1))
                .withServiceEndpoint(List.of(endpoint2))
                .withGrpcWebProxyEndpoint(endpoint3)
                .build(payerId);
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_GOSSIP_CA_CERTIFICATE);
    }

    @Test
    @DisplayName("pureChecks fail when adminKey not specified")
    void adminKeyNeedSet() throws CertificateEncodingException {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1))
                .withServiceEndpoint(List.of(endpoint2))
                .withGrpcWebProxyEndpoint(endpoint3)
                .withGossipCaCertificate(Bytes.wrap(certList.get(1).getEncoded()))
                .build(payerId);
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(KEY_REQUIRED);
    }

    @Test
    @DisplayName("pureChecks fail when adminKey empty")
    void adminKeyEmpty() throws CertificateEncodingException {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1))
                .withServiceEndpoint(List.of(endpoint2))
                .withGrpcWebProxyEndpoint(endpoint3)
                .withGossipCaCertificate(Bytes.wrap(certList.get(0).getEncoded()))
                .withAdminKey(IMMUTABILITY_SENTINEL_KEY)
                .build(payerId);
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(KEY_REQUIRED);
    }

    @Test
    @DisplayName("pureChecks fail when adminKey not valid")
    void adminKeyNeedValid() throws CertificateEncodingException {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1))
                .withServiceEndpoint(List.of(endpoint2))
                .withGrpcWebProxyEndpoint(endpoint3)
                .withGossipCaCertificate(Bytes.wrap(certList.get(0).getEncoded()))
                .withAdminKey(invalidKey)
                .build(payerId);
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_ADMIN_KEY);
    }

    @Test
    @DisplayName("pureChecks succeeds when expected attributes are specified")
    void pureCheckPass() throws CertificateEncodingException {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1))
                .withServiceEndpoint(List.of(endpoint2))
                .withGrpcWebProxyEndpoint(endpoint3)
                .withGossipCaCertificate(Bytes.wrap(certList.get(2).getEncoded()))
                .withAdminKey(key)
                .build(payerId);
        given(pureChecksContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void failsWhenMaxNodesExceeds() {
        txn = new NodeCreateBuilder().withAccountId(accountId).build(payerId);
        given(handleContext.body()).willReturn(txn);
        refreshStoresWithCurrentNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.maxNumber", 1L)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.MAX_NODES_CREATED, msg.getStatus());
        assertEquals(0, writableStore.modifiedNodes().size());
    }

    @Test
    void accountIdMustInState() {
        txn = new NodeCreateBuilder().withAccountId(accountId).build(payerId);
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
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withDescription("Description")
                .build(payerId);
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
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withDescription("Des\0cription")
                .build(payerId);
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
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2, endpoint3))
                .build(payerId);
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.GOSSIP_ENDPOINTS_EXCEEDED_LIMIT, msg.getStatus());
    }

    @Test
    void failsWhenGossipEndpointNull() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(null)
                .build(payerId);
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_GOSSIP_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenGossipEndpointEmpty() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of())
                .build(payerId);
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_GOSSIP_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenGossipEndpointHaveIPAndFQDN() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint4))
                .build(payerId);
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN, msg.getStatus());
    }

    @Test
    void failsWhenEndpointHaveEmptyIPAndFQDN() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint5))
                .build(payerId);
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenEndpointHaveNullIp() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint7))
                .build(payerId);
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenEndpointHaveZeroIp() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint6))
                .build(payerId);
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenEndpointHaveInvalidIp() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint8))
                .build(payerId);
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_IPV4_ADDRESS, msg.getStatus());
    }

    @Test
    void failsWhenEndpointHaveInvalidIp2() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint9))
                .build(payerId);
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_IPV4_ADDRESS, msg.getStatus());
    }

    @Test
    void failsWhenEndpointHaveInvalidIp3() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint10))
                .build(payerId);
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_IPV4_ADDRESS, msg.getStatus());
    }

    @Test
    void failsWhenServiceEndpointTooLarge() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint1, endpoint2, endpoint3))
                .build(payerId);
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
    void failsWhenServiceEndpointNull() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(null)
                .build(payerId);
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_SERVICE_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenServiceEndpointEmpty() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of())
                .build(payerId);
        setupHandle();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_SERVICE_ENDPOINT, msg.getStatus());
    }

    @Test
    void failsWhenEndpointHaveIPAndFQDN() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint1, endpoint4))
                .build(payerId);
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
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint1, endpoint3))
                .build(payerId);
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
    void handleFailsWhenInvalidAdminKey() {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint1, endpoint3))
                .withGrpcWebProxyEndpoint(endpoint3)
                .withAdminKey(invalidKey)
                .build(payerId);
        setupHandle();

        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.maxGossipEndpoint", 2)
                .withValue("nodes.maxServiceEndpoint", 2)
                .withValue("nodes.maxFqdnSize", 100)
                .withValue("nodes.webProxyEndpointsEnabled", true)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.attributeValidator()).willReturn(validator);
        doThrow(new HandleException(INVALID_ADMIN_KEY)).when(validator).validateKey(invalidKey, INVALID_ADMIN_KEY);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_ADMIN_KEY, msg.getStatus());
    }

    @Test
    void handleWorksAsExpected() throws CertificateEncodingException {
        txn = new NodeCreateBuilder()
                .withAccountId(accountId)
                .withDescription("Description")
                .withGossipEndpoint(List.of(endpoint1, endpoint2))
                .withServiceEndpoint(List.of(endpoint1, endpoint3))
                .withGrpcWebProxyEndpoint(endpoint3)
                .withGossipCaCertificate(Bytes.wrap(certList.get(0).getEncoded()))
                .withGrpcCertificateHash(Bytes.wrap("hash"))
                .withAdminKey(key)
                .withDeclineReward(true)
                .build(payerId);
        given(handleContext.body()).willReturn(txn);
        refreshStoresWithMoreNodeInWritable();
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.nodeMaxDescriptionUtf8Bytes", 12)
                .withValue("nodes.maxGossipEndpoint", 4)
                .withValue("nodes.maxServiceEndpoint", 3)
                .withValue("nodes.webProxyEndpointsEnabled", true)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);
        given(storeFactory.writableStore(WritableAccountNodeRelStore.class)).willReturn(writableAccountNodeRelStore);
        final var stack = mock(HandleContext.SavepointStack.class);
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(any())).willReturn(recordBuilder);
        given(accountStore.contains(accountId)).willReturn(true);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(handleContext.attributeValidator()).willReturn(validator);

        assertDoesNotThrow(() -> subject.handle(handleContext));
        final var createdNode = writableStore.get(0L);
        assertNotNull(createdNode);
        verify(recordBuilder).nodeID(0L);
        assertEquals(0L, createdNode.nodeId());
        assertEquals("Description", createdNode.description());
        assertArrayEquals(
                (List.of(endpoint1, endpoint2)).toArray(),
                createdNode.gossipEndpoint().toArray());
        assertArrayEquals(
                (List.of(endpoint1, endpoint3)).toArray(),
                createdNode.serviceEndpoint().toArray());
        assertArrayEquals(
                certList.get(0).getEncoded(), createdNode.gossipCaCertificate().toByteArray());
        assertArrayEquals("hash".getBytes(), createdNode.grpcCertificateHash().toByteArray());
        assertEquals(key, createdNode.adminKey());
        assertTrue(createdNode.declineReward());
    }

    @Test
    void preHandleWorksWhenAdminKeyValid() throws PreCheckException {
        mockAccountLookup(anotherKey, payerId, accountStore);
        txn = new NodeCreateBuilder().withAdminKey(key).build(payerId);
        final var context = new FakePreHandleContext(accountStore, txn);
        subject.preHandle(context);
        assertThat(txn).isEqualTo(context.body());
        assertThat(context.payerKey()).isEqualTo(anotherKey);
        assertThat(context.requiredNonPayerKeys()).contains(key);
    }

    @Test
    void preHandleFailedWhenAdminKeyInValid() throws PreCheckException {
        mockAccountLookup(anotherKey, payerId, accountStore);
        txn = new NodeCreateBuilder().withAdminKey(invalidKey).build(payerId);
        final var context = new FakePreHandleContext(accountStore, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_ADMIN_KEY);
        assertThat(context.payerKey()).isEqualTo(anotherKey);
        assertThat(context.requiredNonPayerKeys()).isEmpty();
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

    private class NodeCreateBuilder {
        private AccountID accountId = null;
        private String description = null;
        private List<ServiceEndpoint> gossipEndpoint = null;

        private List<ServiceEndpoint> serviceEndpoint = null;

        private ServiceEndpoint grpcWebProxyEndpoint = null;

        private Bytes gossipCaCertificate = null;

        private Bytes grpcCertificateHash = null;

        private Key adminKey = null;
        private boolean declineReward = false;

        private NodeCreateBuilder() {}

        public TransactionBody build(AccountID payerId) {
            final var txnId = TransactionID.newBuilder().accountID(payerId).transactionValidStart(consensusTimestamp);
            final var txnBody = NodeCreateTransactionBody.newBuilder();
            if (accountId != null) {
                txnBody.accountId(accountId);
            }
            if (description != null) {
                txnBody.description(description);
            }
            if (gossipEndpoint != null) {
                txnBody.gossipEndpoint(gossipEndpoint);
            }
            if (serviceEndpoint != null) {
                txnBody.serviceEndpoint(serviceEndpoint);
            }
            if (grpcWebProxyEndpoint != null) {
                txnBody.grpcProxyEndpoint(grpcWebProxyEndpoint);
            }
            if (gossipCaCertificate != null) {
                txnBody.gossipCaCertificate(gossipCaCertificate);
            }
            if (grpcCertificateHash != null) {
                txnBody.grpcCertificateHash(grpcCertificateHash);
            }
            if (adminKey != null) {
                txnBody.adminKey(adminKey);
            }
            txnBody.declineReward(declineReward);

            return TransactionBody.newBuilder()
                    .transactionID(txnId)
                    .nodeCreate(txnBody.build())
                    .build();
        }

        public NodeCreateBuilder withAccountId(final AccountID accountId) {
            this.accountId = accountId;
            return this;
        }

        public NodeCreateBuilder withDescription(final String description) {
            this.description = description;
            return this;
        }

        public NodeCreateBuilder withGossipEndpoint(final List<ServiceEndpoint> gossipEndpoint) {
            this.gossipEndpoint = gossipEndpoint;
            return this;
        }

        public NodeCreateBuilder withServiceEndpoint(final List<ServiceEndpoint> serviceEndpoint) {
            this.serviceEndpoint = serviceEndpoint;
            return this;
        }

        public NodeCreateBuilder withGrpcWebProxyEndpoint(final ServiceEndpoint proxyEndpoint) {
            this.grpcWebProxyEndpoint = proxyEndpoint;
            return this;
        }

        public NodeCreateBuilder withGossipCaCertificate(final Bytes gossipCaCertificate) {
            this.gossipCaCertificate = gossipCaCertificate;
            return this;
        }

        public NodeCreateBuilder withGrpcCertificateHash(final Bytes grpcCertificateHash) {
            this.grpcCertificateHash = grpcCertificateHash;
            return this;
        }

        public NodeCreateBuilder withAdminKey(final Key adminKey) {
            this.adminKey = adminKey;
            return this;
        }

        public NodeCreateBuilder withDeclineReward(final boolean declineReward) {
            this.declineReward = declineReward;
            return this;
        }
    }
}
