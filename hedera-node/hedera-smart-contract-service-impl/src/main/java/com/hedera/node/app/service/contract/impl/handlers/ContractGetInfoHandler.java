// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.hexedEvmAddressOf;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.summarizeStakingInfo;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.tokenRelationshipsOf;
import static com.hedera.node.app.spi.fees.Fees.CONSTANT_FEE_DATA;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.contract.ContractGetInfoResponse;
import com.hedera.hapi.node.contract.ContractGetInfoResponse.ContractInfo;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.fees.usage.contract.ContractGetInfoUsage;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.data.TokensConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.InstantSource;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CONTRACT_GET_INFO}.
 */
@Singleton
public class ContractGetInfoHandler extends PaidQueryHandler {
    private static final long BYTES_PER_EVM_KEY_VALUE_PAIR = 64;
    private final EntityIdFactory entityIdFactory;

    private final InstantSource instantSource;

    /**
     * @param instantSource the source of the current instant
     */
    @Inject
    public ContractGetInfoHandler(
            @NonNull final InstantSource instantSource, @NonNull final EntityIdFactory entityIdFactory) {
        this.instantSource = requireNonNull(instantSource);
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.contractGetInfoOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = ContractGetInfoResponse.newBuilder().header(header);
        return Response.newBuilder().contractGetInfo(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        validateFalsePreCheck(contractFrom(context) == null, INVALID_CONTRACT_ID);
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var contractGetInfo = ContractGetInfoResponse.newBuilder().header(header);
        if (header.nodeTransactionPrecheckCode() == OK && header.responseType() == ANSWER_ONLY) {
            final var contract = requireNonNull(contractFrom(context));
            final var config = context.configuration();
            contractGetInfo.contractInfo(infoFor(
                    contract,
                    config.getConfigData(TokensConfig.class),
                    config.getConfigData(LedgerConfig.class),
                    config.getConfigData(StakingConfig.class),
                    context.createStore(ReadableTokenStore.class),
                    context.createStore(ReadableStakingInfoStore.class),
                    context.createStore(ReadableTokenRelationStore.class),
                    context.createStore(ReadableNetworkStakingRewardsStore.class)));
        }
        return Response.newBuilder().contractGetInfo(contractGetInfo).build();
    }

    @NonNull
    @Override
    public Fees computeFees(@NonNull final QueryContext context) {
        return context.feeCalculator().legacyCalculate(sigValueObj -> {
            final var contract = contractFrom(context);
            if (contract == null) {
                return CONSTANT_FEE_DATA;
            } else {
                return ContractGetInfoUsage.newEstimate(fromPbj(context.query()))
                        .givenCurrentKey(fromPbj(contract.keyOrThrow()))
                        .givenCurrentMemo(contract.memo())
                        .givenCurrentTokenAssocs(contract.numberAssociations())
                        .get();
            }
        });
    }

    private ContractInfo infoFor(
            @NonNull final Account contract,
            @NonNull final TokensConfig tokensConfig,
            @NonNull final LedgerConfig ledgerConfig,
            @NonNull final StakingConfig stakingConfig,
            @NonNull final ReadableTokenStore tokenStore,
            @NonNull final ReadableStakingInfoStore stakingInfoStore,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            @NonNull final ReadableNetworkStakingRewardsStore stakingRewardsStore) {
        final var accountId = contract.accountIdOrThrow();
        final var stakingInfo = summarizeStakingInfo(
                stakingConfig.rewardHistoryNumStoredPeriods(),
                stakingConfig.periodMins(),
                stakingRewardsStore.isStakingRewardsActivated(),
                contract,
                stakingInfoStore,
                instantSource.instant());
        final var maxReturnedRels = tokensConfig.maxRelsPerInfoQuery();
        final var builder = ContractInfo.newBuilder()
                .ledgerId(ledgerConfig.id())
                .accountID(accountId)
                .contractID(entityIdFactory.newContractId(accountId.accountNumOrThrow()))
                .deleted(contract.deleted())
                .memo(contract.memo())
                .adminKey(contract.keyOrThrow())
                .storage(contract.contractKvPairsNumber() * BYTES_PER_EVM_KEY_VALUE_PAIR)
                .autoRenewPeriod(Duration.newBuilder().seconds(contract.autoRenewSeconds()))
                .autoRenewAccountId(contract.autoRenewAccountId())
                .balance(contract.tinybarBalance())
                .expirationTime(Timestamp.newBuilder().seconds(contract.expirationSecond()))
                .maxAutomaticTokenAssociations(contract.maxAutoAssociations())
                .contractAccountID(hexedEvmAddressOf(contract))
                .stakingInfo(stakingInfo);
        if (tokensConfig.balancesInQueriesEnabled()) {
            builder.tokenRelationships(tokenRelationshipsOf(contract, tokenStore, tokenRelationStore, maxReturnedRels));
        }
        return builder.build();
    }

    private @Nullable Account contractFrom(@NonNull final QueryContext context) {
        final var accountsStore = context.createStore(ReadableAccountStore.class);
        final var contractId = context.query().contractGetInfoOrThrow().contractIDOrElse(ContractID.DEFAULT);
        final var contract = accountsStore.getContractById(contractId);
        return (contract == null || !contract.smartContract()) ? null : contract;
    }
}
