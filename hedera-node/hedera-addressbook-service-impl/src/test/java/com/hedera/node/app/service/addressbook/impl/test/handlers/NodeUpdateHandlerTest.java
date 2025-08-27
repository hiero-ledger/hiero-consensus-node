// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GRPC_CERTIFICATE_HASH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UPDATE_NODE_ACCOUNT_NOT_ALLOWED;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
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
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.addressbook.NodeUpdateTransactionBody;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
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
                .withAccountId(asAccount(0L, 0L, 53))
                .withAdminKey(key)
                .build();
        final var context = setupPreHandle(true, txn);
        subject.preHandle(context);
        assertThat(txn).isEqualTo(context.body());
        assertThat(context.payerKey()).isEqualTo(anotherKey);
        assertThat(context.requiredNonPayerKeys()).contains(key);
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
                .withAccountId(asAccount(0L, 0L, 53))
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
        mockPayerLookup(anotherKey, contextPayerId, accountStore);
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

        private Bytes gossipCaCertificate = null;

        private Bytes grpcCertificateHash = null;
        private Key adminKey = null;
        private AccountID contextPayerId = payerId;
        private boolean declineReward = false;

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
            if (gossipCaCertificate != null) {
                op.gossipCaCertificate(gossipCaCertificate);
            }
            if (grpcCertificateHash != null) {
                op.grpcCertificateHash(grpcCertificateHash);
            }
            if (adminKey != null) {
                op.adminKey(adminKey);
            }
            op.declineReward(declineReward);

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
            this.declineReward = declineReward;
            return this;
        }
    }
}
