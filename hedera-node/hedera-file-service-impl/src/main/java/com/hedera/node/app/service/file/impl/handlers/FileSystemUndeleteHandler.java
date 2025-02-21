// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hedera.node.app.service.file.impl.utils.FileServiceUtils.preValidate;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.fee.FileFeeBuilder;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.impl.WritableFileStore;
import com.hedera.node.app.service.file.impl.utils.FileServiceUtils;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.LedgerConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#SYSTEM_UNDELETE}.
 */
@Singleton
public class FileSystemUndeleteHandler implements TransactionHandler {
    private final FileFeeBuilder usageEstimator;

    /**
     * Constructs a {@link FileSystemUndeleteHandler} with the given {@link FileFeeBuilder}.
     * @param usageEstimator the file fee builder to be used for fee calculation
     */
    @Inject
    public FileSystemUndeleteHandler(final FileFeeBuilder usageEstimator) {
        this.usageEstimator = usageEstimator;
    }

    /**
     * Performs checks independent of state or context.
     * @param context the context to check
     */
    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var transactionBody = txn.systemUndeleteOrThrow();

        if (transactionBody.fileID() == null) {
            throw new PreCheckException(INVALID_FILE_ID);
        }
    }

    /**
     * This method is called during the pre-handle workflow.
     *
     * <p>Determines signatures needed for undelete system file
     *
     * @param context the {@link PreHandleContext} which collects all information that will be
     *     passed to {@code handle()}
     * @throws PreCheckException if any issue happens on the pre handle level
     */
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);

        final var transactionBody = context.body().systemUndeleteOrThrow();
        final var fileStore = context.createStore(ReadableFileStore.class);
        final var transactionFileId = requireNonNull(transactionBody.fileID());
        preValidate(transactionFileId, fileStore, context);
    }

    @Override
    public void handle(@NonNull final HandleContext handleContext) throws HandleException {
        requireNonNull(handleContext);

        final var systemUndeleteTransactionBody = handleContext.body().systemUndeleteOrThrow();
        if (!systemUndeleteTransactionBody.hasFileID()) {
            throw new HandleException(INVALID_FILE_ID);
        }
        var fileId = systemUndeleteTransactionBody.fileIDOrThrow();
        final var ledgerConfig = handleContext.configuration().getConfigData(LedgerConfig.class);

        final var fileStore = handleContext.storeFactory().writableStore(WritableFileStore.class);
        final File file = FileServiceUtils.verifyNotSystemFile(ledgerConfig, fileStore, fileId, true);

        final var oldExpiry = file.expirationSecond();
        // If the file is already expired, remove it from the state otherwise update the deleted flag to false
        if (oldExpiry <= handleContext.consensusNow().getEpochSecond()) {
            fileStore.removeFile(fileId);
        } else {
            /* Copy all the fields from existing special file and change deleted flag */
            final var fileBuilder = new File.Builder()
                    .fileId(file.fileId())
                    .expirationSecond(file.preSystemDeleteExpirationSecond())
                    .preSystemDeleteExpirationSecond(0L)
                    .keys(file.keys())
                    .contents(file.contents())
                    .memo(file.memo())
                    .deleted(false);

            /* --- Put the modified file. It will be in underlying state's modifications map.
            It will not be committed to state until commit is called on the state.--- */
            fileStore.put(fileBuilder.build());
        }
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull FeeContext feeContext) {
        final var txnBody = feeContext.body();
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(sigValueObj -> usageEstimator.getSystemUnDeleteFileTxFeeMatrices(
                        CommonPbjConverters.fromPbj(txnBody), sigValueObj));
    }
}
