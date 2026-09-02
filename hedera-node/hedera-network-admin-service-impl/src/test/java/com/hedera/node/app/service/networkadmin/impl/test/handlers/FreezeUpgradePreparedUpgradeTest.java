// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NO_UPGRADE_HAS_BEEN_PREPARED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UPDATE_FILE_HASH_DOES_NOT_MATCH_PREPARED;
import static com.hedera.hapi.node.freeze.FreezeType.FREEZE_UPGRADE;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.freeze.FreezeTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.file.ReadableUpgradeFileStore;
import com.hedera.node.app.service.networkadmin.impl.WritableFreezeStore;
import com.hedera.node.app.service.networkadmin.impl.handlers.FreezeHandler;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.io.IOException;
import java.util.concurrent.ForkJoinPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers how {@link FreezeHandler} treats the prepared upgrade hash held in the freeze store when a
 * {@code FREEZE_UPGRADE} is handled. That hash is written by {@code PREPARE_UPGRADE} and cleared by
 * {@code FREEZE_ABORT}, so it records whether an upgrade was prepared and which file it was prepared for.
 *
 * <p>A {@code FREEZE_UPGRADE} is expected to be honored only when it confirms a prepared upgrade: the
 * network-admin service design requires it to be rejected with {@code NO_UPGRADE_HAS_BEEN_PREPARED} when
 * no upgrade was prepared, and with {@code UPDATE_FILE_HASH_DOES_NOT_MATCH_PREPARED} when the prepared
 * hash does not match the hash named by the transaction.
 */
@ExtendWith(MockitoExtension.class)
class FreezeUpgradePreparedUpgradeTest {
    private static final Bytes UPGRADE_FILE_CONTENTS = Bytes.wrap("Upgrade file bytes".getBytes());
    private static final Bytes UPGRADE_FILE_HASH = Bytes.wrap(noThrowSha384HashOf(UPGRADE_FILE_CONTENTS.toByteArray()));
    private static final Bytes UNRELATED_FILE_HASH = Bytes.wrap(noThrowSha384HashOf("a different file".getBytes()));

    @Mock(strictness = LENIENT)
    private ReadableUpgradeFileStore upgradeFileStore;

    @Mock(strictness = LENIENT)
    private WritableFreezeStore freezeStore;

    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = LENIENT)
    private StoreFactory storeFactory;

    @Mock
    private EntityIdFactory entityIdFactory;

    private final FileID fileUpgradeFileId = FileID.newBuilder().fileNum(150L).build();
    private final AccountID nonAdminAccount =
            AccountID.newBuilder().accountNum(9999L).build();

    private FreezeHandler subject;

    @BeforeEach
    void setUp() throws IOException {
        subject = new FreezeHandler(
                new ForkJoinPool(
                        1,
                        ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                        Thread.getDefaultUncaughtExceptionHandler(),
                        true),
                entityIdFactory);

        final Configuration config = HederaTestConfigBuilder.createConfig();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableUpgradeFileStore.class)).willReturn(upgradeFileStore);
        given(storeFactory.writableStore(WritableFreezeStore.class)).willReturn(freezeStore);

        given(upgradeFileStore.peek(fileUpgradeFileId))
                .willReturn(File.newBuilder().build());
        given(upgradeFileStore.getFull(fileUpgradeFileId)).willReturn(UPGRADE_FILE_CONTENTS);

        given(handleContext.body()).willReturn(freezeUpgradeTxn());
    }

    /** A well-formed FREEZE_UPGRADE naming update file 0.0.150 and that file's hash. */
    private TransactionBody freezeUpgradeTxn() {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(nonAdminAccount)
                        .transactionValidStart(
                                Timestamp.newBuilder().seconds(1000).build())
                        .build())
                .freeze(FreezeTransactionBody.newBuilder()
                        .freezeType(FREEZE_UPGRADE)
                        .startTime(Timestamp.newBuilder().seconds(2000).build())
                        .updateFile(fileUpgradeFileId)
                        .fileHash(UPGRADE_FILE_HASH)
                        .build())
                .build();
    }

    @Test
    void rejectsFreezeUpgradeWhenNoUpgradeHasBeenPrepared() {
        given(freezeStore.updateFileHash()).willReturn(null);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(NO_UPGRADE_HAS_BEEN_PREPARED));
    }

    @Test
    void rejectsFreezeUpgradeWhenPreparedHashDiffersFromTransactionHash() {
        given(freezeStore.updateFileHash()).willReturn(UNRELATED_FILE_HASH);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(UPDATE_FILE_HASH_DOES_NOT_MATCH_PREPARED));
    }

    @Test
    void acceptsFreezeUpgradeWhenPreparedHashMatchesTransactionHash() {
        given(freezeStore.updateFileHash()).willReturn(UPGRADE_FILE_HASH);

        assertDoesNotThrow(() -> subject.handle(handleContext));
    }
}
