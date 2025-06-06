// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNAUTHORIZED;
import static com.hedera.node.app.service.file.impl.utils.FileServiceUtils.preValidate;
import static com.hedera.node.app.service.file.impl.utils.FileServiceUtils.validateAndAddRequiredKeysForDelete;
import static com.hedera.node.app.service.file.impl.utils.FileServiceUtils.verifyNotSystemFile;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.file.FileDeleteTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.apis.file.FileOperations;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.fee.FileFeeBuilder;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.impl.WritableFileStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#FILE_DELETE}.
 */
@Singleton
public class FileDeleteHandler implements TransactionHandler {
    private final FileFeeBuilder usageEstimator;

    /**
     * Constructs a {@link FileDeleteHandler} with the given {@link FileFeeBuilder}.
     *
     * @param usageEstimator the file fee builder to be used for fee calculation
     */
    @Inject
    public FileDeleteHandler(final FileFeeBuilder usageEstimator) {
        this.usageEstimator = usageEstimator;
    }

    /**
     * Performs checks independent of state or context.
     *
     * @param context the {@link PureChecksContext} which collects all information
     */
    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var body = context.body();
        final FileDeleteTransactionBody transactionBody = body.fileDeleteOrThrow();

        if (transactionBody.fileID() == null) {
            throw new PreCheckException(INVALID_FILE_ID);
        }
    }

    /**
     * This method is called during the pre-handle workflow.
     *
     * <p>Determines signatures needed for deleting a file
     *
     * @param context the {@link PreHandleContext} which collects all information that will be passed to
     * {@code handle()}
     * @throws PreCheckException if any issue happens on the pre handle level
     */
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);

        final var transactionBody = context.body().fileDeleteOrThrow();
        final var fileStore = context.createStore(ReadableFileStore.class);
        final var transactionFileId = requireNonNull(transactionBody.fileID());
        preValidate(transactionFileId, fileStore, context);

        var file = fileStore.getFileLeaf(transactionFileId);
        validateAndAddRequiredKeysForDelete(file, context);
    }

    @Override
    public void handle(@NonNull final HandleContext handleContext) throws HandleException {
        requireNonNull(handleContext);

        final var fileDeleteTransactionBody = handleContext.body().fileDeleteOrThrow();
        if (!fileDeleteTransactionBody.hasFileID()) {
            throw new HandleException(INVALID_FILE_ID);
        }
        var fileId = fileDeleteTransactionBody.fileIDOrThrow();

        final var ledgerConfig = handleContext.configuration().getConfigData(LedgerConfig.class);
        final var fileStore = handleContext.storeFactory().writableStore(WritableFileStore.class);

        final File file = verifyNotSystemFile(ledgerConfig, fileStore, fileId);

        // First validate this file is mutable; and the pending mutations are allowed
        validateFalse(file.keys() == null, UNAUTHORIZED);

        /* Copy part of the fields from existing, delete the file content and set the deleted flag  */
        final var fileBuilder = new File.Builder()
                .fileId(file.fileId())
                .expirationSecond(file.expirationSecond())
                .keys(file.keys())
                .contents(Bytes.EMPTY)
                .memo(file.memo())
                .deleted(true);

        /* --- Put the modified file. It will be in underlying state's modifications map.
        It will not be committed to state until commit is called on the state.--- */
        fileStore.put(fileBuilder.build());
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull FeeContext feeContext) {
        final var txnBody = feeContext.body();
        final var oldFees = feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(
                        svo -> usageEstimator.getFileDeleteTxFeeMatrices(CommonPbjConverters.fromPbj(txnBody), svo));
        if(feeContext.configuration().getConfigData(FeesConfig.class).simpleFeesEnabled()) {
            FileOperations transfer = new FileOperations("FileDelete", "dummy description");
            Map<String, Object> params = new HashMap<>();
            params.put("numSignatures", feeContext.numTxnSignatures());
            params.put("numKeys", 1);
            params.put("numBytes", 0);//(int)txnBody.fileDeleteOrThrow().contents().length());
            FeeResult simpleFee = transfer.computeFee(params);
            return new Fees(oldFees.nodeFee(), 0, oldFees.serviceFee(), simpleFee.fee);
        } else {
            return oldFees;
        }
    }
}
