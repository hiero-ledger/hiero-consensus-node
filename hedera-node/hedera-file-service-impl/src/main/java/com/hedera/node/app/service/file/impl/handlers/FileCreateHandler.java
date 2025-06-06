// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.node.app.service.file.impl.utils.FileServiceUtils.validateAndAddRequiredKeys;
import static com.hedera.node.app.service.file.impl.utils.FileServiceUtils.validateContent;
import static com.hedera.node.app.spi.validation.ExpiryMeta.NA;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.file.FileCreateTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.apis.file.FileOperations;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.file.FileOpsUsage;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.file.impl.WritableFileStore;
import com.hedera.node.app.service.file.impl.records.CreateFileStreamBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#FILE_CREATE}.
 */
@Singleton
public class FileCreateHandler implements TransactionHandler {
    private final FileOpsUsage fileOpsUsage;

    /**
     * Constructs a {@link FileCreateHandler} with the given {@link FileOpsUsage}.
     *
     * @param fileOpsUsage the file operation usage calculator
     */
    @Inject
    public FileCreateHandler(final FileOpsUsage fileOpsUsage) {
        this.fileOpsUsage = fileOpsUsage;
    }

    /**
     * Performs checks independent of state or context.
     *
     * @param context the {@link PureChecksContext} which collects all information
     */
    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        final FileCreateTransactionBody transactionBody = context.body().fileCreateOrThrow();

        if (!transactionBody.hasExpirationTime()) {
            throw new PreCheckException(INVALID_EXPIRATION_TIME);
        }
    }

    /**
     * This method is called during the pre-handle workflow.
     *
     * <p>Determines signatures needed for create a file
     *
     * @param context the {@link PreHandleContext} which collects all information
     * @throws PreCheckException if any issue happens on the pre handle level
     */
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var transactionBody = context.body().fileCreateOrThrow();

        validateAndAddRequiredKeys(null, transactionBody.keys(), context);
    }

    @Override
    public void handle(@NonNull final HandleContext handleContext) throws HandleException {
        requireNonNull(handleContext);

        final var builder = new File.Builder();
        final var fileServiceConfig = handleContext.configuration().getConfigData(FilesConfig.class);

        final var fileCreateTransactionBody = handleContext.body().fileCreateOrThrow();

        if (fileCreateTransactionBody.hasKeys()) {
            KeyList transactionKeyList = fileCreateTransactionBody.keys();
            builder.keys(transactionKeyList);
        }

        /* Validate if the current file can be created */
        final var fileStore = handleContext.storeFactory().writableStore(WritableFileStore.class);
        if (fileStore.sizeOfState() >= fileServiceConfig.maxNumber()) {
            throw new HandleException(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);
        }

        var expiry = fileCreateTransactionBody.hasExpirationTime()
                ? fileCreateTransactionBody.expirationTimeOrThrow().seconds()
                : NA;
        final var entityExpiryMeta = new ExpiryMeta(
                expiry,
                NA,
                // Shard and realm will be ignored if num is NA
                null);

        try {
            final var effectiveExpiryMeta = handleContext
                    .expiryValidator()
                    .resolveCreationAttempt(false, entityExpiryMeta, HederaFunctionality.FILE_CREATE);
            builder.expirationSecond(effectiveExpiryMeta.expiry());

            handleContext.attributeValidator().validateMemo(fileCreateTransactionBody.memo());
            builder.memo(fileCreateTransactionBody.memo());

            final var hederaConfig = handleContext.configuration().getConfigData(HederaConfig.class);
            builder.keys(fileCreateTransactionBody.keys());
            final var fileId = FileID.newBuilder()
                    .fileNum(handleContext.entityNumGenerator().newEntityNum())
                    .shardNum(
                            fileCreateTransactionBody.hasShardID()
                                    ? fileCreateTransactionBody.shardIDOrThrow().shardNum()
                                    : hederaConfig.shard())
                    .realmNum(
                            fileCreateTransactionBody.hasRealmID()
                                    ? fileCreateTransactionBody.realmIDOrThrow().realmNum()
                                    : hederaConfig.realm())
                    .build();
            builder.fileId(fileId);
            validateContent(CommonPbjConverters.asBytes(fileCreateTransactionBody.contents()), fileServiceConfig);
            builder.contents(fileCreateTransactionBody.contents());

            final var file = builder.build();
            fileStore.putAndIncrementCount(file);

            handleContext
                    .savepointStack()
                    .getBaseBuilder(CreateFileStreamBuilder.class)
                    .fileID(fileId);
        } catch (final HandleException e) {
            if (e.getStatus() == INVALID_EXPIRATION_TIME) {
                // (FUTURE) Remove this translation done for mono-service fidelity
                throw new HandleException(AUTORENEW_DURATION_NOT_IN_RANGE);
            }
            throw e;
        }
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var txnBody = feeContext.body();
        final var oldFees = feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(svo -> fileOpsUsage.fileCreateUsage(
                        CommonPbjConverters.fromPbj(txnBody),
                        new SigUsage(svo.getTotalSigCount(), svo.getSignatureSize(), svo.getPayerAcctSigCount())));

        if(feeContext.configuration().getConfigData(FeesConfig.class).simpleFeesEnabled()) {
            FileOperations transfer = new FileOperations("FileCreate", "dummy description");
            Map<String, Object> params = new HashMap<>();
            params.put("numSignatures", feeContext.numTxnSignatures());
            params.put("numKeys", 1);
            params.put("numBytes", (int)txnBody.fileCreateOrThrow().contents().length());
            FeeResult simpleFee = transfer.computeFee(params);
            return new Fees(oldFees.nodeFee(), 0, oldFees.serviceFee(), simpleFee.fee);
        }

        return oldFees;
    }
}
