// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.handlers;

import static com.hedera.hapi.node.freeze.FreezeType.FREEZE_ONLY;
import static com.hedera.hapi.node.freeze.FreezeType.FREEZE_UPGRADE;
import static com.hedera.hapi.node.freeze.FreezeType.PREPARE_UPGRADE;
import static com.hedera.hapi.node.freeze.FreezeType.TELEMETRY_UPGRADE;
import static com.hedera.hapi.node.freeze.FreezeType.UNKNOWN_FREEZE_TYPE;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.freeze.FreezeTransactionBody;
import com.hedera.hapi.node.freeze.FreezeType;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.file.ReadableUpgradeFileStore;
import com.hedera.node.app.service.networkadmin.ReadableFreezeStore;
import com.hedera.node.app.service.networkadmin.impl.WritableFreezeStore;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.types.LongPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#FREEZE}.
 */
@Singleton
public class FreezeHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(FreezeHandler.class);

    // length of the hash of the update file included in the FreezeTransactionBody
    // used for a quick sanity check that the file hash is not invalid
    private static final int UPDATE_FILE_HASH_LEN = 48;

    private final Executor freezeExecutor;
    private final EntityIdFactory entityIdFactory;

    /**
     * Constructs a {@link FreezeHandler} with the provided {@link Executor}.
     *
     * @param freezeExecutor the {@link Executor} to use for handling freeze transactions
     */
    @Inject
    public FreezeHandler(
            @NonNull @Named("FreezeService") final Executor freezeExecutor,
            @NonNull final EntityIdFactory entityIdFactory) {
        this.freezeExecutor = requireNonNull(freezeExecutor);
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    /**
     * This method is called during the pre-handle workflow for Freeze transactions.
     *
     * @param context the {@link PreHandleContext} which collects all information that will be
     *     passed to {@code handle()}
     * @see <a href="https://hashgraph.github.io/hedera-protobufs/#freeze.proto">Protobuf freeze documentation</a>
     */
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);

        final FreezeTransactionBody freezeTxn = context.body().freezeOrThrow();
        final FreezeType freezeType = freezeTxn.freezeType();
        if (Arrays.asList(FREEZE_UPGRADE, TELEMETRY_UPGRADE, PREPARE_UPGRADE).contains(freezeType)) {
            // from proto specs, it looks like updateFileId not required for FREEZE_UPGRADE and TELEMETRY_UPGRADE
            // but specs aren't very clear previous code in FreezeTransitionLogic checks for it in all 3 cases,
            // so we will do the same
            final ReadableUpgradeFileStore upgradeStore = context.createStore(ReadableUpgradeFileStore.class);
            final var filesConfig = context.configuration().getConfigData(FilesConfig.class);
            verifyUpdateFile(freezeTxn, upgradeStore, filesConfig.softwareUpdateRange());
        }
        // no need to add any keys to the context because this transaction does not require any signatures
        // it must be submitted by an account with superuser privileges, that is checked during ingest
    }

    @SuppressWarnings("java:S1874") // disables the warnings for use of deprecated code
    // it is necessary to check startHour, startMin, endHour, endMin, all of which are deprecated
    // because if any are present then we set a status of INVALID_FREEZE_TRANSACTION_BODY
    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final TransactionBody txn = context.body();

        requireNonNull(txn);
        final FreezeTransactionBody freezeTxn = getFreezeTransactionBody(txn);

        final FreezeType freezeType = freezeTxn.freezeType();
        if (freezeType == UNKNOWN_FREEZE_TYPE) {
            throw new PreCheckException(ResponseCodeEnum.INVALID_FREEZE_TRANSACTION_BODY);
        }

        if (Arrays.asList(FREEZE_ONLY, FREEZE_UPGRADE, TELEMETRY_UPGRADE).contains(freezeType)) {
            final Timestamp txValidStart = txn.transactionIDOrThrow().transactionValidStartOrThrow();
            verifyFreezeStartTimeIsInFuture(freezeTxn, txValidStart);
        }

        if (freezeTxn.freezeType() == PREPARE_UPGRADE || freezeTxn.freezeType() == TELEMETRY_UPGRADE) {
            final FileID updateFileID = freezeTxn.updateFile();
            if (updateFileID == null) {
                throw new PreCheckException(ResponseCodeEnum.INVALID_FREEZE_TRANSACTION_BODY);
            }
        }

        if (Arrays.asList(FREEZE_UPGRADE, TELEMETRY_UPGRADE, PREPARE_UPGRADE).contains(freezeType)) {
            final Bytes fileHash = freezeTxn.fileHash();
            // don't verify the hash, just make sure it is not null or empty and is the correct length
            if (fileHash == null || Bytes.EMPTY.equals(fileHash) || fileHash.length() != UPDATE_FILE_HASH_LEN) {
                throw new PreCheckException(ResponseCodeEnum.FREEZE_UPDATE_FILE_HASH_DOES_NOT_MATCH);
            }
        }
    }

    private static @NonNull FreezeTransactionBody getFreezeTransactionBody(final TransactionBody txn)
            throws PreCheckException {
        final FreezeTransactionBody freezeTxn = txn.freezeOrThrow();

        // freeze.proto properties startHour, startMin, endHour, endMin are deprecated in the protobuf
        // reject any freeze transactions that set these properties
        if (freezeTxn.startHour() != 0
                || freezeTxn.startMin() != 0
                || freezeTxn.endHour() != 0
                || freezeTxn.endMin() != 0) {
            throw new PreCheckException(ResponseCodeEnum.INVALID_FREEZE_TRANSACTION_BODY);
        }
        return freezeTxn;
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var txn = context.body();
        final StoreFactory storeFactory = context.storeFactory();
        final ReadableUpgradeFileStore upgradeFileStore = storeFactory.readableStore(ReadableUpgradeFileStore.class);
        final ReadableNodeStore nodeStore = storeFactory.readableStore(ReadableNodeStore.class);
        final ReadableStakingInfoStore stakingInfoStore = storeFactory.readableStore(ReadableStakingInfoStore.class);
        final WritableFreezeStore freezeStore = storeFactory.writableStore(WritableFreezeStore.class);

        final FreezeTransactionBody freezeTxn = txn.freezeOrThrow();

        validateSemantics(freezeTxn, freezeStore, upgradeFileStore);

        final FileID updateFileID = freezeTxn.updateFile();
        final var filesConfig = context.configuration().getConfigData(FilesConfig.class);

        final FreezeUpgradeActions upgradeActions = new FreezeUpgradeActions(
                context.configuration(),
                freezeStore,
                freezeExecutor,
                upgradeFileStore,
                nodeStore,
                stakingInfoStore,
                entityIdFactory);
        final Timestamp freezeStartTime = freezeTxn.startTime(); // may be null for some freeze types

        switch (freezeTxn.freezeType()) {
            case PREPARE_UPGRADE -> {
                // by the time we get here, we've already checked that fileHash is non-null in preHandle()
                freezeStore.updateFileHash(freezeTxn.fileHash());
                log.info("Preparing upgrade with file {}, hash {}", updateFileID, freezeTxn.fileHash());
                try {
                    if (updateFileID != null
                            && updateFileID.fileNum()
                                    >= filesConfig.softwareUpdateRange().left()
                            && updateFileID.fileNum()
                                    <= filesConfig.softwareUpdateRange().right()) {
                        upgradeActions.extractSoftwareUpgrade(upgradeFileStore.getFull(updateFileID));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Error extracting upgrade file", e);
                }
            }
            case FREEZE_UPGRADE -> upgradeActions.scheduleFreezeUpgradeAt(requireNonNull(freezeStartTime));
            case FREEZE_ABORT -> {
                upgradeActions.abortScheduledFreeze();
                freezeStore.updateFileHash(Bytes.EMPTY);
                log.info("Preparing freeze abort with file {}, hash null", updateFileID);
            }
            case TELEMETRY_UPGRADE -> {
                try {
                    if (updateFileID != null
                            && updateFileID.fileNum()
                                    >= filesConfig.softwareUpdateRange().left()
                            && updateFileID.fileNum()
                                    <= filesConfig.softwareUpdateRange().right()) {
                        upgradeActions.extractTelemetryUpgrade(
                                upgradeFileStore.getFull(updateFileID), requireNonNull(freezeStartTime));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Error extracting upgrade file", e);
                }
            }
            case FREEZE_ONLY -> upgradeActions.scheduleFreezeOnlyAt(requireNonNull(freezeStartTime));
            // UNKNOWN_FREEZE_TYPE will fail at preHandle, this code should never get called
            case UNKNOWN_FREEZE_TYPE -> throw new HandleException(ResponseCodeEnum.INVALID_FREEZE_TRANSACTION_BODY);
        }
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        // Can only reach consensus with a privileged account as payer
        return Fees.FREE;
    }

    /**
     * Performs checks that the entities related to this transaction exist and are valid.
     */
    private static void validateSemantics(
            @NonNull final FreezeTransactionBody freezeTxn,
            @NonNull final ReadableFreezeStore freezeStore,
            @NonNull final ReadableUpgradeFileStore upgradeStore) {
        requireNonNull(freezeTxn);
        requireNonNull(freezeStore);
        requireNonNull(upgradeStore);
        final FileID updateFileID = freezeTxn.updateFile();

        if ((freezeTxn.freezeType() == PREPARE_UPGRADE || freezeTxn.freezeType() == TELEMETRY_UPGRADE)
                && (updateFileID == null || upgradeStore.peek(updateFileID) == null)) {
            throw new IllegalStateException("Update file not found");
        }
    }
    /**
     * For freeze types FREEZE_ONLY, FREEZE_UPGRADE, and TELEMETRY_UPGRADE, the startTime field must be set to
     * a time in the future, where future is defined as a time after the current consensus time.
     * @throws PreCheckException if startTime is not in the future
     */
    private static void verifyFreezeStartTimeIsInFuture(
            final @NonNull FreezeTransactionBody freezeTxn, final @NonNull Timestamp curConsensusTime)
            throws PreCheckException {
        requireNonNull(freezeTxn);
        requireNonNull(curConsensusTime);
        final Timestamp freezeStartTime = freezeTxn.startTime();
        if (freezeStartTime == null || (freezeStartTime.seconds() == 0 && freezeStartTime.nanos() == 0)) {
            throw new PreCheckException(ResponseCodeEnum.INVALID_FREEZE_TRANSACTION_BODY);
        }
        final Instant freezeStartTimeInstant =
                Instant.ofEpochSecond(freezeStartTime.seconds(), freezeStartTime.nanos());

        final Instant effectiveNowInstant = Instant.ofEpochSecond(curConsensusTime.seconds(), curConsensusTime.nanos());

        // make sure freezeStartTime is after current consensus time
        final boolean freezeStartTimeIsInFuture = freezeStartTimeInstant.isAfter(effectiveNowInstant);
        if (!freezeStartTimeIsInFuture) {
            throw new PreCheckException(ResponseCodeEnum.FREEZE_START_TIME_MUST_BE_FUTURE);
        }
    }

    /**
     * For freeze types PREPARE_UPGRADE, FREEZE_UPGRADE, and TELEMETRY_UPGRADE,
     * the updateFile and fileHash fields must be set.
     * @throws PreCheckException if updateFile or fileHash are not set or don't pass sanity checks
     */
    private static void verifyUpdateFile(
            final @NonNull FreezeTransactionBody freezeTxn,
            final @NonNull ReadableUpgradeFileStore upgradeStore,
            final LongPair softwareUpdateRange)
            throws PreCheckException {
        requireNonNull(freezeTxn);
        requireNonNull(upgradeStore);

        // check that the updateFileID was set in the freeze transaction to the correct ID for upgrade files
        // this is the *only* place that the FileID is accessed
        // we subsequently ignore it
        final FileID updateFileID = freezeTxn.updateFile();
        if (updateFileID == null
                || updateFileID.fileNum() < softwareUpdateRange.left()
                || updateFileID.fileNum() > softwareUpdateRange.right()) {
            throw new PreCheckException(ResponseCodeEnum.INVALID_FREEZE_TRANSACTION_BODY);
        }
        if (upgradeStore.peek(updateFileID) == null) {
            throw new PreCheckException(ResponseCodeEnum.FREEZE_UPDATE_FILE_DOES_NOT_EXIST);
        }
        if (freezeTxn.freezeType() == PREPARE_UPGRADE
                || freezeTxn.freezeType() == FREEZE_UPGRADE
                || freezeTxn.freezeType() == TELEMETRY_UPGRADE) {
            try {
                final var fileBytes = upgradeStore.getFull(updateFileID);
                final var fileHash = noThrowSha384HashOf(fileBytes);
                if (!Objects.equals(fileHash, freezeTxn.fileHash())) {
                    throw new PreCheckException(ResponseCodeEnum.UPDATE_FILE_HASH_DOES_NOT_MATCH_PREPARED);
                }
            } catch (IOException e) {
                // This should never happen because we already verified the file wasn't null or empty
                log.error("Couldn't read (previously non-empty) bytes of file {}!", updateFileID, e);
                throw new PreCheckException(ResponseCodeEnum.FREEZE_UPDATE_FILE_DOES_NOT_EXIST);
            }
        }
    }
}
