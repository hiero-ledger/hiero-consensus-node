// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FILE_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.file.FileUpdateTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.usage.file.FileOpsUsage;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.impl.WritableFileStore;
import com.hedera.node.app.service.file.impl.WritableUpgradeFileStore;
import com.hedera.node.app.service.file.impl.handlers.FileSignatureWaiversImpl;
import com.hedera.node.app.service.file.impl.handlers.FileUpdateHandler;
import com.hedera.node.app.service.file.impl.test.FileTestBase;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.prehandle.PreHandleContextImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.info.NodeInfo;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.BDDMockito;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FileUpdateTest extends FileTestBase {

    private static final long EXPIRATION_TIMESTAMP = 3_456_789L;
    private final FileUpdateTransactionBody.Builder OP_BUILDER = FileUpdateTransactionBody.newBuilder();

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private HandleContext.SavepointStack stack;

    @Mock
    private StreamBuilder recordBuilder;

    @Mock
    private AttributeValidator attributeValidator;

    @Mock(strictness = LENIENT)
    private PreHandleContext preHandleContext;

    @Mock(strictness = Mock.Strictness.LENIENT)
    protected TransactionDispatcher mockDispatcher;

    @Mock(strictness = Mock.Strictness.LENIENT)
    protected ReadableStoreFactory mockStoreFactory;

    @Mock(strictness = Mock.Strictness.LENIENT)
    protected Account payerAccount;

    @Mock(strictness = Mock.Strictness.LENIENT)
    protected FileOpsUsage fileOpsUsage;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private FileSignatureWaiversImpl waivers;

    @Mock
    private TransactionChecker transactionChecker;

    @Mock
    private NodeInfo creatorInfo;

    protected Configuration testConfig;

    private FileUpdateHandler subject;

    @BeforeEach
    void setUp() {
        subject = new FileUpdateHandler(fileOpsUsage, waivers);
        testConfig = HederaTestConfigBuilder.createConfig();
        lenient().when(preHandleContext.configuration()).thenReturn(testConfig);
        lenient().when(handleContext.configuration()).thenReturn(testConfig);
        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(-1_234_567L));
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
                mockStoreFactory, txnWith(), testConfig, mockDispatcher, transactionChecker, creatorInfo);

        subject.preHandle(realPreContext);

        assertFalse(realPreContext.requiredNonPayerKeys().size() > 0);
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

        // No-op
        final var txnId = TransactionID.newBuilder().accountID(payerId).build();
        final var updateFileBuilder = FileUpdateTransactionBody.newBuilder().fileID(WELL_KNOWN_FILE_ID);
        final var txBody = TransactionBody.newBuilder()
                .transactionID(txnId)
                .fileUpdate(updateFileBuilder.build())
                .build();
        PreHandleContext realPreContext = new PreHandleContextImpl(
                mockStoreFactory, txBody, testConfig, mockDispatcher, transactionChecker, creatorInfo);

        subject.preHandle(realPreContext);

        assertEquals(0, realPreContext.requiredNonPayerKeys().size());
    }

    @Test
    void rejectsMissingFile() {
        final var op = OP_BUILDER.build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();
        when(handleContext.body()).thenReturn(txBody);

        // expect:
        assertFailsWith(INVALID_FILE_ID, () -> subject.handle(handleContext));
    }

    @Test
    void rejectsDeletedFile() {
        givenValidFile(true);
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var op = OP_BUILDER.fileID(wellKnownId()).build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();
        when(handleContext.body()).thenReturn(txBody);

        // expect:
        assertFailsWith(FILE_DELETED, () -> subject.handle(handleContext));
    }

    @Test
    void rejectsNonExpiryMutationOfImmutableFile() {
        givenValidFile(false, false);
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var op =
                OP_BUILDER.fileID(wellKnownId()).memo("Please mind the vase").build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();
        when(handleContext.body()).thenReturn(txBody);

        // expect:
        assertFailsWith(ResponseCodeEnum.UNAUTHORIZED, () -> subject.handle(handleContext));
    }

    @Test
    void appliesNewKeys() {
        givenValidFile(false);
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var op = OP_BUILDER.fileID(wellKnownId()).keys(anotherKeys).build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();
        when(handleContext.body()).thenReturn(txBody);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        given(handleContext.body())
                .willReturn(TransactionBody.newBuilder().fileUpdate(op).build());
        given(storeFactory.writableStore(WritableFileStore.class)).willReturn(writableStore);
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(1001L).build());

        subject.handle(handleContext);

        final var newFile = writableFileState.get(fileId);
        final var expectedKey = anotherKeys;
        assertEquals(expectedKey, newFile.keys());
    }

    @Test
    @DisplayName("Fails handle if keys doesn't exist on file to be updated")
    void failForImmutableFile() {
        file = new File(fileId, expirationTime, null, Bytes.wrap(contents), memo, false, 0L);
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var op = OP_BUILDER.fileID(fileId).keys(anotherKeys).build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();

        writableFileState = writableFileStateWithOneKey();
        given(writableStates.<FileID, File>get(FILES)).willReturn(writableFileState);
        final var configuration = HederaTestConfigBuilder.createConfig();
        writableStore = new WritableFileStore(writableStates, writableEntityCounters);
        given(storeFactory.writableStore(WritableFileStore.class)).willReturn(writableStore);

        given(handleContext.body()).willReturn(txBody);
        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));

        assertEquals(ResponseCodeEnum.UNAUTHORIZED, msg.getStatus());
    }

    @Test
    void validatesNewMemo() {
        givenValidFile(false);
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var op =
                OP_BUILDER.fileID(wellKnownId()).memo("Please mind the vase").build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();
        when(handleContext.body()).thenReturn(txBody);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);

        willThrow(new HandleException(ResponseCodeEnum.MEMO_TOO_LONG))
                .given(attributeValidator)
                .validateMemo(txBody.fileUpdate().memo());

        // expect:
        assertFailsWith(ResponseCodeEnum.MEMO_TOO_LONG, () -> subject.handle(handleContext));
    }

    @Test
    void validatesAutoRenewDurationIsInRange() {
        givenValidFile(false);
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var op = OP_BUILDER
                .fileID(wellKnownId())
                .expirationTime(Timestamp.newBuilder().seconds(-1_234_567_890L).build())
                .build();
        final var txBody = TransactionBody.newBuilder()
                .fileUpdate(op)
                .transactionID(TransactionID.newBuilder()
                        .transactionValidStart(
                                Timestamp.newBuilder().seconds(100L).build())
                        .build())
                .build();
        when(handleContext.body()).thenReturn(txBody);
        when(handleContext.savepointStack()).thenReturn(stack);
        when(stack.getBaseBuilder(StreamBuilder.class)).thenReturn(recordBuilder);
        when(recordBuilder.category()).thenReturn(HandleContext.TransactionCategory.USER);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);

        // expect:
        assertFailsWith(ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE, () -> subject.handle(handleContext));
    }

    @Test
    void appliesNewMemo() {
        final var newMemo = "Please mind the vase";
        givenValidFile(false);
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var op = OP_BUILDER.fileID(wellKnownId()).memo(newMemo).build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();
        when(handleContext.body()).thenReturn(txBody);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(1001L).build());

        subject.handle(handleContext);

        final var newFile = writableFileState.get(fileId);
        assertEquals(newMemo, newFile.memo());
    }

    @Test
    void validatesNewContent() {
        givenValidFile(false);
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var op = OP_BUILDER
                .fileID(wellKnownId())
                .contents(Bytes.wrap(new byte[1048577]))
                .build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();
        when(handleContext.body()).thenReturn(txBody);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(1001L).build());

        // expect:
        assertFailsWith(ResponseCodeEnum.MAX_FILE_SIZE_EXCEEDED, () -> subject.handle(handleContext));
    }

    @Test
    void validatesNewContentEmptyRemainSameContentIfNotSuperuserPayer() {
        givenValidFile(false);
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var op = OP_BUILDER
                .fileID(wellKnownId())
                .contents(Bytes.wrap(new byte[0]))
                .build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(1001L).build());
        when(handleContext.body()).thenReturn(txBody);

        // expect:
        subject.handle(handleContext);

        final var updatedFile = writableFileState.get(fileId);
        assertEquals(file.contents(), updatedFile.contents());
    }

    @Test
    void validatesNewContentsAreEmptyIfSuperuserPayerAndOverrideFile() {
        givenValidFile(false);
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var op = OP_BUILDER
                .fileID(WELL_KNOWN_SYSTEM_FILE_ID)
                .contents(Bytes.wrap(new byte[0]))
                .build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(50L).build());
        when(handleContext.body()).thenReturn(txBody);

        // expect:
        subject.handle(handleContext);

        final var updatedFile = requireNonNull(writableFileState.get(WELL_KNOWN_SYSTEM_FILE_ID));
        assertEquals(0, updatedFile.contents().length());
    }

    @Test
    void appliesNewContent() {
        final var newContent = Bytes.wrap("STUFF".getBytes());
        givenValidFile(false);
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var op = OP_BUILDER.fileID(wellKnownId()).contents(newContent).build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();
        when(handleContext.body()).thenReturn(txBody);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(1001L).build());

        subject.handle(handleContext);

        final var newFile = writableFileState.get(fileId);
        assertEquals(newContent, newFile.contents());
    }

    @Test
    void appliesNewContentUpgradeFile() {
        final var newContent = Bytes.wrap("STUFF".getBytes());
        givenValidFile(false);
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var op =
                OP_BUILDER.fileID(wellKnowUpgradeId()).contents(newContent).build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();
        when(handleContext.body()).thenReturn(txBody);
        given(storeFactory.writableStore(WritableUpgradeFileStore.class)).willReturn(writableUpgradeFileStore);
        subject.handle(handleContext);

        final var newFile = writableUpgradeStates.poll();
        assertNotNull(newFile);
        assertNotNull(newFile.value());
        assertEquals(newContent.length(), newFile.value().length());
        assertEquals(newContent.toString(), newFile.value().toString());
    }

    @Test
    void appliesNewExpiryViaMeta() {
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var expiry = Timestamp.newBuilder().seconds(EXPIRATION_TIMESTAMP).build();
        final var op = OP_BUILDER.fileID(wellKnownId()).expirationTime(expiry).build();
        final var txBody = TransactionBody.newBuilder()
                .fileUpdate(op)
                .transactionID(TransactionID.newBuilder()
                        .transactionValidStart(
                                Timestamp.newBuilder().seconds(100L).build())
                        .build())
                .build();
        when(handleContext.body()).thenReturn(txBody);
        when(handleContext.savepointStack()).thenReturn(stack);
        when(stack.getBaseBuilder(StreamBuilder.class)).thenReturn(recordBuilder);
        when(recordBuilder.category()).thenReturn(HandleContext.TransactionCategory.USER);
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(1001L).build());

        subject.handle(handleContext);

        final var newFile = writableFileState.get(fileId);
        assertEquals(EXPIRATION_TIMESTAMP, newFile.expirationSecond());
    }

    @Test
    void appliesNewExpiryLowerExpirationTimeViaMeta() {
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        final var expiry = Timestamp.newBuilder().seconds(EXPIRATION_TIMESTAMP).build();
        final var op = OP_BUILDER.fileID(wellKnownId()).expirationTime(expiry).build();
        final var txBody = TransactionBody.newBuilder()
                .fileUpdate(op)
                .transactionID(TransactionID.newBuilder()
                        .transactionValidStart(
                                Timestamp.newBuilder().seconds(100L).build())
                        .build())
                .build();
        when(handleContext.body()).thenReturn(txBody);
        when(handleContext.savepointStack()).thenReturn(stack);
        when(stack.getBaseBuilder(StreamBuilder.class)).thenReturn(recordBuilder);
        when(recordBuilder.category()).thenReturn(HandleContext.TransactionCategory.USER);
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(1001L).build());

        subject.handle(handleContext);

        final var newFile = writableFileState.get(fileId);
        assertEquals(EXPIRATION_TIMESTAMP, newFile.expirationSecond());
    }

    @Test
    void nothingHappensIfUpdateIsNoop() {
        refreshStoresWithCurrentFileInBothReadableAndWritable();

        // No-op
        final var op = OP_BUILDER.fileID(wellKnownId()).build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();
        when(handleContext.body()).thenReturn(txBody);
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(1001L).build());

        subject.handle(handleContext);

        final var newFile = writableFileState.get(fileId);
        assertEquals(file, newFile);
    }

    @Test
    void handleThrowsIfKeysSignatureFailed() {
        final var op = OP_BUILDER.fileID(wellKnownId()).build();
        final var txBody = TransactionBody.newBuilder().fileUpdate(op).build();

        given(handleContext.body()).willReturn(txBody);

        assertThrows(HandleException.class, () -> subject.handle(handleContext));
    }

    public static void assertFailsWith(final ResponseCodeEnum status, final Runnable something) {
        final var ex = assertThrows(HandleException.class, something::run);
        assertEquals(status, ex.getStatus());
    }

    @Test
    void memoMutationsIsNonExpiry() {
        final var op = OP_BUILDER.memo("HI").build();
        assertTrue(FileUpdateHandler.wantsToMutateNonExpiryField(op));
    }

    @Test
    void keysMutationIsNonExpiry() {
        final var op = OP_BUILDER.keys(keys).build();
        assertTrue(FileUpdateHandler.wantsToMutateNonExpiryField(op));
    }

    @Test
    void expiryMutationIsExpiry() {
        final var expiryTime = Timestamp.newBuilder().seconds(123L).build();
        final var op = OP_BUILDER.expirationTime(expiryTime).build();
        assertFalse(FileUpdateHandler.wantsToMutateNonExpiryField(op));
    }

    private TransactionBody txnWith() {
        final var txnId = TransactionID.newBuilder().accountID(payerId).build();
        final var updateFileBuilder = FileUpdateTransactionBody.newBuilder().fileID(WELL_KNOWN_FILE_ID);
        return TransactionBody.newBuilder()
                .transactionID(txnId)
                .fileUpdate(updateFileBuilder.build())
                .build();
    }

    private FileID wellKnownId() {
        return fileId;
    }

    private FileID wellKnowUpgradeId() {
        return fileUpgradeFileId;
    }
}
