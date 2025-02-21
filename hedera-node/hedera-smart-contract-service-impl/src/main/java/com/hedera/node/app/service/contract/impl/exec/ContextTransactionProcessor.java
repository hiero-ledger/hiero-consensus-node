// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.streams.ContractBytecode;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.tracers.AddOnEvmActionTracer;
import com.hedera.node.app.service.contract.impl.exec.tracers.EvmActionTracer;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.hevm.HydratedEthTxData;
import com.hedera.node.app.service.contract.impl.infra.HevmTransactionFactory;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import javax.inject.Inject;
import org.hyperledger.besu.evm.tracing.OperationTracer;

/**
 * A small utility that runs the
 * {@link TransactionProcessor#processTransaction(HederaEvmTransaction, HederaWorldUpdater, Supplier, HederaEvmContext, ActionSidecarContentTracer, Configuration)}
 * call implied by the in-scope {@link HandleContext}.
 */
@TransactionScope
public class ContextTransactionProcessor implements Callable<CallOutcome> {
    private final HandleContext context;
    private final ContractsConfig contractsConfig;
    private final Configuration configuration;
    private final HederaEvmContext hederaEvmContext;

    @Nullable
    private final HydratedEthTxData hydratedEthTxData;

    @Nullable
    private final Supplier<List<OperationTracer>> addOnTracers;

    private final TransactionProcessor processor;
    private final EvmActionTracer evmActionTracer;
    private final RootProxyWorldUpdater rootProxyWorldUpdater;
    private final HevmTransactionFactory hevmTransactionFactory;
    private final Supplier<HederaWorldUpdater> feesOnlyUpdater;
    private final CustomGasCharging gasCharging;

    /**
     * @param hydratedEthTxData the hydrated Ethereum transaction data
     * @param context the context of the transaction
     * @param contractsConfig the contracts configuration to use
     * @param configuration the configuration to use
     * @param hederaEvmContext the hedera EVM context
     * @param addOnTracers all operation tracer callbacks
     * @param evmActionTracer the EVM action tracer
     * @param worldUpdater the world updater for the transaction
     * @param hevmTransactionFactory the factory for EVM transaction
     * @param feesOnlyUpdater if base commit fails, a fees-only updater
     * @param processor a map from the version of the Hedera EVM to the transaction processor
     * @param customGasCharging the Hedera gas charging logic
     */
    @Inject
    public ContextTransactionProcessor(
            @Nullable final HydratedEthTxData hydratedEthTxData,
            @NonNull final HandleContext context,
            @NonNull final ContractsConfig contractsConfig,
            @NonNull final Configuration configuration,
            @NonNull final HederaEvmContext hederaEvmContext,
            @Nullable Supplier<List<OperationTracer>> addOnTracers,
            @NonNull final EvmActionTracer evmActionTracer,
            @NonNull final RootProxyWorldUpdater worldUpdater,
            @NonNull final HevmTransactionFactory hevmTransactionFactory,
            @NonNull final Supplier<HederaWorldUpdater> feesOnlyUpdater,
            @NonNull final TransactionProcessor processor,
            @NonNull final CustomGasCharging customGasCharging) {
        this.context = Objects.requireNonNull(context);
        this.hydratedEthTxData = hydratedEthTxData;
        this.addOnTracers = addOnTracers;
        this.evmActionTracer = Objects.requireNonNull(evmActionTracer);
        this.feesOnlyUpdater = Objects.requireNonNull(feesOnlyUpdater);
        this.processor = Objects.requireNonNull(processor);
        this.rootProxyWorldUpdater = Objects.requireNonNull(worldUpdater);
        this.configuration = Objects.requireNonNull(configuration);
        this.contractsConfig = Objects.requireNonNull(contractsConfig);
        this.hederaEvmContext = Objects.requireNonNull(hederaEvmContext);
        this.hevmTransactionFactory = Objects.requireNonNull(hevmTransactionFactory);
        this.gasCharging = Objects.requireNonNull(customGasCharging);
    }

