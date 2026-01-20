// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;
import static com.hedera.node.app.service.file.impl.utils.FileServiceUtils.notGenesisCreation;
import static com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.file.FileGetContentsQuery;
import com.hedera.hapi.node.file.FileGetContentsResponse;
import com.hedera.hapi.node.file.FileGetContentsResponse.FileContents;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.fee.FileFeeBuilder;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.impl.base.FileQueryBase;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#FILE_GET_CONTENTS}.
 */
@Singleton
public class FileGetContentsHandler extends FileQueryBase {
    private final FileFeeBuilder usageEstimator;
    private final V0490FileSchema genesisSchema;

    /**
     * Constructs a {@link FileGetContentsHandler} with the given {@link FileFeeBuilder}.
     * @param usageEstimator the file fee builder to be used for fee calculation
     */
    @Inject
    public FileGetContentsHandler(
            @NonNull final FileFeeBuilder usageEstimator, @NonNull final V0490FileSchema genesisSchema) {
        this.usageEstimator = requireNonNull(usageEstimator);
        this.genesisSchema = requireNonNull(genesisSchema);
    }

    @Override
    public @NonNull QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.fileGetContentsOrThrow().header();
    }

    @Override
    public @NonNull Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = FileGetContentsResponse.newBuilder().header(header);
        return Response.newBuilder().fileGetContents(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        final var query = context.query();
        final FileGetContentsQuery op = query.fileGetContentsOrThrow();
        if (!op.hasFileID()) {
            throw new PreCheckException(INVALID_FILE_ID);
        }
    }

    @Override
    public @NonNull Fees computeFees(@NonNull QueryContext queryContext) {
        final var query = queryContext.query();
        final var fileStore = queryContext.createStore(ReadableFileStore.class);
        final var nodeStore = queryContext.createStore(ReadableNodeStore.class);
        final var op = query.fileGetContentsOrThrow();
        final var fileId = op.fileIDOrElse(FileID.DEFAULT);
        final var responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        final FileContents fileContents = contentFile(fileId, fileStore, queryContext.configuration(), nodeStore);
        return queryContext
                .feeCalculator()
                .legacyCalculate(sigValueObj ->
                        usageGivenType(fileContents, CommonPbjConverters.fromPbjResponseType(responseType)));
    }

    @Override
    public @NonNull Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var query = context.query();
        final var fileStore = context.createStore(ReadableFileStore.class);
        final var nodeStore = context.createStore(ReadableNodeStore.class);
        final var op = query.fileGetContentsOrThrow();
        final var responseBuilder = FileGetContentsResponse.newBuilder();
        final var fileId = op.fileIDOrThrow();

        final var responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        responseBuilder.header(header);
        if (header.nodeTransactionPrecheckCode() == OK && responseType != COST_ANSWER) {
            final var content = contentFile(fileId, fileStore, context.configuration(), nodeStore);
            if (content == null) {
                responseBuilder.header(header.copyBuilder()
                        .nodeTransactionPrecheckCode(INVALID_FILE_ID)
                        .build());
            } else {
                responseBuilder.fileContents(content);
            }
        }

        return Response.newBuilder().fileGetContents(responseBuilder).build();
    }

    /**
     * Provides file content about a file.
     *
     * @param fileID the file to get information about
     * @param fileStore the file store
     * @param config the configuration
     * @param nodeStore the ReadableNodeStore
     * @return the content about the file
     */
    private @Nullable FileContents contentFile(
            @NonNull final FileID fileID,
            @NonNull final ReadableFileStore fileStore,
            @NonNull final Configuration config,
            @NonNull final ReadableNodeStore nodeStore) {
        final var meta = fileStore.getFileMetadata(fileID);
        if (meta == null) {
            if (notGenesisCreation(fileID, config)) {
                return null;
            } else {
                final var genesisContent = genesisContentProviders(config, nodeStore)
                        .getOrDefault(fileID.fileNum(), ignore -> EMPTY)
                        .apply(config);
                return new FileContents(fileID, genesisContent);
            }
        } else {
            final var info = FileContents.newBuilder();
            info.fileID(fileID);
            info.contents(meta.contents());
            return info.build();
        }
    }

    private Map<Long, Function<Configuration, Bytes>> genesisContentProviders(
            @NonNull final Configuration config, @NonNull final ReadableNodeStore nodeStore) {
        final var filesConfig = config.getConfigData(FilesConfig.class);
        return Map.of(
                filesConfig.addressBook(), ignore -> genesisSchema.nodeStoreAddressBook(nodeStore),
                filesConfig.nodeDetails(), ignore -> genesisSchema.nodeStoreNodeDetails(nodeStore),
                filesConfig.feeSchedules(), genesisSchema::genesisFeeSchedules,
                filesConfig.simpleFeesSchedules(), genesisSchema::genesisSimpleFeesSchedules,
                filesConfig.exchangeRates(), genesisSchema::genesisExchangeRates,
                filesConfig.networkProperties(), genesisSchema::genesisNetworkProperties,
                filesConfig.hapiPermissions(), genesisSchema::genesisHapiPermissions,
                filesConfig.throttleDefinitions(), genesisSchema::genesisThrottleDefinitions);
    }

    private FeeData usageGivenType(final FileContents fileContents, final ResponseType type) {
        /* Given the test in {@code GetFileContentsAnswer.checkValidity}, this can only be empty
         * under the extraordinary circumstance that the desired file expired during the query
         * answer flow (which will now fail downstream with an appropriate status code); so
         * just return the default {@code FeeData} here. */
        if (fileContents == null) {
            return FeeData.getDefaultInstance();
        }
        return usageEstimator.getFileContentQueryFeeMatrices(
                fileContents.contents().toByteArray().length, type);
    }
}
