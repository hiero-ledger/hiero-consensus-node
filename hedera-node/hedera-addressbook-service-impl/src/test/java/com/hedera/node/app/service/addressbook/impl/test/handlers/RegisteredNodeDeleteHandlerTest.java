// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_REGISTERED_NODE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REGISTERED_NODE_STILL_ASSOCIATED;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
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
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

    @Mock
    private FeeContext feeContext;

    @Mock
    private FeeCalculatorFactory feeCalculatorFactory;

    @Mock
    private FeeCalculator feeCalculator;

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

    // ─── pureChecks ────────────────────────────────────────────────

    @Test
    @DisplayName("pureChecks fails for negative registeredNodeId")
    void pureChecksFailsForNegativeId() {
        final var txn = txnWithOp(opBuilder().registeredNodeId(-1).build());
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(INVALID_REGISTERED_NODE_ID, msg.responseCode());
    }

    @Test
    @DisplayName("pureChecks passes for valid registeredNodeId")
    void pureChecksPassesForValidId() {
        final var txn = txnWithOp(opBuilder().registeredNodeId(registeredNodeId).build());
        given(pureChecksContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    @DisplayName("pureChecks passes for zero registeredNodeId")
    void pureChecksPassesForZeroId() {
        final var txn = txnWithOp(opBuilder().registeredNodeId(0).build());
        given(pureChecksContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    // ─── preHandle ─────────────────────────────────────────────────

    @Test
    @DisplayName("preHandle bypasses admin key for treasury payer")
    void preHandleBypassesAdminKeyForTreasuryPayer() throws PreCheckException {
        // payerId in base is 0.0.2 (treasury by default config)
        mockAccountLookup(anotherKey, payerId, accountStore);
        final var txn = txnWithOp(opBuilder().registeredNodeId(registeredNodeId).build());
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        final var ctx = new FakePreHandleContext(accountStore, txn, config);
        ctx.registerStore(ReadableRegisteredNodeStore.class, readableRegisteredNodeStore);
        given(readableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);

        assertDoesNotThrow(() -> subject.preHandle(ctx));
        assertThat(ctx.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    @DisplayName("preHandle requires admin key for non-privileged payer")
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
        final var ctx = new FakePreHandleContext(accountStore, txn, config);
        ctx.registerStore(ReadableRegisteredNodeStore.class, readableRegisteredNodeStore);
        given(readableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);

        subject.preHandle(ctx);
        assertThat(ctx.requiredNonPayerKeys()).contains(key);
    }

    @Test
    @DisplayName("preHandle fails when registered node not found")
    void preHandleFailsWhenNodeNotFound() throws PreCheckException {
        final var nonPrivPayer = idFactory.newAccountId(3);
        mockAccountLookup(anotherKey, nonPrivPayer, accountStore);
        final var txn = TransactionBody.newBuilder()
                .transactionID(
                        TransactionID.newBuilder().accountID(nonPrivPayer).build())
                .registeredNodeDelete(
                        opBuilder().registeredNodeId(registeredNodeId).build())
                .build();
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        final var ctx = new FakePreHandleContext(accountStore, txn, config);
        ctx.registerStore(ReadableRegisteredNodeStore.class, readableRegisteredNodeStore);
        given(readableRegisteredNodeStore.get(registeredNodeId)).willReturn(null);

        final var msg = assertThrows(PreCheckException.class, () -> subject.preHandle(ctx));
        assertEquals(INVALID_REGISTERED_NODE_ID, msg.responseCode());
    }

    @Test
    @DisplayName("preHandle bypasses admin key for system admin payer")
    void preHandleBypassesAdminKeyForSystemAdmin() throws PreCheckException {
        final var systemAdminPayer = idFactory.newAccountId(50);
        mockAccountLookup(anotherKey, systemAdminPayer, accountStore);
        final var txn = TransactionBody.newBuilder()
                .transactionID(
                        TransactionID.newBuilder().accountID(systemAdminPayer).build())
                .registeredNodeDelete(
                        opBuilder().registeredNodeId(registeredNodeId).build())
                .build();
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        final var ctx = new FakePreHandleContext(accountStore, txn, config);
        ctx.registerStore(ReadableRegisteredNodeStore.class, readableRegisteredNodeStore);
        given(readableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);

        assertDoesNotThrow(() -> subject.preHandle(ctx));
        assertThat(ctx.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    @DisplayName("preHandle bypasses admin key for address book admin payer")
    void preHandleBypassesAdminKeyForAddressBookAdmin() throws PreCheckException {
        final var addressBookAdminPayer = idFactory.newAccountId(55);
        mockAccountLookup(anotherKey, addressBookAdminPayer, accountStore);
        final var txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(addressBookAdminPayer)
                        .build())
                .registeredNodeDelete(
                        opBuilder().registeredNodeId(registeredNodeId).build())
                .build();
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        final var ctx = new FakePreHandleContext(accountStore, txn, config);
        ctx.registerStore(ReadableRegisteredNodeStore.class, readableRegisteredNodeStore);
        given(readableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);

        assertDoesNotThrow(() -> subject.preHandle(ctx));
        assertThat(ctx.requiredNonPayerKeys()).isEmpty();
    }

    // ─── handle ────────────────────────────────────────────────────

    @Test
    @DisplayName("handle forbids deletion when referenced by consensus node")
    void handleForbidsDeletionWhenReferenced() {
        givenHandleContext();

        final var referencingNode = createNode()
                .copyBuilder()
                .associatedRegisteredNode(List.of(registeredNodeId))
                .build();
        given(readableNodeStore.keys())
                .willReturn(List.of(EntityNumber.newBuilder().number(1).build()));
        given(readableNodeStore.get(1)).willReturn(referencingNode);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(REGISTERED_NODE_STILL_ASSOCIATED, msg.getStatus());
    }

    @Test
    @DisplayName("handle deletes when unreferenced")
    void handleDeletesWhenUnreferenced() {
        givenHandleContext();
        given(readableNodeStore.keys()).willReturn(List.of());

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(writableRegisteredNodeStore).remove(registeredNodeId);
    }

    @Test
    @DisplayName("handle fails if registered node not found")
    void handleFailsIfNodeNotFound() {
        final var txn = txnWithOp(opBuilder().registeredNodeId(registeredNodeId).build());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableRegisteredNodeStore.class)).willReturn(writableRegisteredNodeStore);
        given(storeFactory.readableStore(ReadableNodeStore.class)).willReturn(readableNodeStore);
        given(writableRegisteredNodeStore.get(registeredNodeId)).willReturn(null);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INVALID_REGISTERED_NODE_ID, msg.getStatus());
    }

    @Test
    @DisplayName("handle deletes when multiple consensus nodes exist but none reference")
    void handleDeletesWhenMultipleConsensusNodesNoneReference() {
        givenHandleContext();

        final var node1 =
                createNode().copyBuilder().associatedRegisteredNode(List.of()).build();
        final var node2 = createNode()
                .copyBuilder()
                .associatedRegisteredNode(List.of(9999L))
                .build();
        given(readableNodeStore.keys())
                .willReturn(List.of(
                        EntityNumber.newBuilder().number(1).build(),
                        EntityNumber.newBuilder().number(2).build()));
        given(readableNodeStore.get(1)).willReturn(node1);
        given(readableNodeStore.get(2)).willReturn(node2);

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(writableRegisteredNodeStore).remove(registeredNodeId);
    }

    @Test
    @DisplayName("handle allows deletion when referencing consensus node is deleted")
    void handleAllowsDeletionWhenReferencingNodeIsDeleted() {
        givenHandleContext();

        final var deletedReferencingNode = createNode()
                .copyBuilder()
                .deleted(true)
                .associatedRegisteredNode(List.of(registeredNodeId))
                .build();
        given(readableNodeStore.keys())
                .willReturn(List.of(EntityNumber.newBuilder().number(1).build()));
        given(readableNodeStore.get(1)).willReturn(deletedReferencingNode);

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(writableRegisteredNodeStore).remove(registeredNodeId);
    }

    @Test
    @DisplayName("handle forbids deletion when referenced among multiple consensus nodes")
    void handleForbidsDeletionWhenReferencedAmongMultipleNodes() {
        givenHandleContext();

        final var node1 =
                createNode().copyBuilder().associatedRegisteredNode(List.of()).build();
        final var node2 = createNode()
                .copyBuilder()
                .associatedRegisteredNode(List.of(registeredNodeId))
                .build();
        given(readableNodeStore.keys())
                .willReturn(List.of(
                        EntityNumber.newBuilder().number(1).build(),
                        EntityNumber.newBuilder().number(2).build()));
        given(readableNodeStore.get(1)).willReturn(node1);
        given(readableNodeStore.get(2)).willReturn(node2);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(REGISTERED_NODE_STILL_ASSOCIATED, msg.getStatus());
    }

    // ─── calculateFees ─────────────────────────────────────────────

    @Test
    @DisplayName("calculateFees uses signature-based pricing with SubType.DEFAULT")
    void calculateFeesUsesSignatureBasedPricing() {
        final var expectedFees = new Fees(1L, 2L, 3L);
        given(feeContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(feeCalculatorFactory.feeCalculator(DEFAULT)).willReturn(feeCalculator);
        given(feeContext.numTxnSignatures()).willReturn(2);
        given(feeCalculator.calculate()).willReturn(expectedFees);

        final var result = subject.calculateFees(feeContext);

        assertEquals(expectedFees, result);
        verify(feeCalculator).resetUsage();
        verify(feeCalculator).addVerificationsPerTransaction(1);
    }

    @Test
    @DisplayName("calculateFees floors verification count at zero for single signature")
    void calculateFeesWithZeroSignatures() {
        given(feeContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(feeCalculatorFactory.feeCalculator(DEFAULT)).willReturn(feeCalculator);
        given(feeContext.numTxnSignatures()).willReturn(0);
        given(feeCalculator.calculate()).willReturn(Fees.FREE);

        subject.calculateFees(feeContext);

        verify(feeCalculator).addVerificationsPerTransaction(0);
    }

    @Test
    @DisplayName("calculateFees adds n-1 verifications for multiple signatures")
    void calculateFeesWithMultipleSignatures() {
        given(feeContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(feeCalculatorFactory.feeCalculator(DEFAULT)).willReturn(feeCalculator);
        given(feeContext.numTxnSignatures()).willReturn(5);
        given(feeCalculator.calculate()).willReturn(Fees.FREE);

        subject.calculateFees(feeContext);

        verify(feeCalculator).addVerificationsPerTransaction(4);
    }

    // ─── helpers ───────────────────────────────────────────────────

    private void givenHandleContext() {
        final var txn = txnWithOp(opBuilder().registeredNodeId(registeredNodeId).build());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableRegisteredNodeStore.class)).willReturn(writableRegisteredNodeStore);
        given(storeFactory.readableStore(ReadableNodeStore.class)).willReturn(readableNodeStore);
        given(writableRegisteredNodeStore.get(registeredNodeId)).willReturn(existing);
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
