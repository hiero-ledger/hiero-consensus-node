// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.FEE_SCHEDULE_UNITS_PER_TINYCENT;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.selfManagedCustomizedCreation;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.THREE_MONTHS_IN_SECONDS;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthAccountCreationFromHapi;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthContractCreationForExternalization;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthContractCreationFromParent;
import static com.hedera.node.app.spi.workflows.DispatchOptions.stepDispatch;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.SUPPRESSING_SIGNED_TX_CUSTOMIZER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.signedTxWith;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.utils.PendingCreationMetadata;
import com.hedera.node.app.service.contract.impl.exec.utils.PendingCreationMetadataRef;
import com.hedera.node.app.service.contract.impl.records.ContractCreateStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.state.WritableContractStateStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.ContractChangeSummary;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.throttle.ThrottleAdviser;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.UncheckedParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.hyperledger.besu.datatypes.Address;

/**
 * A fully mutable {@link HederaOperations} implementation based on a {@link HandleContext}.
 */
@TransactionScope
public class HandleHederaOperations implements HederaOperations {
    public static final Bytes ZERO_ENTROPY = Bytes.fromHex(
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000");
    private static final CryptoCreateTransactionBody.Builder CREATE_TXN_BODY_BUILDER =
            CryptoCreateTransactionBody.newBuilder()
                    .initialBalance(0)
                    .maxAutomaticTokenAssociations(0)
                    .autoRenewPeriod(Duration.newBuilder().seconds(THREE_MONTHS_IN_SECONDS))
                    .key(IMMUTABILITY_SENTINEL_KEY);

    private final TinybarValues tinybarValues;
    private final ContractsConfig contractsConfig;
    private final SystemContractGasCalculator gasCalculator;
    private final HederaConfig hederaConfig;
    private final HandleContext context;
    private final HederaFunctionality functionality;
    private final PendingCreationMetadataRef pendingCreationMetadataRef;
    private final AccountsConfig accountsConfig;
    private final EntityIdFactory entityIdFactory;
    private final List<GasChargingEvent> gasChargingEvents = new ArrayList<>(1);
    private final ContractMetrics contractMetrics;

    /**
     * The types of events that occur when charging gas.
     */
    private enum GasChargingAction {
        /**
         * An account is charged for gas.
         */
        CHARGE,
        /**
         * An account is refunded for unused gas.
         */
        REFUND,
    }

    /**
     * An event that occurs when charging gas.
     * @param action the action that occurred
     * @param accountId the account that was charged or refunded
     * @param amount the amount of gas charged or refunded
     * @param withNonceIncrement whether the account's nonce was incremented
     */
    private record GasChargingEvent(
            GasChargingAction action, AccountID accountId, long amount, boolean withNonceIncrement) {}

    @Inject
    public HandleHederaOperations(
            @NonNull final ContractsConfig contractsConfig,
            @NonNull final HandleContext context,
            @NonNull final TinybarValues tinybarValues,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaConfig hederaConfig,
            @NonNull final HederaFunctionality functionality,
            @NonNull final PendingCreationMetadataRef pendingCreationMetadataRef,
            @NonNull final AccountsConfig accountsConfig,
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final ContractMetrics contractMetrics) {
        this.contractsConfig = requireNonNull(contractsConfig);
        this.context = requireNonNull(context);
        this.tinybarValues = requireNonNull(tinybarValues);
        this.gasCalculator = requireNonNull(gasCalculator);
        this.hederaConfig = requireNonNull(hederaConfig);
        this.functionality = requireNonNull(functionality);
        this.pendingCreationMetadataRef = requireNonNull(pendingCreationMetadataRef);
        this.accountsConfig = requireNonNull(accountsConfig);
        this.entityIdFactory = requireNonNull(entityIdFactory);
        this.contractMetrics = requireNonNull(contractMetrics);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull HandleHederaOperations begin() {
        context.savepointStack().createSavepoint();
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void commit() {
        context.savepointStack().commit();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void revert() {
        context.savepointStack().rollback();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ContractStateStore getStore() {
        return context.storeFactory().writableStore(WritableContractStateStore.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long peekNextEntityNumber() {
        return context.entityNumGenerator().peekAtNewEntityNum();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long useNextEntityNumber() {
        return context.entityNumGenerator().newEntityNum();
    }

    @Override
    public long contractCreationLimit() {
        return contractsConfig.maxNumber();
    }

    @Override
    public long accountCreationLimit() {
        return accountsConfig.maxNumber();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Bytes entropy() {
        final var entropy = context.blockRecordInfo().prngSeed();
        return (entropy == null || entropy.equals(Bytes.EMPTY)) ? ZERO_ENTROPY : entropy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long lazyCreationCostInGas(@NonNull final Address recipient) {
        final var payerId = context.payer();
        // Calculate gas for a CryptoCreateTransactionBody with an alias address
        final var synthCreation = TransactionBody.newBuilder()
                .cryptoCreateAccount(CREATE_TXN_BODY_BUILDER.alias(tuweniToPbjBytes(recipient)))
                .build();
        final var createFee = gasCalculator.feeCalculatorPriceInTinyBars(synthCreation, payerId);

        return (createFee) * FEE_SCHEDULE_UNITS_PER_TINYCENT / gasCalculator.topLevelGasPriceInTinyBars();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long gasPriceInTinybars() {
        return tinybarValues.topLevelTinybarGasPrice();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long valueInTinybars(final long tinycents) {
        return tinybarValues.asTinybars(tinycents);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void collectHtsFee(@NonNull final AccountID payerId, final long amount) {
        requireNonNull(payerId);
        context.tryToCharge(payerId, amount);
    }

    @Override
    public void collectGasFee(@NonNull final AccountID payerId, final long amount, final boolean withNonceIncrement) {
        requireNonNull(payerId);
        context.tryToCharge(payerId, amount);
        gasChargingEvents.add(new GasChargingEvent(GasChargingAction.CHARGE, payerId, amount, withNonceIncrement));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void refundGasFee(@NonNull final AccountID payerId, final long amount) {
        requireNonNull(payerId);
        context.refundBestEffort(payerId, amount);
        gasChargingEvents.add(new GasChargingEvent(GasChargingAction.REFUND, payerId, amount, false));
    }

    @Override
    public void replayGasChargingIn(@NonNull final FeeCharging.Context feeChargingContext) {
        requireNonNull(feeChargingContext);
        final Map<AccountID, Long> netCharges = new LinkedHashMap<>();
        for (final var event : gasChargingEvents) {
            if (event.action() == GasChargingAction.CHARGE) {
                netCharges.merge(event.accountId(), event.amount(), Long::sum);
                if (event.withNonceIncrement()) {
                    final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
                    tokenServiceApi.incrementSenderNonce(event.accountId());
                }
            } else {
                netCharges.merge(event.accountId(), -event.amount(), Long::sum);
            }
        }
        netCharges.forEach((payerId, amount) -> feeChargingContext.charge(payerId, new Fees(0, amount, 0), null));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void chargeStorageRent(
            final ContractID contractID, final long amount, final boolean itemizeStoragePayments) {
        // (FUTURE) Needed before enabling contract expiry
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateStorageMetadata(
            final ContractID contractID, @NonNull final Bytes firstKey, final int netChangeInSlotsUsed) {
        requireNonNull(firstKey);
        final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
        tokenServiceApi.updateStorageMetadata(contractID, firstKey, netChangeInSlotsUsed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createContract(final long number, final long parentNumber, @Nullable final Bytes evmAddress) {
        final var accountStore = context.storeFactory().readableStore(ReadableAccountStore.class);
        final var parent = accountStore.getAccountById(entityIdFactory.newAccountId(parentNumber));
        final var impliedContractCreation =
                synthContractCreationFromParent(entityIdFactory.newContractId(number), requireNonNull(parent));
        try {
            dispatchAndMarkCreation(
                    entityIdFactory.newContractId(number),
                    synthAccountCreationFromHapi(
                            entityIdFactory.newContractId(number), evmAddress, impliedContractCreation),
                    impliedContractCreation,
                    parent.autoRenewAccountId(),
                    evmAddress,
                    ExternalizeInitcodeOnSuccess.YES);
        } catch (final HandleException e) {
            throw new ResourceExhaustedException(e.getStatus());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createContract(
            final long number, @NonNull final ContractCreateTransactionBody body, @Nullable final Bytes evmAddress) {
        requireNonNull(body);
        // Note that a EthereumTransaction with a top-level creation still needs to externalize its
        // implied ContractCreateTransactionBody (unlike ContractCreate, which evidently already does so)
        dispatchAndMarkCreation(
                entityIdFactory.newContractId(number),
                synthAccountCreationFromHapi(entityIdFactory.newContractId(number), evmAddress, body),
                functionality == HederaFunctionality.ETHEREUM_TRANSACTION
                        ? selfManagedCustomizedCreation(body, entityIdFactory.newContractId(number))
                        : null,
                body.autoRenewAccountId(),
                evmAddress,
                body.hasInitcode() ? ExternalizeInitcodeOnSuccess.NO : ExternalizeInitcodeOnSuccess.YES);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteAliasedContract(@NonNull final Bytes evmAddress) {
        requireNonNull(evmAddress);
        final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
        tokenServiceApi.deleteContract(entityIdFactory.newContractIdWithEvmAddress(evmAddress));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteUnaliasedContract(final long number) {
        final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
        tokenServiceApi.deleteContract(entityIdFactory.newContractId(number));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Long> getModifiedAccountNumbers() {
        return Collections.emptyList();
    }

    @Override
    public ContractChangeSummary summarizeContractChanges() {
        final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
        return tokenServiceApi.summarizeContractChanges();
    }

    @Override
    public long getOriginalSlotsUsed(final ContractID contractID) {
        final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
        return tokenServiceApi.originalKvUsageFor(contractID);
    }

    @Override
    public void externalizeHollowAccountMerge(@NonNull final ContractID contractId, @NonNull final Bytes evmAddress) {
        requireNonNull(contractId);
        requireNonNull(evmAddress);
        final var recordBuilder = context.savepointStack()
                .addRemovableChildRecordBuilder(ContractCreateStreamBuilder.class, CONTRACT_CREATE)
                .createdContractID(contractId)
                .createdEvmAddress(evmAddress)
                .status(SUCCESS)
                .signedTx(signedTxWith(TransactionBody.newBuilder()
                        .contractCreateInstance(synthContractCreationForExternalization(contractId))
                        .build()))
                .evmCreateTransactionResult(
                        EvmTransactionResult.newBuilder().contractId(contractId).build())
                .contractCreateResult(ContractFunctionResult.newBuilder()
                        .contractID(contractId)
                        .evmAddress(evmAddress)
                        .build());
        final var pendingCreationMetadata = new PendingCreationMetadata(recordBuilder, true);
        pendingCreationMetadataRef.set(contractId, pendingCreationMetadata);
    }

    @Override
    public ContractID shardAndRealmValidated(@NonNull final ContractID contractId) {
        return configValidated(contractId, hederaConfig);
    }

    private enum ExternalizeInitcodeOnSuccess {
        YES,
        NO
    }

    private void dispatchAndMarkCreation(
            @NonNull final ContractID contractID,
            @NonNull final CryptoCreateTransactionBody bodyToDispatch,
            @Nullable final ContractCreateTransactionBody bodyToExternalize,
            @Nullable final AccountID autoRenewAccountId,
            @Nullable final Bytes evmAddress,
            @NonNull final ExternalizeInitcodeOnSuccess externalizeInitcodeOnSuccess) {
        // Create should have conditional child record, but we only externalize this child if it's not already
        // externalized by the top-level HAPI transaction; and we "finish" the synthetic transaction by swapping
        // in the contract creation body for the dispatched crypto create body. This child transaction will not
        // be throttled at consensus.
        final var isTopLevelCreation = bodyToExternalize == null;
        final var body =
                TransactionBody.newBuilder().cryptoCreateAccount(bodyToDispatch).build();
        final var transactionCustomizer = isTopLevelCreation
                ? SUPPRESSING_SIGNED_TX_CUSTOMIZER
                : contractBodyCustomizerFor(contractID, bodyToExternalize);
        final var streamBuilder = context.dispatch(
                stepDispatch(context.payer(), body, ContractCreateStreamBuilder.class, transactionCustomizer));
        if (streamBuilder.status() != SUCCESS) {
            // The only plausible failure mode (MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED) should
            // have been pre-validated in ProxyWorldUpdater.createAccount() so this is an invariant failure
            throw new IllegalStateException("Unexpected failure creating new contract - " + streamBuilder.status());
        }
        streamBuilder.functionality(CONTRACT_CREATE);
        // If this creation runs to a successful completion, its ContractBytecode sidecar
        // goes in the top-level record or the just-created child record depending on whether
        // we are doing this on behalf of a HAPI ContractCreate call; we only include the
        // initcode in the bytecode sidecar if it's not already externalized via a body
        final var pendingCreationMetadata = new PendingCreationMetadata(
                isTopLevelCreation
                        ? context.savepointStack().getBaseBuilder(ContractOperationStreamBuilder.class)
                        : streamBuilder,
                externalizeInitcodeOnSuccess == ExternalizeInitcodeOnSuccess.YES);
        final var newContractId = contractID.copyBuilder().build();
        pendingCreationMetadataRef.set(newContractId, pendingCreationMetadata);
        streamBuilder
                .createdContractID(newContractId)
                .createdEvmAddress(evmAddress)
                .evmCreateTransactionResult(EvmTransactionResult.newBuilder()
                        .contractId(newContractId)
                        .build())
                .contractCreateResult(ContractFunctionResult.newBuilder()
                        .contractID(newContractId)
                        .evmAddress(evmAddress)
                        .build());
        // Mark the created account as a contract with the given auto-renew account id
        final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
        final var accountId = AccountID.newBuilder()
                .shardNum(contractID.shardNum())
                .realmNum(contractID.realmNum())
                .accountNum(contractID.contractNumOrThrow())
                .build();
        tokenServiceApi.markAsContract(accountId, autoRenewAccountId);
    }

    private StreamBuilder.SignedTxCustomizer contractBodyCustomizerFor(
            @NonNull final ContractID contractID, @NonNull final ContractCreateTransactionBody op) {
        return signedTx -> {
            try {
                final var dispatchedBody = TransactionBody.PROTOBUF.parseStrict(
                        signedTx.bodyBytes().toReadableSequentialData());
                if (!dispatchedBody.hasCryptoCreateAccount()) {
                    throw new IllegalArgumentException(
                            "Dispatched transaction body was not a crypto create" + dispatchedBody);
                }
                final var standardizedOp = standardized(contractID, op);
                return signedTxWith(dispatchedBody
                        .copyBuilder()
                        .contractCreateInstance(standardizedOp)
                        .build());
            } catch (ParseException e) {
                // Should be impossible
                throw new UncheckedParseException(e);
            }
        };
    }

    /**
     * Standardizes the given {@link ContractCreateTransactionBody} to not include initcode, gas, and initial balance
     * values as these parameters are only set on the top-level HAPI transactions.
     *
     * @param contractID the contractID of the created contract
     * @param op the operation to standardize
     * @return the standardized operation
     */
    private ContractCreateTransactionBody standardized(
            @NonNull final ContractID contractID, @NonNull final ContractCreateTransactionBody op) {
        if (needsStandardization(op)) {
            Key newAdminKey = op.adminKey();
            // If the admin key is not set, we set it to the contract itself for externalization
            // Typically, the op will not have an adminkey if the transaction's HederaFunctionality is
            // ETHEREUM_TRANSACTION
            if (!op.hasAdminKey()) {
                newAdminKey = Key.newBuilder()
                        .contractID(contractID.copyBuilder().build())
                        .build();
            }
            return new ContractCreateTransactionBody(
                    com.hedera.hapi.node.contract.codec.ContractCreateTransactionBodyProtoCodec.INITCODE_SOURCE_UNSET,
                    newAdminKey,
                    0L,
                    0L,
                    op.proxyAccountID(),
                    op.autoRenewPeriod(),
                    op.constructorParameters(),
                    op.shardID(),
                    op.realmID(),
                    op.newRealmAdminKey(),
                    op.memo(),
                    op.maxAutomaticTokenAssociations(),
                    op.autoRenewAccountId(),
                    op.stakedId(),
                    op.declineReward());
        } else {
            return op;
        }
    }

    private boolean needsStandardization(@NonNull final ContractCreateTransactionBody op) {
        return op.hasInitcode() || op.gas() > 0L || op.initialBalance() > 0L;
    }

    @Override
    @Nullable
    public ThrottleAdviser getThrottleAdviser() {
        return context.throttleAdviser();
    }

    @Override
    public ContractMetrics contractMetrics() {
        return contractMetrics;
    }
}
