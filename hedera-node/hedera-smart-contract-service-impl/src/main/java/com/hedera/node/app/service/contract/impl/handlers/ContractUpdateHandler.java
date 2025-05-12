// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EXISTING_AUTOMATIC_ASSOCIATIONS_EXCEED_GIVEN_LIMIT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EXPIRATION_REDUCTION_NOT_ALLOWED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_MAX_AUTO_ASSOCIATIONS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MODIFYING_IMMUTABLE_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT;
import static com.hedera.hapi.util.HapiUtils.EMPTY_KEY_LIST;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.SENTINEL_ACCOUNT_ID;
import static com.hedera.node.app.spi.fees.Fees.CONSTANT_FEE_DATA;
import static com.hedera.node.app.spi.validation.ExpiryMeta.NA;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.contract.ContractUpdateTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.hapi.utils.fee.SmartContractFeeBuilder;
import com.hedera.node.app.hapi.utils.keys.KeyUtils;
import com.hedera.node.app.service.contract.impl.records.ContractUpdateStreamBuilder;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hederahashgraph.api.proto.java.FeeData;
import com.swirlds.state.lifecycle.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CONTRACT_UPDATE}.
 */
@Singleton
public class ContractUpdateHandler implements TransactionHandler {
    private final SmartContractFeeBuilder usageEstimator = new SmartContractFeeBuilder();
    private final EntityIdFactory entityIdFactory;

    /**
     * The value for unlimited automatic associations
     */
    public static final int UNLIMITED_AUTOMATIC_ASSOCIATIONS = -1;

    /**
     * Default constructor for injection.
     */
    @Inject
    public ContractUpdateHandler(@NonNull final EntityIdFactory entityIdFactory) {
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().contractUpdateInstanceOrThrow();

        if (isAdminSigRequired(op)) {
            final var accountStore = context.createStore(ReadableAccountStore.class);
            final var targetId = op.contractIDOrThrow();
            final var maybeContract = accountStore.getContractById(targetId);
            if (maybeContract != null && maybeContract.keyOrThrow().key().kind() == Key.KeyOneOfType.CONTRACT_ID) {
                throw new PreCheckException(MODIFYING_IMMUTABLE_CONTRACT);
            }
            context.requireKeyOrThrow(targetId, INVALID_CONTRACT_ID);
        }
        if (hasCryptoAdminKey(op)) {
            context.requireKey(op.adminKeyOrThrow());
        }
        if (op.hasAutoRenewAccountId() && !op.autoRenewAccountIdOrThrow().equals(AccountID.DEFAULT)) {
            context.requireKeyOrThrow(op.autoRenewAccountIdOrThrow(), INVALID_AUTORENEW_ACCOUNT);
        }
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.contractUpdateInstanceOrThrow();
        mustExist(op.contractID(), INVALID_CONTRACT_ID);

        if (op.hasAdminKey() && processAdminKey(op)) {
            throw new PreCheckException(INVALID_ADMIN_KEY);
        }
    }

    private boolean isAdminSigRequired(final ContractUpdateTransactionBody op) {
        // Consider the update attempt with both expiration time and id reset to default fields
        final var withDefaultExpirationTimeAndTarget = op.copyBuilder()
                .contractID(ContractUpdateTransactionBody.DEFAULT.contractID())
                .expirationTime(ContractUpdateTransactionBody.DEFAULT.expirationTime())
                .build();
        // If anything else was touched, then admin sig is required
        return !withDefaultExpirationTimeAndTarget.equals(ContractUpdateTransactionBody.DEFAULT);
    }

    private boolean hasCryptoAdminKey(final ContractUpdateTransactionBody op) {
        return op.hasAdminKey() && !op.adminKeyOrThrow().hasContractID();
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        final var txn = requireNonNull(context).body();
        final var op = txn.contractUpdateInstanceOrThrow();
        final var target = op.contractIDOrThrow();

        final var accountStore = context.storeFactory().readableStore(ReadableAccountStore.class);
        final var toBeUpdated = accountStore.getContractById(target);
        validateSemantics(toBeUpdated, context, op, accountStore);
        final var changed = update(requireNonNull(toBeUpdated), context, op);
        context.storeFactory().serviceApi(TokenServiceApi.class).updateContract(changed);
        context.savepointStack()
                .getBaseBuilder(ContractUpdateStreamBuilder.class)
                .contractID(entityIdFactory.newContractId(
                        toBeUpdated.accountIdOrThrow().accountNumOrThrow()));
    }

