// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.addressbook.NodeDeleteTransactionBody;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeDeleteHandler;
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
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeDeleteHandlerTest extends AddressBookTestBase {

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ReadableNodeStoreImpl mockStore;

    @Mock(strictness = Strictness.LENIENT)
    private HandleContext handleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private NodeDeleteHandler subject;

    protected Configuration testConfig;

    @BeforeEach
    void setUp() {
        mockStore = mock(ReadableNodeStoreImpl.class);
        subject = new NodeDeleteHandler();

        writableNodeState = writableNodeStateWithOneKey();
        given(writableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(writableNodeState);
        testConfig = HederaTestConfigBuilder.createConfig();
        writableStore = new WritableNodeStore(writableStates, writableEntityCounters);
        lenient().when(handleContext.configuration()).thenReturn(testConfig);
    }

    @Test
    @DisplayName("pureChecks throws exception when node id is negative or zero")
    void testPureChecksThrowsExceptionWhenFileIdIsNull() {
        NodeDeleteTransactionBody transactionBody = mock(NodeDeleteTransactionBody.class);
        TransactionBody transaction = mock(TransactionBody.class);
        given(pureChecksContext.body()).willReturn(transaction);
        given(transaction.nodeDeleteOrThrow()).willReturn(transactionBody);
        given(transactionBody.nodeId()).willReturn(-1L);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext)).isInstanceOf(PreCheckException.class);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_NODE_ID);
    }

    @Test
    @DisplayName("pureChecks does not throw exception when node id is not null")
    void testPureChecksDoesNotThrowExceptionWhenNodeIdIsNotNull() {
        given(pureChecksContext.body()).willReturn(newDeleteTxn());

        assertThatCode(() -> subject.pureChecks(pureChecksContext)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("check that fees are 1 for delete node trx")
    void testCalculateFeesInvocations() throws IOException {
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
    @DisplayName("Fails handle if node doesn't exist")
    void fileDoesntExist() {
        final var txn = newDeleteTxn().nodeDeleteOrThrow();

        given(handleContext.storeFactory()).willReturn(storeFactory);
        writableNodeState = emptyWritableNodeState();
        given(writableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(writableNodeState);
        writableStore = new WritableNodeStore(writableStates, writableEntityCounters);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);

        given(handleContext.body())
                .willReturn(TransactionBody.newBuilder().nodeDelete(txn).build());
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);

        HandleException thrown = (HandleException) catchThrowable(() -> subject.handle(handleContext));
        assertThat(thrown.getStatus()).isEqualTo(INVALID_NODE_ID);
    }

    @Test
    @DisplayName("Node is null")
    void NodeIsNull() {
        final var txn = newDeleteTxn().nodeDeleteOrThrow();

        node = null;

        given(handleContext.storeFactory()).willReturn(storeFactory);
        writableNodeState = writableNodeStateWithOneKey();
        given(writableStates.<EntityNumber, Node>get(NODES_STATE_ID)).willReturn(writableNodeState);
        writableStore = new WritableNodeStore(writableStates, writableEntityCounters);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);

        given(handleContext.body())
                .willReturn(TransactionBody.newBuilder().nodeDelete(txn).build());
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);
        HandleException thrown = (HandleException) catchThrowable(() -> subject.handle(handleContext));
        assertThat(thrown.getStatus()).isEqualTo(INVALID_NODE_ID);
    }

    @Test
    @DisplayName("Handle works as expected")
    void handleWorksAsExpected() {
        final var txn = newDeleteTxn().nodeDeleteOrThrow();

        final var existingNode = writableStore.get(WELL_KNOWN_NODE_ID);
        assertThat(existingNode).isNotNull();
        assertThat(existingNode.deleted()).isFalse();

        given(handleContext.body())
                .willReturn(TransactionBody.newBuilder().nodeDelete(txn).build());
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);

        subject.handle(handleContext);

        final var changedFile = writableStore.get(WELL_KNOWN_NODE_ID);

        assertThat(changedFile).isNotNull();
        assertThat(changedFile.deleted()).isTrue();
    }

    @Test
    @DisplayName("Node already deleted returns error")
    void noFileKeys() {
        givenValidNode(true);
        refreshStoresWithCurrentNodeInBothReadableAndWritable();

        final var txn = newDeleteTxn().nodeDeleteOrThrow();

        final var existingNode = writableStore.get(WELL_KNOWN_NODE_ID);
        assertThat(existingNode).isNotNull();
        assertThat(existingNode.deleted()).isTrue();

        given(handleContext.body())
                .willReturn(TransactionBody.newBuilder().nodeDelete(txn).build());
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableNodeStore.class)).willReturn(writableStore);
        // expect:
        assertFailsWith(() -> subject.handle(handleContext), ResponseCodeEnum.NODE_DELETED);
    }

    @Test
    void preHandleWorksWhenExistingAdminKeyValid() throws PreCheckException {
        givenValidNodeWithAdminKey(anotherKey);
        refreshStoresWithCurrentNodeInReadable();

        final var txn = newDeleteTxnWithNodeId(nodeId.number());
        final var context = setupPreHandlePayerKey(txn, accountId, key);
        subject.preHandle(context);
        assertThat(txn).isEqualTo(context.body());
        assertThat(context.payerKey()).isEqualTo(key);
        assertThat(context.requiredNonPayerKeys()).contains(anotherKey);
    }

    @Test
    void preHandleFailedWhenAdminKeyInValid() throws PreCheckException {
        givenValidNodeWithAdminKey(invalidKey);
        refreshStoresWithCurrentNodeInReadable();
        final var txn = newDeleteTxnWithNodeId(nodeId.number());
        final var context = setupPreHandlePayerKey(txn, accountId, anotherKey);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_ADMIN_KEY);
    }

    @Test
    void preHandleWorksWhenTreasureSign() throws PreCheckException {
        final var txn = newDeleteTxn();
        final var context = setupPreHandlePayerKey(txn, payerId, anotherKey);
        subject.preHandle(context);
        assertThat(txn).isEqualTo(context.body());
        assertThat(context.payerKey()).isEqualTo(anotherKey);
        assertThat(context.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    void preHandleWorksWhenSysAdminSign() throws PreCheckException {
        final var accountID = AccountID.newBuilder().accountNum(50).build();
        final var txn = newDeleteTxnWithPayerId(accountID);
        final var context = setupPreHandlePayerKey(txn, accountID, anotherKey);
        subject.preHandle(context);
        assertThat(txn).isEqualTo(context.body());
        assertThat(context.payerKey()).isEqualTo(anotherKey);
        assertThat(context.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    void preHandleWorksWhenAddressBookAdminSign() throws PreCheckException {
        final var accountID = AccountID.newBuilder().accountNum(55).build();
        final var txn = newDeleteTxnWithPayerId(accountID);
        final var context = setupPreHandlePayerKey(txn, accountID, anotherKey);
        subject.preHandle(context);
        assertThat(txn).isEqualTo(context.body());
        assertThat(context.payerKey()).isEqualTo(anotherKey);
        assertThat(context.requiredNonPayerKeys()).isEmpty();
    }

    private TransactionBody newDeleteTxn() {
        final var txnId = TransactionID.newBuilder().accountID(payerId).build();
        final var deleteFileBuilder = NodeDeleteTransactionBody.newBuilder().nodeId(WELL_KNOWN_NODE_ID);
        return TransactionBody.newBuilder()
                .transactionID(txnId)
                .nodeDelete(deleteFileBuilder.build())
                .build();
    }

    private TransactionBody newDeleteTxnWithPayerId(AccountID accountID) {
        final var txnId = TransactionID.newBuilder().accountID(accountID).build();
        final var deleteFileBuilder = NodeDeleteTransactionBody.newBuilder().nodeId(WELL_KNOWN_NODE_ID);
        return TransactionBody.newBuilder()
                .transactionID(txnId)
                .nodeDelete(deleteFileBuilder.build())
                .build();
    }

    private TransactionBody newDeleteTxnWithNodeId(long nodeId) {
        final var txnId = TransactionID.newBuilder().accountID(accountId).build();
        final var deleteFileBuilder = NodeDeleteTransactionBody.newBuilder().nodeId(nodeId);
        return TransactionBody.newBuilder()
                .transactionID(txnId)
                .nodeDelete(deleteFileBuilder.build())
                .build();
    }

    private PreHandleContext setupPreHandlePayerKey(TransactionBody txn, AccountID contextPayerId, Key key)
            throws PreCheckException {
        final var config = HederaTestConfigBuilder.create()
                .withValue("accounts.treasury", 2)
                .withValue("accounts.systemAdmin", 50)
                .withValue("accounts.addressBookAdmin", 55)
                .getOrCreateConfig();
        mockPayerLookup(key, contextPayerId, accountStore);
        final var context = new FakePreHandleContext(accountStore, txn, config);
        context.registerStore(ReadableNodeStore.class, readableStore);
        return context;
    }

    private static void assertFailsWith(final Runnable something, final ResponseCodeEnum status) {
        assertThatThrownBy(something::run)
                .isInstanceOf(HandleException.class)
                .extracting(ex -> ((HandleException) ex).getStatus())
                .isEqualTo(status);
    }
}
