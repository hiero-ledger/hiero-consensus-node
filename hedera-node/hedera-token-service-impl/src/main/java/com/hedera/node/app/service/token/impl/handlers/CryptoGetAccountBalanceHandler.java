// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static com.hedera.node.app.spi.validation.Validations.validateAccountID;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.TokenBalance;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.CryptoGetAccountBalanceQuery;
import com.hedera.hapi.node.token.CryptoGetAccountBalanceResponse;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.workflows.FreeQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.TokensConfig;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#CRYPTO_GET_ACCOUNT_BALANCE}.
 */
@Singleton
public class CryptoGetAccountBalanceHandler extends FreeQueryHandler {

    private static final SpeedometerMetric.Config BALANCE_SPEEDOMETER_CONFIG = new SpeedometerMetric.Config(
                    "app", "queriedAccountBalances")
            .withDescription("Number of balances requested in GetAccountBalance queries per second");

    private final SpeedometerMetric balanceSpeedometer;
    private final HederaConfig hederaConfig;

    /**
     * Default constructor for injection.
     */
    @Inject
    public CryptoGetAccountBalanceHandler(
            @NonNull final Metrics metrics, @NonNull final ConfigProvider configProvider) {
        super();
        this.balanceSpeedometer = metrics.getOrCreate(BALANCE_SPEEDOMETER_CONFIG);
        this.hederaConfig = configProvider.getConfiguration().getConfigData(HederaConfig.class);
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.cryptogetAccountBalanceOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = CryptoGetAccountBalanceResponse.newBuilder().header(requireNonNull(header));
        return Response.newBuilder().cryptogetAccountBalance(response).build();
    }

    @Override
    // contract.deleted() won't throw NPE since we are checking it for null the line before
    @SuppressWarnings("java:S2259")
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        final var query = context.query();
        final var accountStore = context.createStore(ReadableAccountStore.class);
        final CryptoGetAccountBalanceQuery op = query.cryptogetAccountBalanceOrThrow();
        if (op.hasAccountID()) {
            validateAccountId(op, accountStore);
        } else if (op.hasContractID()) {
            validateContractId(op, accountStore);
        } else {
            throw new PreCheckException(INVALID_ACCOUNT_ID);
        }
    }

    private void validateContractId(CryptoGetAccountBalanceQuery op, ReadableAccountStore accountStore)
            throws PreCheckException {
        mustExist(op.contractID(), INVALID_CONTRACT_ID);
        final ContractID contractId = (ContractID) op.balanceSource().value();
        validateTruePreCheck(contractId.shardNum() == hederaConfig.shard(), INVALID_CONTRACT_ID);
        validateTruePreCheck(contractId.realmNum() == hederaConfig.realm(), INVALID_CONTRACT_ID);
        validateTruePreCheck(
                (contractId.hasContractNum() && contractId.contractNumOrThrow() >= 0) || contractId.hasEvmAddress(),
                INVALID_CONTRACT_ID);
        final var contract = accountStore.getContractById(requireNonNull(op.contractID()));
        validateFalsePreCheck(contract == null, INVALID_CONTRACT_ID);
        validateTruePreCheck(contract.smartContract(), INVALID_CONTRACT_ID);
        validateFalsePreCheck(contract.deleted(), CONTRACT_DELETED);
    }

    private void validateAccountId(CryptoGetAccountBalanceQuery op, ReadableAccountStore accountStore)
            throws PreCheckException {
        AccountID accountId = (AccountID) op.balanceSource().value();
        validateTruePreCheck(accountId.shardNum() == hederaConfig.shard(), INVALID_ACCOUNT_ID);
        validateTruePreCheck(accountId.realmNum() == hederaConfig.realm(), INVALID_ACCOUNT_ID);
        validateAccountID(accountId, INVALID_ACCOUNT_ID);
        final var account = accountStore.getAliasedAccountById(requireNonNull(op.accountID()));
        validateFalsePreCheck(account == null, INVALID_ACCOUNT_ID);
        validateFalsePreCheck(account.deleted(), ACCOUNT_DELETED);
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var query = context.query();
        final var config = context.configuration().getConfigData(TokensConfig.class);
        final var accountStore = context.createStore(ReadableAccountStore.class);
        final var tokenRelationStore = context.createStore(ReadableTokenRelationStore.class);
        final var tokenStore = context.createStore(ReadableTokenStore.class);
        final var op = query.cryptogetAccountBalanceOrThrow();
        final var response = CryptoGetAccountBalanceResponse.newBuilder();

        response.header(header);
        if (header.nodeTransactionPrecheckCode() == OK) {
            final var account = op.hasAccountID()
                    ? accountStore.getAliasedAccountById(op.accountIDOrThrow())
                    : accountStore.getContractById(op.contractIDOrThrow());
            requireNonNull(account);
            response.accountID(account.accountIdOrThrow()).balance(account.tinybarBalance());
            if (config.balancesInQueriesEnabled()) {
                final var tokenBalances = getTokenBalances(config, account, tokenStore, tokenRelationStore);
                balanceSpeedometer.update(tokenBalances.size());
                response.tokenBalances(tokenBalances);
            }
        }

        return Response.newBuilder().cryptogetAccountBalance(response).build();
    }

    /**
     * Calculate TokenBalance of an Account.
     *
     * @param tokenConfig use TokenConfig to get maxRelsPerInfoQuery value
     * @param account the account to be calculated from
     * @param readableTokenStore readable token store
     * @param tokenRelationStore token relation store
     * @return ArrayList of TokenBalance object
     */
    private List<TokenBalance> getTokenBalances(
            @NonNull final TokensConfig tokenConfig,
            @NonNull final Account account,
            @NonNull final ReadableTokenStore readableTokenStore,
            @NonNull final ReadableTokenRelationStore tokenRelationStore) {
        final var ret = new ArrayList<TokenBalance>();
        var tokenId = account.headTokenId();
        int count = 0;
        TokenRelation tokenRelation;
        Token token; // token from readableToken store by tokenID
        AccountID accountID; // build from accountNumber
        TokenBalance tokenBalance; // created TokenBalance object
        while (tokenId != null && !tokenId.equals(TokenID.DEFAULT) && count < tokenConfig.maxRelsPerInfoQuery()) {
            accountID = account.accountId();
            tokenRelation = tokenRelationStore.get(accountID, tokenId);
            if (tokenRelation != null) {
                token = readableTokenStore.get(tokenId);
                if (token != null) {
                    tokenBalance = TokenBalance.newBuilder()
                            .tokenId(tokenId)
                            .balance(tokenRelation.balance())
                            .decimals(token.decimals())
                            .build();
                    ret.add(tokenBalance);
                }
                tokenId = tokenRelation.nextToken();
            } else {
                break;
            }
            count++;
        }
        return ret;
    }
}