    @Override
    public CallOutcome call() {
        // Ensure that if this is an EthereumTransaction, we have a valid EthTxData
        assertEthTxDataValidIfApplicable();

        // Try to translate the HAPI operation to a Hedera EVM transaction, throw HandleException on failure
        // if an exception occurs during a ContractCall, charge fees to the sender and return a CallOutcome reflecting
        // the error.
        final var hevmTransaction = safeCreateHevmTransaction();
        if (hevmTransaction.isException()) {
            return maybeChargeFeesAndReturnOutcome(
                    hevmTransaction,
                    context.body().transactionIDOrThrow().accountIDOrThrow(),
                    null,
                    contractsConfig.chargeGasOnEvmHandleException());
        }

        // Process the transaction and return its outcome
        try {
            final var tracer = addOnTracers != null
                    ? new AddOnEvmActionTracer(evmActionTracer, addOnTracers.get())
                    : evmActionTracer;
            if (hevmTransaction.isLambdaDispatch()) {
                rootProxyWorldUpdater.setLambdaContractId(requireNonNull(hevmTransaction.contractId()));
            }
            var result = processor.processTransaction(
                    hevmTransaction, rootProxyWorldUpdater, feesOnlyUpdater, hederaEvmContext, tracer, configuration);

            if (hydratedEthTxData != null) {
                final var sender = requireNonNull(rootProxyWorldUpdater.getHederaAccount(hevmTransaction.senderId()));
                result = result.withSignerNonce(sender.getNonce());
            }

            if (!result.isSuccess()) {
                chargeOnFailedEthTxn(hevmTransaction);
            }

            // For mono-service fidelity, externalize an initcode-only sidecar when a top-level creation fails
            if (!result.isSuccess() && hevmTransaction.needsInitcodeExternalizedOnFailure()) {
                final var contractBytecode = ContractBytecode.newBuilder()
                        .initcode(hevmTransaction.payload())
                        .build();
                requireNonNull(hederaEvmContext.recordBuilder()).addContractBytecode(contractBytecode, false);
            }
            return CallOutcome.fromResultsWithMaybeSidecars(
                    result.asProtoResultOf(ethTxDataIfApplicable(), rootProxyWorldUpdater), result);
        } catch (HandleException e) {
            final var sender = rootProxyWorldUpdater.getHederaAccount(hevmTransaction.senderId());
            final var senderId = sender != null ? sender.hederaId() : hevmTransaction.senderId();

            return maybeChargeFeesAndReturnOutcome(
                    hevmTransaction.withException(e),
                    senderId,
                    sender,
                    hevmTransaction.isContractCall() && contractsConfig.chargeGasOnEvmHandleException());
        }
    }

    private HederaEvmTransaction safeCreateHevmTransaction() {
        try {
            return hevmTransactionFactory.fromHapiTransaction(context.body(), context.payer());
        } catch (HandleException e) {
            // Return a HederaEvmTransaction that represents the error in order to charge fees to the sender
            return hevmTransactionFactory.fromContractTxException(context.body(), e);
        }
    }

    private CallOutcome maybeChargeFeesAndReturnOutcome(
            @NonNull final HederaEvmTransaction hevmTransaction,
            @NonNull final AccountID senderId,
            @Nullable final HederaEvmAccount sender,
            final boolean chargeGas) {
        final var status = requireNonNull(hevmTransaction.exception()).getStatus();
        if (chargeGas) {
            gasCharging.chargeGasForAbortedTransaction(
                    senderId, hederaEvmContext, rootProxyWorldUpdater, hevmTransaction);
        }

        chargeOnFailedEthTxn(hevmTransaction);
        rootProxyWorldUpdater.commit();
        ContractID recipientId = null;
        if (!INVALID_CONTRACT_ID.equals(status)) {
            recipientId = hevmTransaction.contractId();
        }

        var result = HederaEvmTransactionResult.fromAborted(senderId, recipientId, status);

        if (context.body().hasEthereumTransaction() && sender != null) {
            result = result.withSignerNonce(sender.getNonce());
        }

        return CallOutcome.fromResultsWithoutSidecars(
                result.asProtoResultOf(ethTxDataIfApplicable(), rootProxyWorldUpdater), result);
    }

    /**
     * Charges hapi fees to the relayer if an Ethereum transaction failed.
     * The charge is the canonical price of an Ethereum transaction in tinybars.
     * @param hevmTransaction the Hedera EVM transaction
     */
    private void chargeOnFailedEthTxn(@NonNull final HederaEvmTransaction hevmTransaction) {
        final var zeroHapiFeesEnabled = contractsConfig.evmEthTransactionZeroHapiFeesEnabled();
        if (hevmTransaction.isEthereumTransaction() && zeroHapiFeesEnabled) {
            final var relayerId = hevmTransaction.relayerId();

            final var fee = hederaEvmContext
                    .systemContractGasCalculator()
                    .canonicalPriceInTinycents(DispatchType.ETHEREUM_TRANSACTION);
            final var feeInTinyBars = ConversionUtils.fromTinycentsToTinybars(
                    context.exchangeRateInfo().activeRate(context.consensusNow()), fee);
            rootProxyWorldUpdater.collectFee(relayerId, feeInTinyBars);
        }
    }

    private void assertEthTxDataValidIfApplicable() {
        if (hydratedEthTxData != null && !hydratedEthTxData.isAvailable()) {
            throw new HandleException(hydratedEthTxData.status());
        }
    }

    private @Nullable EthTxData ethTxDataIfApplicable() {
        return hydratedEthTxData == null ? null : hydratedEthTxData.ethTxData();
    }
}
