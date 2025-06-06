// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FILE_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_FILE_SIZE_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNAUTHORIZED;
import static com.hedera.node.app.service.file.impl.FileServiceImpl.DEFAULT_MEMO;
import static com.hedera.node.app.service.file.impl.utils.FileServiceUtils.preValidate;
import static com.hedera.node.app.service.file.impl.utils.FileServiceUtils.validateAndAddRequiredKeys;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.PRECEDING;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.file.FileUpdateTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.apis.file.FileOperations;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.file.ExtantFileContext;
import com.hedera.node.app.hapi.fees.usage.file.FileOpsUsage;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.file.FileSignatureWaivers;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.impl.WritableFileStore;
import com.hedera.node.app.service.file.impl.WritableUpgradeFileStore;
import com.hedera.node.app.spi.authorization.SystemPrivilege;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.types.LongPair;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.KeyList;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#FILE_UPDATE}.
 */
@Singleton
public class FileUpdateHandler implements TransactionHandler {
    private static final Timestamp EXPIRE_NEVER =
            Timestamp.newBuilder().seconds(Long.MAX_VALUE - 1).build();
    private final FileOpsUsage fileOpsUsage;
    private final FileSignatureWaivers fileSignatureWaivers;

    /**
     * Constructs a {@link FileUpdateHandler} with the given {@link FileOpsUsage} and {@link FileSignatureWaivers}.
     * @param fileOpsUsage the file operation usage calculator
     * @param fileSignatureWaivers the file signature waivers
     */
    @Inject
    public FileUpdateHandler(final FileOpsUsage fileOpsUsage, final FileSignatureWaivers fileSignatureWaivers) {
        this.fileOpsUsage = fileOpsUsage;
        this.fileSignatureWaivers = fileSignatureWaivers;
    }

    /**
     * Performs checks independent of state or context.
     * @param context the context to check
     */
    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var transactionBody = txn.fileUpdateOrThrow();

