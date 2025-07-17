// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.CommonUtils.hex;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.file.FileGetInfoQuery;
import com.hedera.hapi.node.file.FileGetInfoResponse;
import com.hedera.hapi.node.file.FileGetInfoResponse.FileInfo;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.fees.apis.file.FileOperations;
import com.hedera.node.app.hapi.fees.usage.file.ExtantFileContext;
import com.hedera.node.app.hapi.fees.usage.file.FileOpsUsage;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.file.FileMetadata;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.ReadableUpgradeFileStore;
import com.hedera.node.app.service.file.impl.base.FileQueryBase;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#FILE_GET_INFO}.
 */
@Singleton
public class FileGetInfoHandler extends FileQueryBase {
    private final FileOpsUsage fileOpsUsage;
    private final Cryptography cryptography;

    /**
     * Constructs a {@link FileGetInfoHandler} with the given {@link FileOpsUsage}.
     * @param fileOpsUsage the file operations usage to be used for fee calculation
     */
    @Inject
    public FileGetInfoHandler(final FileOpsUsage fileOpsUsage) {
        this.fileOpsUsage = fileOpsUsage;
        cryptography = CryptographyProvider.getInstance();
    }

    @Override
    public @NonNull QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.fileGetInfoOrThrow().header();
    }

    @Override
    public @NonNull Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = FileGetInfoResponse.newBuilder().header(header);
        return Response.newBuilder().fileGetInfo(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        final var query = context.query();
        final FileGetInfoQuery op = query.fileGetInfoOrThrow();
        if (!op.hasFileID()) {
            throw new PreCheckException(INVALID_FILE_ID);
        }
    }

    @Override
    public @NonNull Fees computeFees(@NonNull QueryContext queryContext) {
        final var query = queryContext.query();
        final var fileStore = queryContext.createStore(ReadableFileStore.class);
        final var op = query.fileGetInfoOrThrow();
        final var fileId = op.fileIDOrElse(FileID.DEFAULT);
        final File file = fileStore.getFileLeaf(fileId);
        if(queryContext.configuration().getConfigData(FeesConfig.class).simpleFeesEnabled()) {
            FileOperations transfer = new FileOperations("FileGetInfo", "get file information");
            Map<String, Object> params = new HashMap<>();
            params.put("numSignatures", 0);
            params.put("numKeys", 0);
            params.put("numBytes", (int)file.contents().length());
            return transfer.computeFee(params, queryContext.activeRate());
        }

        return queryContext
                .feeCalculator()
                .legacyCalculate(sigValueObj -> usageGiven(CommonPbjConverters.fromPbj(query), file));
    }

    @Override
    public @NonNull Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var query = context.query();
        final var fileStore = context.createStore(ReadableFileStore.class);
        final var upgradeFileStore = context.createStore(ReadableUpgradeFileStore.class);
        final var ledgerConfig = context.configuration().getConfigData(LedgerConfig.class);
        final var fileServiceConfig = context.configuration().getConfigData(FilesConfig.class);
        final var op = query.fileGetInfoOrThrow();
        final var responseBuilder = FileGetInfoResponse.newBuilder();
        final var file = op.fileIDOrThrow();

        final var responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        responseBuilder.header(header);
        if (header.nodeTransactionPrecheckCode() == OK && responseType != COST_ANSWER) {
            final Optional<FileInfo> optionalInfo;
            try {
                optionalInfo = infoForFile(file, fileStore, ledgerConfig, upgradeFileStore, fileServiceConfig);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to read file contents", e);
            }

            if (optionalInfo.isEmpty()) {
                responseBuilder.header(header.copyBuilder()
                        .nodeTransactionPrecheckCode(INVALID_FILE_ID)
                        .build());
            } else {
                responseBuilder.fileInfo(optionalInfo.get());
            }
        }

        return Response.newBuilder().fileGetInfo(responseBuilder).build();
    }

    /**
     * Provides information about a file.
     * @param fileID the file to get information about
     * @param fileStore the file store
     * @param ledgerConfig Ledger configuration properties
     * @return the information about the file
     */
    @SuppressWarnings("java:S5738") // Suppress the warning that we are using deprecated class(CryptographyHolder)
    private Optional<FileInfo> infoForFile(
            @NonNull final FileID fileID,
            @NonNull final ReadableFileStore fileStore,
            @NonNull final LedgerConfig ledgerConfig,
            @NonNull final ReadableUpgradeFileStore upgradeFileStore,
            @NonNull final FilesConfig fileServiceConfig)
            throws IOException {

        FileMetadata meta = null;
        long contentSize = 0L;
        // upgrade is for the entire network, not a node. It's across shards and realms, however, which is why we ignore
        // the shard and realm values.
        if (fileID.fileNum() >= fileServiceConfig.softwareUpdateRange().left()
                && fileID.fileNum() <= fileServiceConfig.softwareUpdateRange().right()) {
            final var file = upgradeFileStore.peek(fileID);
            if (file != null) {
                // The "memo" of a special upgrade file is its hexed SHA-384 hash for DevOps convenience
                final var contents = upgradeFileStore.getFull(fileID).toByteArray();
                contentSize = contents.length;
                final var upgradeHash = hex(cryptography.digestBytesSync(contents));
                meta = new FileMetadata(
                        file.fileId(),
                        Timestamp.newBuilder().seconds(file.expirationSecond()).build(),
                        file.keys(),
                        Bytes.EMPTY,
                        upgradeHash,
                        file.deleted(),
                        Timestamp.newBuilder()
                                .seconds(file.preSystemDeleteExpirationSecond())
                                .build());
            }
        } else {
            meta = fileStore.getFileMetadata(fileID);
        }

        if (meta == null) {
            return Optional.empty();
        } else {
            final var info = FileInfo.newBuilder();
            info.memo(meta.memo() == null ? "" : meta.memo());
            info.fileID(fileID);
            info.size((contentSize > 0L ? contentSize : meta.contents().length()));
            info.expirationTime(meta.expirationTimestamp());
            info.deleted(meta.deleted());
            info.keys(meta.keys());
            info.ledgerId(ledgerConfig.id());
            return Optional.of(info.build());
        }
    }

    private FeeData usageGiven(
            @NonNull final com.hederahashgraph.api.proto.java.Query query, @Nullable final File file) {
        requireNonNull(query);
        if (file == null) {
            return FeeData.getDefaultInstance();
        }
        final com.hederahashgraph.api.proto.java.File details = CommonPbjConverters.fromPbj(file);
        final var ctx = ExtantFileContext.newBuilder()
                .setCurrentSize(details.getContents().toByteArray().length)
                .setCurrentWacl(details.getKeys())
                .setCurrentMemo(details.getMemo())
                .setCurrentExpiry(details.getExpirationSecond())
                .build();
        return fileOpsUsage.fileInfoUsage(query, ctx);
    }
}