    private void validateSemantics(
            @Nullable final Account contract,
            @NonNull final HandleContext context,
            @NonNull final ContractUpdateTransactionBody op,
            @NonNull final ReadableAccountStore accountStore) {
        validateTrue(contract != null, INVALID_CONTRACT_ID);
        validateTrue(!contract.deleted(), INVALID_CONTRACT_ID);

        if (op.hasExpirationTime()) {
            try {
                context.attributeValidator()
                        .validateExpiry(op.expirationTimeOrThrow().seconds());
            } catch (HandleException e) {
                validateFalse(contract.expiredAndPendingRemoval(), CONTRACT_EXPIRED_AND_PENDING_REMOVAL);
                throw e;
            }
        }

        validateFalse(nonExpiryFieldUpdated(op) && !isMutable(contract), MODIFYING_IMMUTABLE_CONTRACT);
        validateFalse(reducesExpiry(op, contract.expirationSecond()), EXPIRATION_REDUCTION_NOT_ALLOWED);

        if (op.hasMaxAutomaticTokenAssociations()) {
            final var ledgerConfig = context.configuration().getConfigData(LedgerConfig.class);
            final var entitiesConfig = context.configuration().getConfigData(EntitiesConfig.class);
            final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);

            final long newMaxAssociations = op.maxAutomaticTokenAssociationsOrThrow();

            if (entitiesConfig.unlimitedAutoAssociationsEnabled() && newMaxAssociations < 0) {
                validateTrue(newMaxAssociations == UNLIMITED_AUTOMATIC_ASSOCIATIONS, INVALID_MAX_AUTO_ASSOCIATIONS);
            } else {
                validateFalse(newMaxAssociations < 0, INVALID_MAX_AUTO_ASSOCIATIONS);
                validateFalse(
                        newMaxAssociations > ledgerConfig.maxAutoAssociations(),
                        REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT);
                validateFalse(
                        newMaxAssociations < contract.maxAutoAssociations(),
                        EXISTING_AUTOMATIC_ASSOCIATIONS_EXCEED_GIVEN_LIMIT);
                validateFalse(
                        entitiesConfig.limitTokenAssociations() && newMaxAssociations > tokensConfig.maxPerAccount(),
                        REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT);
            }
        }

        // validate expiry metadata
        final var currentMetadata =
                new ExpiryMeta(contract.expirationSecond(), contract.autoRenewSeconds(), contract.autoRenewAccountId());
        final var updateMeta = new ExpiryMeta(
                op.hasExpirationTime() ? op.expirationTimeOrThrow().seconds() : NA,
                op.hasAutoRenewPeriod() ? op.autoRenewPeriodOrThrow().seconds() : NA,
                null);
        context.expiryValidator().resolveUpdateAttempt(currentMetadata, updateMeta);

