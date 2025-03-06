// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RECEIVING_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.gas.DispatchType.ASSOCIATE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.encodedRc;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.TransferEventLoggingUtils.logSuccessfulFungibleTransfer;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.TransferEventLoggingUtils.logSuccessfulNftTransfer;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.configOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements the "classic" HTS transfer calls, which differ from the ERC redirects in three notable ways:
 * <ol>
 *     <li>They accept the token address as an explicit parameter, instead of getting the token id
 *     via a redirect.</li>
 *     <li>They return the ordinal value of a non-successful {@link ResponseCodeEnum} instead of reverting
 *     like the ERC calls do.</li>
 *     <li>The legacy versions that don't support approvals will automatically "retry" their synthetic
 *     transaction using approvals for all non-sender debits if the initial attempt fails with
 *     {@link ResponseCodeEnum#INVALID_SIGNATURE} (which they translate to
 *     {@link ResponseCodeEnum#INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE}
 *     for backward compatibility).</li>
 * </ol>
 * But the basic pattern of constructing and dispatching a synthetic {@link CryptoTransferTransactionBody} remains.
 */
public class ClassicTransfersCall extends AbstractCall {
    private final byte[] selector;
    private final AccountID senderId;
    private final ResponseCodeEnum preemptingFailureStatus;

    @Nullable
    private final TransactionBody syntheticTransfer;

    private final Configuration configuration;

    @Nullable
    private final ApprovalSwitchHelper approvalSwitchHelper;

    private final CallStatusStandardizer callStatusStandardizer;
    private final SystemAccountCreditScreen systemAccountCreditScreen;
    private final VerificationStrategy verificationStrategy;
    private final SpecialRewardReceivers specialRewardReceivers;

    /**
     * @param gasCalculator the gas calculator for the system contract
     * @param enhancement the enhancement to be used
     * @param selector the method selector
     * @param senderId the account id of the sender
     * @param preemptingFailureStatus the response code to revert with
     * @param syntheticTransfer the body of synthetic transfer operation
     * @param configuration the configuration to use
     * @param approvalSwitchHelper the switcher between unauthorized debits to approvals in a synthetic transfer
     * @param callStatusStandardizer the standardizer of failure statuses to an HTS transfer system contract
     * @param verificationStrategy the verification strategy to use
     * @param systemAccountCreditScreen the helper to screen if a transfer tries to credit a system account
     * @param specialRewardReceivers the special reward receiver
     */
    // too many parameters
    @SuppressWarnings("java:S107")
    public ClassicTransfersCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final byte[] selector,
            @NonNull final AccountID senderId,
            @Nullable final ResponseCodeEnum preemptingFailureStatus,
            @Nullable final TransactionBody syntheticTransfer,
            @NonNull final Configuration configuration,
            @Nullable ApprovalSwitchHelper approvalSwitchHelper,
            @NonNull final CallStatusStandardizer callStatusStandardizer,
            @NonNull final VerificationStrategy verificationStrategy,
            @NonNull final SystemAccountCreditScreen systemAccountCreditScreen,
            @NonNull final SpecialRewardReceivers specialRewardReceivers) {
        super(gasCalculator, enhancement, false);
        this.selector = requireNonNull(selector);
        this.senderId = requireNonNull(senderId);
        this.preemptingFailureStatus = preemptingFailureStatus;
        this.syntheticTransfer = syntheticTransfer;
        this.configuration = requireNonNull(configuration);
        this.approvalSwitchHelper = approvalSwitchHelper;
        this.callStatusStandardizer = requireNonNull(callStatusStandardizer);
        this.systemAccountCreditScreen = systemAccountCreditScreen;
        this.verificationStrategy = requireNonNull(verificationStrategy);
        this.specialRewardReceivers = requireNonNull(specialRewardReceivers);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        if (syntheticTransfer == null) {
            return gasOnly(
                    haltResult(
                            configuration.getConfigData(ContractsConfig.class).precompileHtsDefaultGasCost()),
                    INVALID_TRANSACTION_BODY,
                    false);
        }
        // When unlimited associations are enabled, will be updated with additional charges for any auto-associations
        var gasRequirement = transferGasRequirement(syntheticTransfer, gasCalculator, enhancement, senderId);
        if (preemptingFailureStatus != null) {
            return reversionWith(preemptingFailureStatus, gasRequirement);
        }
        if (systemAccountCreditScreen.creditsToSystemAccount(syntheticTransfer.cryptoTransferOrThrow())) {
            return reversionWith(
                    gasRequirement,
                    systemContractOperations()
                            .externalizePreemptedDispatch(
                                    syntheticTransfer, INVALID_RECEIVING_NODE_ACCOUNT, CRYPTO_TRANSFER));
        }
        if (executionIsNotSupported()) {
            return haltWith(
                    gasRequirement,
                    systemContractOperations()
                            .externalizePreemptedDispatch(syntheticTransfer, NOT_SUPPORTED, CRYPTO_TRANSFER));
        }
        final var transferToDispatch = shouldRetryWithApprovals()
                ? syntheticTransfer
                        .copyBuilder()
                        .cryptoTransfer(requireNonNull(approvalSwitchHelper)
                                .switchToApprovalsAsNeededIn(
                                        syntheticTransfer.cryptoTransferOrThrow(),
                                        systemContractOperations().signatureTestWith(verificationStrategy),
                                        nativeOperations(),
                                        senderId))
                        .build()
                : syntheticTransfer;
        final var recordBuilder = systemContractOperations()
                .dispatch(transferToDispatch, verificationStrategy, senderId, ContractCallStreamBuilder.class);
        final var op = transferToDispatch.cryptoTransferOrThrow();
        if (recordBuilder.status() == SUCCESS) {
            maybeEmitErcLogsFor(op, frame);
            specialRewardReceivers.addInFrame(frame, op, recordBuilder.getAssessedCustomFees());
        } else {
            recordBuilder.status(callStatusStandardizer.codeForFailure(recordBuilder.status(), frame, op));
        }
        if (recordBuilder.getNumAutoAssociations() > 0) {
            if (configOf(frame).getConfigData(EntitiesConfig.class).unlimitedAutoAssociationsEnabled()) {
                gasRequirement +=
                        recordBuilder.getNumAutoAssociations() * gasCalculator.canonicalGasRequirement(ASSOCIATE);
            }
        }
        return completionWith(gasRequirement, recordBuilder, encodedRc(recordBuilder.status()));
    }

    /**
     * Simulates the mono-service gas calculation for a classic transfer, which is significantly complicated by our
     * current strategy for setting the minimum tinycent price based on the canonical prices of various operations.
     *
     * @param body                        the transaction body to be dispatched
     * @param systemContractGasCalculator the gas calculator to use
     * @param enhancement                 the enhancement to use
     * @param payerId                     the payer of the transaction
     * @return the gas requirement for the transaction to be dispatched
     */
    public static long transferGasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        final var op = body.cryptoTransferOrThrow();
        final var hasCustomFees = enhancement.nativeOperations().checkForCustomFees(op);
        // For fungible there are always at least two operations, so only charge half for each
        // operation
        final var baseUnitAdjustTinycentPrice = systemContractGasCalculator.canonicalPriceInTinycents(
                        hasCustomFees ? DispatchType.TRANSFER_FUNGIBLE_CUSTOM_FEES : DispatchType.TRANSFER_FUNGIBLE)
                / 2;
        // NFTs are atomic, one line can do it.
        final var baseNftTransferTinycentsPrice = systemContractGasCalculator.canonicalPriceInTinycents(
                hasCustomFees ? DispatchType.TRANSFER_NFT_CUSTOM_FEES : DispatchType.TRANSFER_NFT);
        // Hbar transfer is similar to fungible tokens so only charge half for each operation
        final var baseAdjustTinycentsPrice =
                systemContractGasCalculator.canonicalPriceInTinycents(DispatchType.TRANSFER_HBAR) / 2;
        final var baseLazyCreationPrice =
                systemContractGasCalculator.canonicalPriceInTinycents(DispatchType.CRYPTO_CREATE)
                        + systemContractGasCalculator.canonicalPriceInTinycents(DispatchType.CRYPTO_UPDATE);

        final var extantAccounts = enhancement.nativeOperations().readableAccountStore();
        final long minimumTinycentPrice = minimumTinycentPriceGiven(
                op,
                baseUnitAdjustTinycentPrice,
                baseAdjustTinycentsPrice,
                baseNftTransferTinycentsPrice,
                baseLazyCreationPrice,
                extantAccounts);
        return systemContractGasCalculator.gasRequirementWithTinycents(body, payerId, minimumTinycentPrice);
    }

    private static long minimumTinycentPriceGiven(
            @NonNull final CryptoTransferTransactionBody op,
            final long baseUnitAdjustTinyCentPrice,
            final long baseAdjustTinyCentsPrice,
            final long baseNftTransferTinyCentsPrice,
            final long baseLazyCreationPrice,
            @NonNull final ReadableAccountStore extantAccounts) {
        long minimumTinycentPrice = 0L;
        final var numTinyCentsAdjusts =
                op.transfersOrElse(TransferList.DEFAULT).accountAmounts().size();
        minimumTinycentPrice += numTinyCentsAdjusts * baseAdjustTinyCentsPrice;
        final Set<Bytes> aliasesToLazyCreate = new HashSet<>();
        for (final var tokenTransfers : op.tokenTransfers()) {
            final var unitAdjusts = tokenTransfers.transfers();
            minimumTinycentPrice += unitAdjusts.size() * baseUnitAdjustTinyCentPrice;
            for (final var unitAdjust : unitAdjusts) {
                if (unitAdjust.amount() > 0
                        && unitAdjust.accountIDOrElse(AccountID.DEFAULT).hasAlias()) {
                    final var accountId = unitAdjust.accountIDOrThrow();
                    final var alias = unitAdjust.accountIDOrThrow().aliasOrThrow();
                    final var extantAccount =
                            extantAccounts.getAccountIDByAlias(accountId.shardNum(), accountId.realmNum(), alias);
                    if (extantAccount == null) {
                        aliasesToLazyCreate.add(alias);
                    }
                }
            }
            final var nftTransfers = tokenTransfers.nftTransfers();
            minimumTinycentPrice += nftTransfers.size() * baseNftTransferTinyCentsPrice;
            for (final var nftTransfer : nftTransfers) {
                if (nftTransfer.receiverAccountIDOrElse(AccountID.DEFAULT).hasAlias()) {
                    final var receiverAccountId = nftTransfer.receiverAccountIDOrThrow();
                    final var alias = receiverAccountId.aliasOrThrow();
                    final var extantAccount = extantAccounts.getAccountIDByAlias(
                            receiverAccountId.shardNum(), receiverAccountId.realmNum(), alias);
                    if (extantAccount == null) {
                        aliasesToLazyCreate.add(alias);
                    }
                }
            }
        }
        minimumTinycentPrice += aliasesToLazyCreate.size() * baseLazyCreationPrice;
        return minimumTinycentPrice;
    }

    private boolean shouldRetryWithApprovals() {
        return approvalSwitchHelper != null;
    }

    private boolean executionIsNotSupported() {
        return Arrays.equals(selector, ClassicTransfersTranslator.CRYPTO_TRANSFER_V2.selector())
                && !configuration.getConfigData(ContractsConfig.class).precompileAtomicCryptoTransferEnabled();
    }

    private void maybeEmitErcLogsFor(
            @NonNull final CryptoTransferTransactionBody op, @NonNull final MessageFrame frame) {
        if (Arrays.equals(ClassicTransfersTranslator.TRANSFER_FROM.selector(), selector)) {
            final var fungibleTransfers = op.tokenTransfers().getFirst();
            logSuccessfulFungibleTransfer(
                    fungibleTransfers.tokenOrThrow(), fungibleTransfers.transfers(), readableAccountStore(), frame);
        } else if (Arrays.equals(ClassicTransfersTranslator.TRANSFER_NFT_FROM.selector(), selector)) {
            final var nftTransfers = op.tokenTransfers().getFirst();
            logSuccessfulNftTransfer(
                    nftTransfers.tokenOrThrow(), nftTransfers.nftTransfers().getFirst(), readableAccountStore(), frame);
        }
    }
}
