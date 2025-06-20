// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;
import static com.hedera.node.app.spi.fees.Fees.CONSTANT_FEE_DATA;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.token.TokenGetNftInfoResponse;
import com.hedera.hapi.node.token.TokenNftInfo;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.fees.usage.token.TokenGetNftInfoUsage;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.LedgerConfig;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#TOKEN_GET_NFT_INFO}.
 */
@Singleton
public class TokenGetNftInfoHandler extends PaidQueryHandler {
    /**
     * Default constructor for injection.
     */
    @Inject
    public TokenGetNftInfoHandler() {
        // Exists for injection
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.tokenGetNftInfoOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = TokenGetNftInfoResponse.newBuilder().header(requireNonNull(header));
        return Response.newBuilder().tokenGetNftInfo(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        final var query = context.query();
        final var nftStore = context.createStore(ReadableNftStore.class);
        final var op = query.tokenGetNftInfoOrThrow();
        final var nftId = op.nftIDOrThrow();
        validateTruePreCheck(nftId.hasTokenId(), INVALID_TOKEN_ID);
        validateTruePreCheck(nftId.serialNumber() > 0, INVALID_TOKEN_NFT_SERIAL_NUMBER);

        final var nft = nftStore.get(nftId.tokenIdOrThrow(), nftId.serialNumber());
        validateFalsePreCheck(nft == null, INVALID_NFT_ID);
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var query = context.query();
        final var config = context.configuration().getConfigData(LedgerConfig.class);
        final var nftStore = context.createStore(ReadableNftStore.class);
        final var tokenStore = context.createStore(ReadableTokenStore.class);
        final var op = query.tokenGetNftInfoOrThrow();
        final var response = TokenGetNftInfoResponse.newBuilder();
        final var nftId = op.nftIDOrElse(NftID.DEFAULT);

        final var responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        response.header(header);
        if (header.nodeTransactionPrecheckCode() == OK && responseType != COST_ANSWER) {
            final var optionalInfo = infoForNft(nftId, nftStore, tokenStore, config);
            if (optionalInfo.isPresent()) {
                response.nft(optionalInfo.get());
            } else {
                response.header(ResponseHeader.newBuilder()
                        .nodeTransactionPrecheckCode(INVALID_NFT_ID)
                        .cost(0)); // from mono service, need to validate in the future
            }
        }

        return Response.newBuilder().tokenGetNftInfo(response).build();
    }
    /**
     * Returns the {@link TokenNftInfo} for the given {@link NftID} if it exists.
     *
     * @param nftId
     * the {@link NftID} to get the {@link TokenNftInfo} for
     * @param readableNftStore
     * the {@link ReadableNftStore} to get the {@link TokenNftInfo} from
     * @param readableTokenStore
     *  the {@link ReadableTokenStore} to get the {@link com.hedera.hapi.node.state.token.Token} from
     * @param config
     * the {@link LedgerConfig} to get the ledger ID from
     * @return the {@link TokenNftInfo} for the given {@link NftID} if it exists
     */
    private Optional<TokenNftInfo> infoForNft(
            @NonNull final NftID nftId,
            @NonNull final ReadableNftStore readableNftStore,
            @NonNull final ReadableTokenStore readableTokenStore,
            @NonNull final LedgerConfig config) {
        requireNonNull(nftId);
        requireNonNull(readableNftStore);
        requireNonNull(readableTokenStore);
        requireNonNull(config);

        final var nft = readableNftStore.get(nftId.tokenIdOrThrow(), nftId.serialNumber());
        final var token = requireNonNull(readableTokenStore.get(nftId.tokenIdOrThrow()));
        if (nft == null) {
            return Optional.empty();
        } else {
            final var info = TokenNftInfo.newBuilder()
                    .ledgerId(config.id())
                    .nftID(nftId)
                    .accountID(nft.ownerIdOrElse(token.treasuryAccountIdOrThrow()))
                    .creationTime(nft.mintTime())
                    .metadata(nft.metadata())
                    .spenderId(nft.spenderId())
                    .build();
            return Optional.of(info);
        }
    }

    @NonNull
    @Override
    public Fees computeFees(@NonNull final QueryContext queryContext) {
        final var query = queryContext.query();
        final var nftStore = queryContext.createStore(ReadableNftStore.class);
        final var op = query.tokenGetNftInfoOrThrow();
        final var nftId = op.nftIDOrThrow();
        final var nft = nftStore.get(nftId);

        return queryContext.feeCalculator().legacyCalculate(sigValueObj -> usageGiven(query, nft));
    }

    private FeeData usageGiven(final com.hedera.hapi.node.transaction.Query query, final Nft nft) {
        if (nft != null) {
            final var estimate = TokenGetNftInfoUsage.newEstimate(CommonPbjConverters.fromPbj(query))
                    .givenMetadata(nft.metadata().toString());
            return estimate.get();
        } else {
            return CONSTANT_FEE_DATA;
        }
    }
}
