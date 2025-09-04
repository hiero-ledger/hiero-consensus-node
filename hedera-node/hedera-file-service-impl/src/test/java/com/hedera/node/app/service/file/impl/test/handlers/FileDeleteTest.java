// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.file.FileDeleteTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.fee.FileFeeBuilder;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.impl.ReadableFileStoreImpl;
import com.hedera.node.app.service.file.impl.WritableFileStore;
import com.hedera.node.app.service.file.impl.handlers.FileDeleteHandler;
import com.hedera.node.app.service.file.impl.test.FileTestBase;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.prehandle.PreHandleContextImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.BDDMockito;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FileDeleteTest extends FileTestBase {

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ReadableFileStoreImpl mockStore;

    @Mock
    private FileDeleteHandler subject;

    @Mock(strictness = LENIENT)
    private PreHandleContext preHandleContext;

    @Mock(strictness = Mock.Strictness.LENIENT)
    protected TransactionDispatcher mockDispatcher;

    @Mock(strictness = Mock.Strictness.LENIENT)
    protected ReadableStoreFactory mockStoreFactory;

    @Mock(strictness = Mock.Strictness.LENIENT)
    protected Account payerAccount;

    @Mock
    protected FileFeeBuilder usageEstimator;

    @Mock
    private PureChecksContext context;

    @Mock
    private TransactionChecker transactionChecker;

    @Mock
    private NodeInfo creatorInfo;

    protected Configuration testConfig;

    @BeforeEach
    void setUp() {
        mockStore = mock(ReadableFileStoreImpl.class);
        subject = new FileDeleteHandler(usageEstimator);

        writableFileState = writableFileStateWithOneKey();
        given(writableStates.<FileID, File>get(FILES)).willReturn(writableFileState);
        testConfig = HederaTestConfigBuilder.createConfig();
        writableStore = new WritableFileStore(writableStates, writableEntityCounters);
        lenient().when(preHandleContext.configuration()).thenReturn(testConfig);
        lenient().when(handleContext.configuration()).thenReturn(testConfig);
        when(mockStoreFactory.getStore(ReadableFileStore.class)).thenReturn(mockStore);
        when(mockStoreFactory.getStore(ReadableAccountStore.class)).thenReturn(accountStore);
    }

    @Test
    @DisplayName("pureChecks throws exception when file id is null")
    void testPureChecksThrowsExceptionWhenFileIdIsNull() {
        FileDeleteTransactionBody transactionBody = mock(FileDeleteTransactionBody.class);
        TransactionBody transaction = mock(TransactionBody.class);
        given(transaction.fileDeleteOrThrow()).willReturn(transactionBody);
        given(transactionBody.fileID()).willReturn(null);
        given(context.body()).willReturn(transaction);

        assertThatThrownBy(() -> subject.pureChecks(context)).isInstanceOf(PreCheckException.class);
    }

    @Test
    @DisplayName("pureChecks does not throw exception when file id is not null")
    void testPureChecksDoesNotThrowExceptionWhenFileIdIsNotNull() {
        given(context.body()).willReturn(newDeleteTxn());

        assertThatCode(() -> subject.pureChecks(context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("calculateFees method invocations")
    void testCalculateFeesInvocations() {
        FeeContext feeContext = mock(FeeContext.class);
        FeeCalculatorFactory feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        FeeCalculator feeCalculator = mock(FeeCalculator.class);
        when(feeContext.feeCalculatorFactory()).thenReturn(feeCalculatorFactory);
        when(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).thenReturn(feeCalculator);

        subject.calculateFees(feeContext);

        InOrder inOrder = inOrder(feeContext, feeCalculatorFactory, feeCalculator);
        inOrder.verify(feeContext).body();
        inOrder.verify(feeCalculatorFactory).feeCalculator(SubType.DEFAULT);
        inOrder.verify(feeCalculator).legacyCalculate(any());
    }

    @Test
    @DisplayName("File not found returns error")
    void fileIdNotFound() throws PreCheckException {
        // given:
        mockPayerLookup();
        given(mockStore.getFileMetadata(notNull())).willReturn(null);
        final var context = new PreHandleContextImpl(
                mockStoreFactory, newDeleteTxn(), testConfig, mockDispatcher, transactionChecker, creatorInfo);

        // when:
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_FILE_ID);
    }

    @Test
    @DisplayName("Pre handle works as expected immutable")
    void preHandleWorksAsExpectedImmutable() throws PreCheckException {
        file = createFileEmptyMemoAndKeys();
        refreshStoresWithCurrentFileOnlyInReadable();
        BDDMockito.given(accountStore.getAccountById(payerId)).willReturn(payerAccount);
        BDDMockito.given(mockStoreFactory.getStore(ReadableFileStore.class)).willReturn(readableStore);
        BDDMockito.given(mockStoreFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        BDDMockito.given(payerAccount.key()).willReturn(A_COMPLEX_KEY);

        final var txnId = TransactionID.newBuilder().accountID(payerId).build();
        final var deleteFileBuilder = FileDeleteTransactionBody.newBuilder().fileID(WELL_KNOWN_FILE_ID);
        final TransactionBody transactionBody = TransactionBody.newBuilder()
                .transactionID(txnId)
                .fileDelete(deleteFileBuilder.build())
                .build();
        final PreHandleContext realPreContext = new PreHandleContextImpl(
                mockStoreFactory, transactionBody, testConfig, mockDispatcher, transactionChecker, creatorInfo);

        subject.preHandle(realPreContext);

        assertThat(realPreContext.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    @DisplayName("Pre handle works as expected")
    void preHandleWorksAsExpected() throws PreCheckException {
        refreshStoresWithCurrentFileOnlyInReadable();
        BDDMockito.given(accountStore.getAccountById(payerId)).willReturn(payerAccount);
        BDDMockito.given(mockStoreFactory.getStore(ReadableFileStore.class)).willReturn(readableStore);
        BDDMockito.given(mockStoreFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        BDDMockito.given(payerAccount.key()).willReturn(A_COMPLEX_KEY);

        PreHandleContext realPreContext = new PreHandleContextImpl(
                mockStoreFactory, newDeleteTxn(), testConfig, mockDispatcher, transactionChecker, creatorInfo);

        subject.preHandle(realPreContext);

        assertThat(realPreContext.requiredNonPayerKeys()).hasSize(1);
        assertThat(realPreContext
                        .requiredNonPayerKeys()
                        .toArray(Key[]::new)[0]
                        .thresholdKey()
                        .threshold())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Fails handle if file doesn't exist")
    void fileDoesntExist() {
        final var txn = newDeleteTxn().fileDeleteOrThrow();

        writableFileState = emptyWritableFileState();
        given(writableStates.<FileID, File>get(FILES)).willReturn(writableFileState);
        writableStore = new WritableFileStore(writableStates, writableEntityCounters);
        given(storeFactory.writableStore(WritableFileStore.class)).willReturn(writableStore);
        given(handleContext.body())
                .willReturn(TransactionBody.newBuilder().fileDelete(txn).build());

        HandleException thrown = (HandleException) catchThrowable(() -> subject.handle(handleContext));
        assertThat(thrown.getStatus()).isEqualTo(INVALID_FILE_ID);
    }

    @Test
    @DisplayName("Fails handle if keys doesn't exist on file to be deleted")
    void keysDoesntExist() {
        final var txn = newDeleteTxn().fileDeleteOrThrow();

        file = new File(fileId, expirationTime, null, Bytes.wrap(contents), memo, false, 0L);

        writableFileState = writableFileStateWithOneKey();
        given(writableStates.<FileID, File>get(FILES)).willReturn(writableFileState);
        writableStore = new WritableFileStore(writableStates, writableEntityCounters);
        given(storeFactory.writableStore(WritableFileStore.class)).willReturn(writableStore);

        given(handleContext.body())
                .willReturn(TransactionBody.newBuilder().fileDelete(txn).build());
        HandleException thrown = (HandleException) catchThrowable(() -> subject.handle(handleContext));
        assertThat(thrown.getStatus()).isEqualTo(ResponseCodeEnum.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Handle works as expected")
    void handleWorksAsExpected() {
        final var txn = newDeleteTxn().fileDeleteOrThrow();

        final var existingFile = writableStore.get(fileId);
        assertThat(existingFile).isPresent();
        assertThat(existingFile.get().deleted()).isFalse();

        given(handleContext.body())
                .willReturn(TransactionBody.newBuilder().fileDelete(txn).build());
        given(storeFactory.writableStore(WritableFileStore.class)).willReturn(writableStore);

        subject.handle(handleContext);

        final var changedFile = writableStore.get(fileId);

        assertThat(changedFile).isPresent();
        assertThat(changedFile.get().deleted()).isTrue();
        assertThat(changedFile.get().contents()).isEqualTo(Bytes.EMPTY);
    }

    @Test
    @DisplayName("File without keys returns error")
    void noFileKeys() {
        file = new File(fileId, expirationTime, null, Bytes.wrap(contents), memo, false, 0L);
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var txn = newDeleteTxn().fileDeleteOrThrow();

        final var existingFile = writableStore.get(fileId);
        assertThat(existingFile).isPresent();
        assertThat(existingFile.get().deleted()).isFalse();

        given(handleContext.body())
                .willReturn(TransactionBody.newBuilder().fileDelete(txn).build());
        given(storeFactory.writableStore(WritableFileStore.class)).willReturn(writableStore);
        // expect:
        assertFailsWith(ResponseCodeEnum.UNAUTHORIZED, () -> subject.handle(handleContext));
    }

    private Key mockPayerLookup() {
        return FileTestUtils.mockPayerLookup(A_COMPLEX_KEY, payerId, accountStore);
    }

    private TransactionBody newDeleteTxn() {
        final var txnId = TransactionID.newBuilder().accountID(payerId).build();
        final var deleteFileBuilder = FileDeleteTransactionBody.newBuilder().fileID(WELL_KNOWN_FILE_ID);
        return TransactionBody.newBuilder()
                .transactionID(txnId)
                .fileDelete(deleteFileBuilder.build())
                .build();
    }

    private static void assertFailsWith(final ResponseCodeEnum status, final Runnable something) {
        assertThatThrownBy(something::run)
                .isInstanceOf(HandleException.class)
                .extracting(ex -> ((HandleException) ex).getStatus())
                .isEqualTo(status);
    }
}