        if (transactionBody.fileID() == null) {
            throw new PreCheckException(INVALID_FILE_ID);
        }
    }

    /**
     * This method is called during the pre-handle workflow.
     *
     * <p>Determines signatures needed for update a file
     *
     * @param context the {@link PreHandleContext} which collects all information that will be
     *                passed to {@code #handle()}
     * @throws PreCheckException if any issue happens on the pre handle level
     */
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var body = context.body();
        final var op = body.fileUpdateOrThrow();
        final var fileStore = context.createStore(ReadableFileStore.class);
        final var transactionFileId = requireNonNull(op.fileID());
        preValidate(transactionFileId, fileStore, context);
        final var areSignaturesWaived = fileSignatureWaivers.areFileUpdateSignaturesWaived(body, context.payer());
        if (areSignaturesWaived) {
            return;
        }

        var file = fileStore.getFileLeaf(transactionFileId);
        if (wantsToMutateNonExpiryField(op)) {
            validateAndAddRequiredKeys(file, op.keys(), context);
        }
    }

    @Override
    public void handle(@NonNull final HandleContext handleContext) throws HandleException {
        requireNonNull(handleContext);

        final var fileStore = handleContext.storeFactory().writableStore(WritableFileStore.class);
        final var fileUpdate = handleContext.body().fileUpdateOrThrow();

        final var fileServiceConfig = handleContext.configuration().getConfigData(FilesConfig.class);

        if (fileUpdate.fileID() == null) {
            throw new HandleException(INVALID_FILE_ID);
        }

        // the update file always will be for the node, not a particular ledger that's why we just compare the fileNum
        // and ignore shard and realm
        FileID fileID = fileUpdate.fileIDOrThrow();
        LongPair upgradeFileRange = fileServiceConfig.softwareUpdateRange();
        if (fileID.fileNum() >= upgradeFileRange.left() && fileID.fileNum() <= upgradeFileRange.right()) {
            handleUpdateUpgradeFile(fileUpdate, handleContext);
            return;
        }

        final var maybeFile = fileStore.get(fileUpdate.fileIDOrElse(FileID.DEFAULT));
        if (maybeFile.isEmpty()) {
            throw new HandleException(INVALID_FILE_ID);
        }

        final var file = maybeFile.get();
        validateFalse(file.deleted(), FILE_DELETED);

        // First validate this file is mutable; and the pending mutations are allowed
        if (wantsToMutateNonExpiryField(fileUpdate)) {
            if (handleContext.hasPrivilegedAuthorization() != SystemPrivilege.AUTHORIZED) {
                validateTrue(file.hasKeys() && !file.keys().keys().isEmpty(), UNAUTHORIZED);
            }

            validateMaybeNewMemo(handleContext.attributeValidator(), fileUpdate);
        }

        validateAutoRenew(fileUpdate, handleContext);

        // Now we apply the mutations to a builder
        final var builder = new File.Builder();
        // But first copy over the immutable topic attributes to the builder
        builder.fileId(file.fileId());
        builder.deleted(file.deleted());

        // And then resolve mutable attributes, and put the new topic back
        final var accountsConfig = handleContext.configuration().getConfigData(AccountsConfig.class);
        resolveMutableBuilderAttributes(
                fileUpdate, builder, fileServiceConfig, file, fileID, accountsConfig, handleContext.payer());
        fileStore.put(builder.build());
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull FeeContext feeContext) {
        final var op = feeContext.body();
        final var file = feeContext
                .readableStore(ReadableFileStore.class)
                .getFileLeaf(op.fileUpdateOrThrow().fileIDOrThrow());

        final AccountID payerId = op.transactionID().accountID();

        final SystemPrivilege privilege =
                feeContext.authorizer().hasPrivilegedAuthorization(payerId, HederaFunctionality.FILE_UPDATE, op);

        // Even if the privilege is UNAUTHORIZED or IMPERMISSIBLE continue with a free fee
        // The appropriate error is thrown at a later stage of the workflow
        if (privilege != SystemPrivilege.UNNECESSARY) {
            return Fees.FREE;
        }

        final var oldFees = feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(sigValueObj ->
                        usageGiven(CommonPbjConverters.fromPbj(op), sigValueObj, CommonPbjConverters.fromPbj(file)));
        if(feeContext.configuration().getConfigData(FeesConfig.class).simpleFeesEnabled()) {
            FileOperations transfer = new FileOperations("FileUpdate", "dummy description");
            Map<String, Object> params = new HashMap<>();
            params.put("numSignatures", feeContext.numTxnSignatures());
            params.put("numKeys", 1);
            params.put("numBytes", (int)op.fileUpdateOrThrow().contents().length());
            FeeResult simpleFee = transfer.computeFee(params);
            return new Fees(oldFees.nodeFee(), 0, oldFees.serviceFee(), simpleFee.fee);
        } else {
            return oldFees;
        }
    }

    private void handleUpdateUpgradeFile(FileUpdateTransactionBody fileUpdate, HandleContext handleContext) {
        final var fileStore = handleContext.storeFactory().writableStore(WritableUpgradeFileStore.class);
        // empty old upgrade file
        FileID fileId = fileUpdate.fileIDOrThrow();

        if (fileUpdate.contents() != null && fileUpdate.contents().length() > 0) {
            fileStore.resetFileContents(fileId);
            fileStore.addUpgradeContent(fileId, fileUpdate.contents());
        }
        // Note that upgrade file memos are generated programmatically
        // as the SHA-384 hash of their contents
        final var file = new File.Builder()
                .fileId(fileId)
                .deleted(false)
                .expirationSecond(fileUpdate.expirationTimeOrElse(EXPIRE_NEVER).seconds())
                .build();
        fileStore.add(file);
    }

    private void resolveMutableBuilderAttributes(
            @NonNull final FileUpdateTransactionBody op,
            @NonNull final File.Builder builder,
            @NonNull final FilesConfig filesConfig,
            @NonNull final File file,
            @NonNull final FileID fileId,
            @NonNull final AccountsConfig accountsConfig,
            @NonNull final AccountID payerId) {
        if (op.hasKeys()) {
            builder.keys(op.keys());
        } else {
            builder.keys(file.keys());
        }
        final var contentLength = op.contents().length();
        final var zeroLengthShouldClearTarget =
                accountsConfig.isSuperuser(payerId) && filesConfig.isOverrideFile(fileId);
        if (contentLength > 0 || zeroLengthShouldClearTarget) {
            if (contentLength > filesConfig.maxSizeKb() * 1024L) {
                throw new HandleException(MAX_FILE_SIZE_EXCEEDED);
            }
            builder.contents(op.contents());
        } else {
            builder.contents(file.contents());
        }

        if (op.hasMemo()) {
            builder.memo(op.memo());
        } else {
            builder.memo(file.memo());
        }

        if (op.hasExpirationTime() && op.expirationTime().seconds() > file.expirationSecond()) {
            builder.expirationSecond(op.expirationTime().seconds());
        } else {
            builder.expirationSecond(file.expirationSecond());
        }
    }

    private void validateAutoRenew(FileUpdateTransactionBody op, HandleContext handleContext) {
        if (op.hasExpirationTime()) {
            final var category = handleContext
                    .savepointStack()
                    .getBaseBuilder(StreamBuilder.class)
                    .category();
            final var isInternalDispatch = category == CHILD || category == PRECEDING;
            final long startSeconds = isInternalDispatch
                    ? handleContext.consensusNow().getEpochSecond()
                    : handleContext
                            .body()
                            .transactionID()
                            .transactionValidStart()
                            .seconds();
            final long effectiveDuration = op.expirationTime().seconds() - startSeconds;

            final var ledgerConfig = handleContext.configuration().getConfigData(LedgerConfig.class);
            final long maxEntityLifetime = ledgerConfig.autoRenewPeriodMaxDuration();
            final long minEntityLifetime = ledgerConfig.autoRenewPeriodMinDuration();

            validateTrue(
                    effectiveDuration >= minEntityLifetime && effectiveDuration <= maxEntityLifetime,
                    AUTORENEW_DURATION_NOT_IN_RANGE);
        }
    }

    /**
     * Determines if the update operation wants to mutate non-expiry fields.
     *
     * @param op the update operation transaction body
     * @return {@code true} if the operation wants to mutate non-expiry fields, {@code false} otherwise
     */
    public static boolean wantsToMutateNonExpiryField(@NonNull final FileUpdateTransactionBody op) {
        return op.hasMemo() || op.hasKeys() || op.contents().length() > 0;
    }

    private void validateMaybeNewMemo(
            @NonNull final AttributeValidator attributeValidator, @NonNull final FileUpdateTransactionBody op) {
        if (op.hasMemo()) {
            attributeValidator.validateMemo(op.memo());
        }
    }

    private FeeData usageGiven(
            final com.hederahashgraph.api.proto.java.TransactionBody txn,
            final SigValueObj svo,
            final com.hederahashgraph.api.proto.java.File file) {
        final var sigUsage = new SigUsage(svo.getTotalSigCount(), svo.getSignatureSize(), svo.getPayerAcctSigCount());
        if (file != null) {
            final var contents = file.getContents();
            final var ctx = ExtantFileContext.newBuilder()
                    .setCurrentSize(contents == null ? 0 : contents.size())
                    .setCurrentWacl(file.getKeys())
                    .setCurrentMemo(file.getMemo())
                    .setCurrentExpiry(file.getExpirationSecond())
                    .build();
            return fileOpsUsage.fileUpdateUsage(txn, sigUsage, ctx);
        } else {
            final long now = txn.getTransactionID().getTransactionValidStart().getSeconds();
            return fileOpsUsage.fileUpdateUsage(txn, sigUsage, missingCtx(now));
        }
    }

    static ExtantFileContext missingCtx(final long now) {
        return ExtantFileContext.newBuilder()
                .setCurrentExpiry(now)
                .setCurrentMemo(DEFAULT_MEMO)
                .setCurrentWacl(KeyList.getDefaultInstance())
                .setCurrentSize(0)
                .build();
    }
}
