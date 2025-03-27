// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;
import static com.hedera.hapi.node.base.TokenFreezeStatus.FREEZE_NOT_APPLICABLE;
import static com.hedera.hapi.node.base.TokenFreezeStatus.FROZEN;
import static com.hedera.hapi.node.base.TokenFreezeStatus.UNFROZEN;
import static com.hedera.hapi.node.base.TokenKycStatus.GRANTED;
import static com.hedera.hapi.node.base.TokenKycStatus.KYC_NOT_APPLICABLE;
import static com.hedera.hapi.node.base.TokenKycStatus.REVOKED;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.spi.fees.Fees.CONSTANT_FEE_DATA;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenFreezeStatus;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenKycStatus;
import com.hedera.hapi.node.base.TokenRelationship;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.GetAccountDetailsQuery;
import com.hedera.hapi.node.token.GetAccountDetailsResponse;
import com.hedera.hapi.node.token.GetAccountDetailsResponse.AccountDetails;
import com.hedera.hapi.node.token.GrantedCryptoAllowance;
import com.hedera.hapi.node.token.GrantedNftAllowance;
import com.hedera.hapi.node.token.GrantedTokenAllowance;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage;
import com.hedera.node.app.hapi.fees.usage.crypto.ExtantCryptoContext;
import com.hedera.node.app.service.networkadmin.impl.utils.NetworkAdminServiceUtil;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all query information regarding {@link HederaFunctionality#GET_ACCOUNT_DETAILS}.
 */
@Singleton
public class NetworkGetAccountDetailsHandler extends PaidQueryHandler {
    private final CryptoOpsUsage cryptoOpsUsage;

    @Inject
    public NetworkGetAccountDetailsHandler(final CryptoOpsUsage cryptoOpsUsage) {
        this.cryptoOpsUsage = cryptoOpsUsage;
    }

    @Override
    @NonNull
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.accountDetailsOrThrow().headerOrThrow();
    }

    @Override
    @NonNull
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = GetAccountDetailsResponse.newBuilder().header(header);
        return Response.newBuilder().accountDetails(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        final GetAccountDetailsQuery op = context.query().accountDetailsOrThrow();

        // The Account ID must be specified
        if (!op.hasAccountId()) {
            throw new PreCheckException(INVALID_ACCOUNT_ID);
        }

        // The account must exist for that transaction ID
        final var accountStore = context.createStore(ReadableAccountStore.class);
        final var account = accountStore.getAliasedAccountById(op.accountIdOrThrow());
        mustExist(account, INVALID_ACCOUNT_ID);
    }

    @Override
    @NonNull
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var query = context.query();
        final var accountStore = context.createStore(ReadableAccountStore.class);
        final var op = query.accountDetailsOrThrow();
        final var responseBuilder = GetAccountDetailsResponse.newBuilder();
        final var account = op.accountIdOrElse(AccountID.DEFAULT);
        final var ledgerConfig = context.configuration().getConfigData(LedgerConfig.class);

        final var responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        responseBuilder.header(header);
        if (header.nodeTransactionPrecheckCode() == OK && responseType != COST_ANSWER) {
            final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
            final var readableTokenStore = context.createStore(ReadableTokenStore.class);
            final var tokenRelationStore = context.createStore(ReadableTokenRelationStore.class);
            final var optionalInfo = infoForAccount(
                    account, accountStore, tokensConfig, readableTokenStore, tokenRelationStore, ledgerConfig);

            if (optionalInfo.isEmpty()) {
                responseBuilder.header(header.copyBuilder()
                        .nodeTransactionPrecheckCode(FAIL_INVALID)
                        .build());
            } else {
                optionalInfo.ifPresent(responseBuilder::accountDetails);
            }
        }

        return Response.newBuilder().accountDetails(responseBuilder).build();
    }

    /**
     * Provides information about an account.
     * @param accountID the account to get information about
     * @param accountStore the account store
     * @return the information about the account
     */
    private Optional<AccountDetails> infoForAccount(
            @NonNull final AccountID accountID,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final TokensConfig tokensConfig,
            @NonNull final ReadableTokenStore readableTokenStore,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            @NonNull final LedgerConfig ledgerConfig) {
        final var account = accountStore.getAliasedAccountById(accountID);
        if (account == null) {
            return Optional.empty();
        } else {
            final var info = AccountDetails.newBuilder();
            info.accountId(account.accountId());
            info.contractAccountId(NetworkAdminServiceUtil.asHexedEvmAddress(accountID));
            info.deleted(account.deleted());
            info.key(account.key());
            info.balance(account.tinybarBalance());
            info.receiverSigRequired(account.receiverSigRequired());
            info.expirationTime(
                    Timestamp.newBuilder().seconds(account.expirationSecond()).build());
            info.autoRenewPeriod(
                    Duration.newBuilder().seconds(account.autoRenewSeconds()).build());
            info.memo(account.memo());
            info.ownedNfts(account.numberOwnedNfts());
            info.maxAutomaticTokenAssociations(account.maxAutoAssociations());
            info.alias(account.alias());
            info.ledgerId(ledgerConfig.id());
            info.grantedCryptoAllowances(getCryptoGrantedAllowancesList(account));
            info.grantedNftAllowances(getNftGrantedAllowancesList(account));
            info.grantedTokenAllowances(getFungibleGrantedTokenAllowancesList(account));

            final var tokenRels = tokenRelationshipsOf(
                    tokensConfig.maxRelsPerInfoQuery(), account, readableTokenStore, tokenRelationStore);
            if (!tokenRels.isEmpty()) {
                info.tokenRelationships(tokenRels);
            }
            return Optional.of(info.build());
        }
    }

    /**
     * Returns a list of token relationship for the given account.
     * @param maxRelsPerInfoQuery the maximum number of token relationships to return
     * @param account account to get token relationships for
     * @param readableTokenStore the readable token store
     * @param tokenRelationStore the token relationship store
     * @return list of token relationships for the given account
     */
    private List<TokenRelationship> tokenRelationshipsOf(
            final long maxRelsPerInfoQuery,
            @NonNull final Account account,
            @NonNull final ReadableTokenStore readableTokenStore,
            @NonNull final ReadableTokenRelationStore tokenRelationStore) {
        requireNonNull(account);
        requireNonNull(tokenRelationStore);
        requireNonNull(readableTokenStore);

        final var ret = new ArrayList<TokenRelationship>();
        var tokenId = account.headTokenId();
        int count = 0;
        TokenRelation tokenRelation;
        Token token; // token from readableToken store by tokenID
        AccountID accountID; // build from accountNumber
        while (tokenId != null && count < maxRelsPerInfoQuery) {
            accountID = account.accountId();
            tokenRelation = tokenRelationStore.get(accountID, tokenId);
            if (tokenRelation != null) {
                token = readableTokenStore.get(tokenId);
                if (token != null) {
                    addTokenRelation(ret, token, tokenRelation, tokenId);
                }
                tokenId = tokenRelation.nextToken();
            } else {
                break;
            }
            count++;
        }
        return ret;
    }

    private void addTokenRelation(
            List<TokenRelationship> ret, Token token, TokenRelation tokenRelation, TokenID tokenId) {
        TokenFreezeStatus freezeStatus = FREEZE_NOT_APPLICABLE;
        if (token.hasFreezeKey()) {
            freezeStatus = tokenRelation.frozen() ? FROZEN : UNFROZEN;
        }

        TokenKycStatus kycStatus = KYC_NOT_APPLICABLE;
        if (token.hasKycKey()) {
            kycStatus = tokenRelation.kycGranted() ? GRANTED : REVOKED;
        }

        final var tokenRelationship = TokenRelationship.newBuilder()
                .tokenId(tokenId)
                .symbol(token.symbol())
                .balance(tokenRelation.balance())
                .decimals(token.decimals())
                .kycStatus(kycStatus)
                .freezeStatus(freezeStatus)
                .automaticAssociation(tokenRelation.automaticAssociation())
                .build();
        ret.add(tokenRelationship);
    }

    /**
     * Returns the list of granted NFT allowances for the given account.
     * @param account the account to get granted crypto allowances for
     * @return list of granted NFT allowances for specific account
     */
    private static List<GrantedNftAllowance> getNftGrantedAllowancesList(final Account account) {
        if (!account.approveForAllNftAllowances().isEmpty()) {
            List<GrantedNftAllowance> nftAllowances = new ArrayList<>();
            for (var a : account.approveForAllNftAllowances()) {
                final var approveForAllNftsAllowance = GrantedNftAllowance.newBuilder();
                approveForAllNftsAllowance.tokenId(a.tokenId());
                approveForAllNftsAllowance.spender(a.spenderId());
                nftAllowances.add(approveForAllNftsAllowance.build());
            }
            return nftAllowances;
        }
        return Collections.emptyList();
    }

    /**
     * Returns the list of granted token allowances for the given account.
     * @param account the account to get granted token allowances for
     * @return list of granted token allowances for specific account
     */
    private static List<GrantedTokenAllowance> getFungibleGrantedTokenAllowancesList(final Account account) {
        if (!account.tokenAllowances().isEmpty()) {
            List<GrantedTokenAllowance> tokenAllowances = new ArrayList<>();
            final var tokenAllowance = GrantedTokenAllowance.newBuilder();
            for (var a : account.tokenAllowances()) {
                tokenAllowance.tokenId(a.tokenId());
                tokenAllowance.spender(a.spenderId());
                tokenAllowance.amount(a.amount());
                tokenAllowances.add(tokenAllowance.build());
            }
            return tokenAllowances;
        }
        return Collections.emptyList();
    }

    /**
     * Returns the list of granted crypto allowances for the given account.
     * @param account the account to get granted crypto allowances for
     * @return list of granted crypto allowances for specific account
     */
    private static List<GrantedCryptoAllowance> getCryptoGrantedAllowancesList(final Account account) {
        if (!account.cryptoAllowances().isEmpty()) {
            List<GrantedCryptoAllowance> cryptoAllowances = new ArrayList<>();
            final var cryptoAllowance = GrantedCryptoAllowance.newBuilder();
            for (var a : account.cryptoAllowances()) {
                cryptoAllowance.spender(a.spenderId());
                cryptoAllowance.amount(a.amount());
                cryptoAllowances.add(cryptoAllowance.build());
            }
            return cryptoAllowances;
        }
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public Fees computeFees(@NonNull final QueryContext queryContext) {
        final var query = queryContext.query();
        final var accountStore = queryContext.createStore(ReadableAccountStore.class);
        final var op = query.accountDetailsOrThrow();
        final var accountId = op.accountIdOrElse(AccountID.DEFAULT);
        final var account = accountStore.getAliasedAccountById(accountId);

        return queryContext.feeCalculator().legacyCalculate(sigValueObj -> usageGiven(query, account));
    }

    private FeeData usageGiven(final com.hedera.hapi.node.transaction.Query query, final Account account) {
        if (account == null) {
            return CONSTANT_FEE_DATA;
        }
        final var ctx = ExtantCryptoContext.newBuilder()
                .setCurrentKey(fromPbj(account.key()))
                .setCurrentMemo(account.memo())
                .setCurrentExpiry(account.expirationSecond())
                .setCurrentNumTokenRels(account.numberAssociations())
                .setCurrentMaxAutomaticAssociations(account.maxAutoAssociations())
                .setCurrentCryptoAllowances(Collections.emptyMap())
                .setCurrentTokenAllowances(Collections.emptyMap())
                .setCurrentApproveForAllNftAllowances(Collections.emptySet())
                .build();
        return cryptoOpsUsage.cryptoInfoUsage(fromPbj(query), ctx);
    }
}