        context.storeFactory()
                .serviceApi(TokenServiceApi.class)
                .assertValidStakingElectionForUpdate(
                        op.hasDeclineReward(),
                        op.stakedId().kind().name(),
                        op.stakedAccountId(),
                        op.stakedNodeId(),
                        accountStore,
                        context.networkInfo());
    }

    private boolean processAdminKey(ContractUpdateTransactionBody op) {
        if (EMPTY_KEY_LIST.equals(op.adminKey())) {
            return false;
        }
        return keyIfAcceptable(op.adminKey());
    }

    private boolean keyIfAcceptable(Key candidate) {
        boolean keyIsNotValid = !KeyUtils.isValid(candidate);
        return keyIsNotValid || candidate.contractID() != null;
    }

    private boolean nonExpiryFieldUpdated(ContractUpdateTransactionBody op) {
        return isAdminSigRequired(op);
    }

    private boolean affectsMemo(@NonNull final ContractUpdateTransactionBody op) {
        return op.hasMemoWrapper() || (!op.memoOrElse("").isEmpty());
    }

    private boolean isMutable(final Account contract) {
        return Optional.ofNullable(contract.key())
                .map(key -> !key.hasContractID())
                .orElse(false);
    }

    private boolean reducesExpiry(ContractUpdateTransactionBody op, long curExpiry) {
        return op.hasExpirationTime() && op.expirationTimeOrThrow().seconds() < curExpiry;
    }

    /**
     * @param contract the account of the contract to be updated
     * @param context the {@link HandleContext} of the active ContractUpdateTransaction
     * @param op the body of contract update transaction
     * @return the updated account of the contract
     */
    public Account update(
            @NonNull final Account contract,
            @NonNull final HandleContext context,
            @NonNull final ContractUpdateTransactionBody op) {
        final var builder = contract.copyBuilder();
        if (op.hasAdminKey()) {
            if (EMPTY_KEY_LIST.equals(op.adminKey())) {
                try {
                    var contractID = ContractID.newBuilder()
                            .shardNum(contract.accountIdOrThrow().shardNum())
                            .realmNum(contract.accountIdOrThrow().realmNum())
                            .contractNum(contract.accountIdOrThrow().accountNumOrThrow())
                            .build();
                    var key = Key.newBuilder().contractID(contractID).build();
                    builder.key(key);
                } catch (NullPointerException e) {
                    builder.key(contract.key());
                }
            } else {
                builder.key(op.adminKey());
            }
        }
        if (op.hasExpirationTime()) {
            if (contract.expiredAndPendingRemoval()) {
                builder.expiredAndPendingRemoval(false);
            }
            builder.expirationSecond(op.expirationTimeOrThrow().seconds());
        }
        if (op.hasAutoRenewPeriod()) {
            builder.autoRenewSeconds(op.autoRenewPeriodOrThrow().seconds());
        }
        if (affectsMemo(op)) {
            final var newMemo = op.hasMemoWrapper() ? op.memoWrapperOrThrow() : op.memo();
            requireNonNull(newMemo);
            context.attributeValidator().validateMemo(newMemo);
            builder.memo(newMemo);
        }
        if (op.hasStakedAccountId()) {
            if (SENTINEL_ACCOUNT_ID.equals(op.stakedAccountId())) {
                builder.stakedAccountId((AccountID) null);
            } else {
                builder.stakedAccountId(op.stakedAccountId());
            }
        } else if (op.hasStakedNodeId()) {
            builder.stakedNodeId(op.stakedNodeIdOrThrow());
        }
        if (op.hasDeclineReward()) {
            builder.declineReward(op.declineRewardOrThrow());
        }
        if (op.hasAutoRenewAccountId()) {
            builder.autoRenewAccountId(op.autoRenewAccountId());
        }
        if (op.hasMaxAutomaticTokenAssociations()) {
            builder.maxAutoAssociations(op.maxAutomaticTokenAssociationsOrThrow());
        }
        return builder.build();
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var op = feeContext.body();
        final var contractId = op.contractUpdateInstanceOrThrow().contractIDOrElse(ContractID.DEFAULT);
        final var accountStore = feeContext.readableStore(ReadableAccountStore.class);
        final var contract = accountStore.getContractById(contractId);
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(sigValueObj -> usageGiven(fromPbj(op), sigValueObj, contract));
    }

    private FeeData usageGiven(
            @NonNull com.hederahashgraph.api.proto.java.TransactionBody txn,
            @NonNull SigValueObj sigUsage,
            @Nullable Account contract) {
        if (contract == null) {
            return CONSTANT_FEE_DATA;
        }
        return usageEstimator.getContractUpdateTxFeeMatrices(
                txn, fromPbj(new com.hedera.hapi.node.base.Timestamp(contract.expirationSecond(), 0)), sigUsage);
    }
}
