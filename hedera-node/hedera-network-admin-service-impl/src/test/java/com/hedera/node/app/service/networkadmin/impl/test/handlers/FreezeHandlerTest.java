// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FREEZE_START_TIME_MUST_BE_FUTURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FREEZE_UPDATE_FILE_DOES_NOT_EXIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FREEZE_UPDATE_FILE_HASH_DOES_NOT_MATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FREEZE_TRANSACTION_BODY;
import static com.hedera.hapi.node.freeze.FreezeType.FREEZE_ABORT;
import static com.hedera.hapi.node.freeze.FreezeType.FREEZE_ONLY;
import static com.hedera.hapi.node.freeze.FreezeType.FREEZE_UPGRADE;
import static com.hedera.hapi.node.freeze.FreezeType.PREPARE_UPGRADE;
import static com.hedera.hapi.node.freeze.FreezeType.TELEMETRY_UPGRADE;
import static com.hedera.hapi.node.freeze.FreezeType.UNKNOWN_FREEZE_TYPE;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.freeze.FreezeTransactionBody;
import com.hedera.hapi.node.freeze.FreezeType;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.file.ReadableUpgradeFileStore;
import com.hedera.node.app.service.networkadmin.impl.WritableFreezeStore;
import com.hedera.node.app.service.networkadmin.impl.handlers.FreezeHandler;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.EntityIdFactory;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FreezeHandlerTest {
    @Mock(strictness = LENIENT)
    ReadableUpgradeFileStore upgradeFileStore;

    @Mock(strictness = LENIENT)
    private WritableFreezeStore freezeStore;

    @Mock(strictness = LENIENT)
    private ReadableAccountStore accountStore;

    @Mock(strictness = LENIENT)
    private PreHandleContext preHandleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = LENIENT)
    private StoreFactory storeFactory;

    @Mock(strictness = LENIENT)
    private Account account;

    @Mock(strictness = LENIENT)
    private ReadableNodeStore nodeStore;

    @Mock
    private ReadableStakingInfoStore stakingInfoStore;

    @Mock
    private EntityIdFactory entityIdFactory;

    private final FileID fileUpgradeFileId = FileID.newBuilder().fileNum(150L).build();
    private final FileID anotherFileUpgradeFileId =
            FileID.newBuilder().fileNum(157).build();
    private final FileID invalidFileUpgradeFileId =
            FileID.newBuilder().fileNum(140).build();

    private final Key key = Key.newBuilder()
            .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes()))
            .build();
    private final AccountID nonAdminAccount =
            AccountID.newBuilder().accountNum(9999L).build();
    private FreezeHandler subject;

    @BeforeEach
    void setUp() {
        subject = new FreezeHandler(
                new ForkJoinPool(
                        1,
                        ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                        Thread.getDefaultUncaughtExceptionHandler(),
                        true),
                entityIdFactory);

        Configuration config = HederaTestConfigBuilder.createConfig();
        given(preHandleContext.configuration()).willReturn(config);

        given(accountStore.getAccountById(nonAdminAccount)).willReturn(account);
        given(account.key()).willReturn(key);

        given(preHandleContext.createStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(preHandleContext.createStore(ReadableUpgradeFileStore.class)).willReturn(upgradeFileStore);
        given(preHandleContext.createStore(ReadableNodeStore.class)).willReturn(nodeStore);
        given(preHandleContext.createStore(ReadableStakingInfoStore.class)).willReturn(stakingInfoStore);

        given(handleContext.configuration()).willReturn(config);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableUpgradeFileStore.class)).willReturn(upgradeFileStore);
        given(storeFactory.writableStore(WritableFreezeStore.class)).willReturn(freezeStore);
        given(storeFactory.readableStore(ReadableNodeStore.class)).willReturn(nodeStore);
        given(storeFactory.readableStore(ReadableStakingInfoStore.class)).willReturn(stakingInfoStore);
    }

    @Test
    void failsUnknownFreezeType() {
        TransactionBody txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(nonAdminAccount)
                        .transactionValidStart(
                                Timestamp.newBuilder().seconds(1000).build()))
                .freeze(FreezeTransactionBody.newBuilder()
                        .freezeType(UNKNOWN_FREEZE_TYPE)
                        .build())
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_FREEZE_TRANSACTION_BODY);

        given(handleContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_FREEZE_TRANSACTION_BODY));
    }

    @Test
    void rejectIfStartTimeNotSetForCertainFreezeTypes() {
        // when using these freeze types, it is required to set start time
        FreezeType[] freezeTypes = {FREEZE_ONLY, FREEZE_UPGRADE, TELEMETRY_UPGRADE};
        for (FreezeType freezeType : freezeTypes) {
            TransactionBody txn = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .accountID(nonAdminAccount)
                            .transactionValidStart(
                                    Timestamp.newBuilder().seconds(1000).build()))
                    .freeze(FreezeTransactionBody.newBuilder()
                            .freezeType(freezeType)
                            .build())
                    .build();
            given(pureChecksContext.body()).willReturn(txn);
            assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_FREEZE_TRANSACTION_BODY);
        }
    }

    @Test
    void rejectIfStartTimeInPastForCertainFreezeTypes() {
        // when using these freeze types, it is required to set start time to a time after the effective consensus time
        FreezeType[] freezeTypes = {FREEZE_ONLY, FREEZE_UPGRADE, TELEMETRY_UPGRADE};

        for (FreezeType freezeType : freezeTypes) {
            TransactionID txnId = TransactionID.newBuilder()
                    .accountID(nonAdminAccount)
                    .transactionValidStart(Timestamp.newBuilder().seconds(2000).build())
                    .build();
            TransactionBody txn = TransactionBody.newBuilder()
                    .transactionID(txnId)
                    .freeze(FreezeTransactionBody.newBuilder()
                            .freezeType(freezeType)
                            .startTime(Timestamp.newBuilder().seconds(1000).build()))
                    .build();
            given(pureChecksContext.body()).willReturn(txn);
            assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), FREEZE_START_TIME_MUST_BE_FUTURE);
        }
    }

    @Test
    void rejectIfUpdateFileIdNotSetForCertainFreezeTypes() {
        // when using these freeze types, it is required to set an update file
        FreezeType[] freezeTypes = {PREPARE_UPGRADE, FREEZE_UPGRADE, TELEMETRY_UPGRADE};
        for (FreezeType freezeType : freezeTypes) {
            TransactionID txnId = TransactionID.newBuilder()
                    .accountID(nonAdminAccount)
                    .transactionValidStart(Timestamp.newBuilder().seconds(1000).build())
                    .build();
            TransactionBody txn = TransactionBody.newBuilder()
                    .transactionID(txnId)
                    .freeze(FreezeTransactionBody.newBuilder()
                            .freezeType(freezeType)
                            // do not set update file Id
                            .startTime(Timestamp.newBuilder().seconds(2000).build()))
                    .build();
            given(preHandleContext.body()).willReturn(txn);
            assertThrowsPreCheck(() -> subject.preHandle(preHandleContext), INVALID_FREEZE_TRANSACTION_BODY);
        }
    }

    @Test
    void rejectIfUpdateFileIdSetIncorrectlyForCertainFreezeTypes() {
        // when using these freeze types, it is required to set an update file
        FreezeType[] freezeTypes = {PREPARE_UPGRADE, FREEZE_UPGRADE, TELEMETRY_UPGRADE};
        for (FreezeType freezeType : freezeTypes) {
            TransactionID txnId = TransactionID.newBuilder()
                    .accountID(nonAdminAccount)
                    .transactionValidStart(Timestamp.newBuilder().seconds(1000).build())
                    .build();
            TransactionBody txn = TransactionBody.newBuilder()
                    .transactionID(txnId)
                    .freeze(FreezeTransactionBody.newBuilder()
                            .freezeType(freezeType)
                            .updateFile(FileID.newBuilder().fileNum(200L)) // set to anything other than 150
                            .startTime(Timestamp.newBuilder().seconds(2000).build()))
                    .build();
            given(preHandleContext.body()).willReturn(txn);
            assertThrowsPreCheck(() -> subject.preHandle(preHandleContext), INVALID_FREEZE_TRANSACTION_BODY);
        }
    }

    @Test
    void rejectIfUpdateFileNotSetForCertainFreezeTypes() {
        // when using these freeze types, it is required to set an update file
        FreezeType[] freezeTypes = {PREPARE_UPGRADE, FREEZE_UPGRADE, TELEMETRY_UPGRADE};
        for (FreezeType freezeType : freezeTypes) {
            TransactionID txnId = TransactionID.newBuilder()
                    .accountID(nonAdminAccount)
                    .transactionValidStart(Timestamp.newBuilder().seconds(1000).build())
                    .build();
            TransactionBody txn = TransactionBody.newBuilder()
                    .transactionID(txnId)
                    .freeze(FreezeTransactionBody.newBuilder()
                            .freezeType(freezeType)
                            .updateFile(FileID.newBuilder().fileNum(150L))
                            .startTime(Timestamp.newBuilder().seconds(2000).build()))
                    .build();
            given(preHandleContext.body()).willReturn(txn);
            assertThrowsPreCheck(() -> subject.preHandle(preHandleContext), FREEZE_UPDATE_FILE_DOES_NOT_EXIST);
        }
    }

    @Test
    void rejectIfFileHashNotSetForCertainFreezeTypes() {
        // when using these freeze types, it is required to set an update file
        FreezeType[] freezeTypes = {PREPARE_UPGRADE, FREEZE_UPGRADE, TELEMETRY_UPGRADE};

        given(upgradeFileStore.peek(fileUpgradeFileId))
                .willReturn(File.newBuilder().build());

        for (FreezeType freezeType : freezeTypes) {
            TransactionID txnId = TransactionID.newBuilder()
                    .accountID(nonAdminAccount)
                    .transactionValidStart(Timestamp.newBuilder().seconds(1000).build())
                    .build();
            TransactionBody txn = TransactionBody.newBuilder()
                    .transactionID(txnId)
                    .freeze(FreezeTransactionBody.newBuilder()
                            .freezeType(freezeType)
                            .startTime(Timestamp.newBuilder().seconds(2000).build())
                            .updateFile(FileID.newBuilder().fileNum(150L))
                            .build())
                    .build();
            given(pureChecksContext.body()).willReturn(txn);
            assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), FREEZE_UPDATE_FILE_HASH_DOES_NOT_MATCH);
        }
    }

    @Test
    void rejectOnInvalidFile() throws IOException {
        // these freeze types require a valid update file to have been set via FileService
        FreezeType[] freezeTypes = {PREPARE_UPGRADE, TELEMETRY_UPGRADE};

        // set up the file store to return a fake upgrade file
        given(upgradeFileStore.peek(fileUpgradeFileId))
                .willReturn(File.newBuilder().build());
        given(upgradeFileStore.getFull(fileUpgradeFileId)).willThrow(IOException.class);

        for (FreezeType freezeType : freezeTypes) {
            TransactionID txnId = TransactionID.newBuilder()
                    .accountID(nonAdminAccount)
                    .transactionValidStart(Timestamp.newBuilder().seconds(1000).build())
                    .build();
            TransactionBody txn = TransactionBody.newBuilder()
                    .transactionID(txnId)
                    .freeze(FreezeTransactionBody.newBuilder()
                            .freezeType(freezeType)
                            .startTime(Timestamp.newBuilder().seconds(2000).build())
                            .updateFile(FileID.newBuilder().fileNum(150L))
                            .fileHash(Bytes.wrap(new byte[48]))
                            .build())
                    .build();
            given(preHandleContext.body()).willReturn(txn);
            assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

            given(handleContext.body()).willReturn(txn);
            assertThatThrownBy(() -> subject.handle(handleContext)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectInvalidFileId() {
        // these freeze types require a valid update file to have been set via FileService
        FreezeType[] freezeTypes = {PREPARE_UPGRADE, TELEMETRY_UPGRADE};

        for (FreezeType freezeType : freezeTypes) {
            TransactionID txnId = TransactionID.newBuilder()
                    .accountID(nonAdminAccount)
                    .transactionValidStart(Timestamp.newBuilder().seconds(1000).build())
                    .build();
            TransactionBody txn = TransactionBody.newBuilder()
                    .transactionID(txnId)
                    .freeze(FreezeTransactionBody.newBuilder()
                            .freezeType(freezeType)
                            .startTime(Timestamp.newBuilder().seconds(2000).build())
                            .updateFile(invalidFileUpgradeFileId)
                            .fileHash(Bytes.wrap(new byte[48]))
                            .build())
                    .build();
            given(preHandleContext.body()).willReturn(txn);
            assertThrowsPreCheck(() -> subject.preHandle(preHandleContext), INVALID_FREEZE_TRANSACTION_BODY);
        }
    }

    @Test
    void happyPathFreezeAbort() {
        // freeze_abort always returns OK, to allow the node to send multiple commands to abort
        TransactionBody txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(nonAdminAccount)
                        .transactionValidStart(
                                Timestamp.newBuilder().seconds(1000).build()))
                .freeze(FreezeTransactionBody.newBuilder()
                        .freezeType(FREEZE_ABORT)
                        .build())
                .build();
        given(preHandleContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        given(handleContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.handle(handleContext));

        assertThat(freezeStore.updateFileHash()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void happyPathFreezeUpgradeOrTelemetryUpgrade() throws IOException {
        // when using these freeze types, it is required to set an update file and file hash and they must match
        // also must set start time to a time after the effective consensus time
        FreezeType[] freezeTypes = {FREEZE_UPGRADE, TELEMETRY_UPGRADE};

        // set up the file store to return a fake upgrade file
        given(upgradeFileStore.peek(fileUpgradeFileId))
                .willReturn(File.newBuilder().build());
        given(upgradeFileStore.getFull(fileUpgradeFileId)).willReturn(Bytes.wrap("Upgrade file bytes"));
        given(nodeStore.keys()).willReturn(mock(List.class));

        for (FreezeType freezeType : freezeTypes) {
            TransactionID txnId = TransactionID.newBuilder()
                    .accountID(nonAdminAccount)
                    .transactionValidStart(Timestamp.newBuilder().seconds(1000).build())
                    .build();
            TransactionBody txn = TransactionBody.newBuilder()
                    .transactionID(txnId)
                    .freeze(FreezeTransactionBody.newBuilder()
                            .freezeType(freezeType)
                            .startTime(Timestamp.newBuilder().seconds(2000).build())
                            .updateFile(FileID.newBuilder().fileNum(150L))
                            .fileHash(Bytes.wrap(new byte[48]))
                            .build())
                    .build();
            given(preHandleContext.body()).willReturn(txn);
            assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

            given(handleContext.body()).willReturn(txn);
            assertDoesNotThrow(() -> subject.handle(handleContext));
        }
    }

    @Test
    void happyPathPrepareUpgradeFile150() throws IOException {
        // when using these freeze types, it is required to set an update file and file hash and they must match
        // start time not required
        // set up the file store to return a fake upgrade file
        given(upgradeFileStore.peek(fileUpgradeFileId))
                .willReturn(File.newBuilder().build());
        given(upgradeFileStore.getFull(fileUpgradeFileId)).willReturn(Bytes.wrap("Upgrade file bytes"));
        given(nodeStore.keys()).willReturn(List.of(new EntityNumber(0)));
        given(nodeStore.sizeOfState()).willReturn(1L);

        TransactionID txnId = TransactionID.newBuilder()
                .accountID(nonAdminAccount)
                .transactionValidStart(Timestamp.newBuilder().seconds(1000).build())
                .build();
        TransactionBody txn = TransactionBody.newBuilder()
                .transactionID(txnId)
                .freeze(FreezeTransactionBody.newBuilder()
                        .freezeType(PREPARE_UPGRADE)
                        .updateFile(FileID.newBuilder().fileNum(150L))
                        .fileHash(Bytes.wrap(new byte[48]))
                        .build())
                .build();
        given(preHandleContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        given(handleContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.handle(handleContext));
    }

    @Test
    void happyPathPrepareUpgradeFile157() throws IOException {
        // when using these freeze types, it is required to set an update file and file hash and they must match
        // start time not required
        // set up the file store to return a fake upgrade file
        given(upgradeFileStore.peek(anotherFileUpgradeFileId))
                .willReturn(File.newBuilder().build());
        given(upgradeFileStore.getFull(anotherFileUpgradeFileId)).willReturn(Bytes.wrap("Upgrade file bytes"));
        given(nodeStore.keys()).willReturn(List.of(new EntityNumber(0)));
        given(nodeStore.sizeOfState()).willReturn(1L);

        TransactionID txnId = TransactionID.newBuilder()
                .accountID(nonAdminAccount)
                .transactionValidStart(Timestamp.newBuilder().seconds(1000).build())
                .build();
        TransactionBody txn = TransactionBody.newBuilder()
                .transactionID(txnId)
                .freeze(FreezeTransactionBody.newBuilder()
                        .freezeType(PREPARE_UPGRADE)
                        .updateFile(FileID.newBuilder().fileNum(157L))
                        .fileHash(Bytes.wrap(new byte[48]))
                        .build())
                .build();
        given(preHandleContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        given(handleContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.handle(handleContext));
    }

    @Test
    void happyPathFreezeOnly() {
        // for FREEZE_ONLY, start time is required and it must be in the future

        TransactionID txnId = TransactionID.newBuilder()
                .accountID(nonAdminAccount)
                .transactionValidStart(Timestamp.newBuilder().seconds(1000).build())
                .build();
        TransactionBody txn = TransactionBody.newBuilder()
                .transactionID(txnId)
                .freeze(FreezeTransactionBody.newBuilder()
                        .freezeType(FREEZE_ONLY)
                        .startTime(Timestamp.newBuilder().seconds(2000).build())
                        .build())
                .build();
        given(preHandleContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        given(handleContext.body()).willReturn(txn);
        assertDoesNotThrow(() -> subject.handle(handleContext));
    }

    @Test
    void rejectIfNoStartTimeSet() {
        TransactionBody txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(nonAdminAccount)
                        .transactionValidStart(
                                Timestamp.newBuilder().seconds(1000).build()))
                .freeze(FreezeTransactionBody.newBuilder().build())
                // do not set freeze start time
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_FREEZE_TRANSACTION_BODY);
    }

    @Test
    void rejectIfStartHourSet() {
        // start hour is not supported
        TransactionBody txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(nonAdminAccount)
                        .transactionValidStart(
                                Timestamp.newBuilder().seconds(1000).build()))
                .freeze(FreezeTransactionBody.newBuilder().startHour(3).build())
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_FREEZE_TRANSACTION_BODY);
    }

    @Test
    void rejectIfStartMinSet() {
        // start min is not supported
        TransactionBody txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(nonAdminAccount)
                        .transactionValidStart(
                                Timestamp.newBuilder().seconds(1000).build()))
                .freeze(FreezeTransactionBody.newBuilder().startMin(31).build())
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_FREEZE_TRANSACTION_BODY);
    }

    @Test
    void rejectIfEndHourSet() {
        // end hour is not supported
        TransactionBody txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(nonAdminAccount)
                        .transactionValidStart(
                                Timestamp.newBuilder().seconds(1000).build()))
                .freeze(FreezeTransactionBody.newBuilder().endHour(3).build())
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_FREEZE_TRANSACTION_BODY);
    }

    @Test
    void rejectIfEndMinSet() {
        // end min is not supported
        TransactionBody txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(nonAdminAccount)
                        .transactionValidStart(
                                Timestamp.newBuilder().seconds(1000).build()))
                .freeze(FreezeTransactionBody.newBuilder().endMin(16).build())
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_FREEZE_TRANSACTION_BODY);
    }
}
