// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.trace.ContractInitcode;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.streams.ContractBytecode;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.tracers.AddOnEvmActionTracer;
import com.hedera.node.app.service.contract.impl.exec.tracers.EvmActionTracer;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.hevm.HederaOpsDuration;
import com.hedera.node.app.service.contract.impl.hevm.HydratedEthTxData;
import com.hedera.node.app.service.contract.impl.infra.HevmTransactionFactory;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.node.config.data.OpsDurationConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import javax.inject.Inject;
import org.hyperledger.besu.evm.tracing.OperationTracer;

/**
 * A small utility that runs the * {@code #processTransaction()} call implied by the
 * in-scope {@link HandleContext}.
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
    private final CustomGasCharging gasCharging;
    private final HederaOpsDuration hederaOpsDuration;

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
            @NonNull final TransactionProcessor processor,
            @NonNull final CustomGasCharging customGasCharging,
            @NonNull final HederaOpsDuration hederaOpsDuration) {
        this.context = requireNonNull(context);
        this.hydratedEthTxData = hydratedEthTxData;
        this.addOnTracers = addOnTracers;
        this.evmActionTracer = requireNonNull(evmActionTracer);
        this.processor = requireNonNull(processor);
        this.rootProxyWorldUpdater = requireNonNull(worldUpdater);
        this.configuration = requireNonNull(configuration);
        this.contractsConfig = requireNonNull(contractsConfig);
        this.hederaEvmContext = requireNonNull(hederaEvmContext);
        this.hevmTransactionFactory = requireNonNull(hevmTransactionFactory);
        this.gasCharging = requireNonNull(customGasCharging);
        this.hederaOpsDuration = requireNonNull(hederaOpsDuration);
    }

    @Override
    public CallOutcome call() {
        // Apply the latest ops duration schedule from the configuration
        setOpsDurationValues();

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
            var result = processor.processTransaction(
                    hevmTransaction, rootProxyWorldUpdater, hederaEvmContext, tracer, configuration);

            if (hydratedEthTxData != null) {
                final var sender = requireNonNull(rootProxyWorldUpdater.getHederaAccount(hevmTransaction.senderId()));
                result = result.withSignerNonce(sender.getNonce());
            }

            // For mono-service fidelity, externalize an initcode-only sidecar when a top-level creation fails
            if (!result.isSuccess() && hevmTransaction.needsInitcodeExternalizedOnFailure()) {
                // (FUTURE) Remove after switching to block stream
                final var contractBytecode = ContractBytecode.newBuilder()
                        .initcode(hevmTransaction.payload())
                        .build();
                requireNonNull(hederaEvmContext.streamBuilder()).addContractBytecode(contractBytecode, false);
                // No-op for the RecordStreamBuilder
                final var initcode = ContractInitcode.newBuilder()
                        .failedInitcode(hevmTransaction.payload())
                        .build();
                requireNonNull(hederaEvmContext.streamBuilder()).addInitcode(initcode);
            }
            final var callData = (hydratedEthTxData != null && hydratedEthTxData.ethTxData() != null)
                    ? Bytes.wrap(hydratedEthTxData.ethTxData().callData())
                    : null;
            return CallOutcome.fromResultsWithMaybeSidecars(
                    result.asProtoResultOf(ethTxDataIfApplicable(), rootProxyWorldUpdater, callData),
                    result.asEvmTxResultOf(ethTxDataIfApplicable(), callData),
                    result.isSuccess() ? rootProxyWorldUpdater.getUpdatedContractNonces() : null,
                    result.isSuccess() ? rootProxyWorldUpdater.getCreatedContractIds() : null,
                    result.isSuccess() ? result.evmAddressIfCreatedIn(rootProxyWorldUpdater) : null,
                    result);
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
            final var hevmTransaction = hevmTransactionFactory.fromHapiTransaction(context.body(), context.payer());
            validatePayloadLength(hevmTransaction);
            return hevmTransaction;
        } catch (HandleException e) {
            // Return a HederaEvmTransaction that represents the error in order to charge fees to the sender
            return hevmTransactionFactory.fromContractTxException(context.body(), e);
        }
    }

    private void validatePayloadLength(HederaEvmTransaction hevmTransaction) {
        final var maxJumboEthereumCallDataSize =
                configuration.getConfigData(JumboTransactionsConfig.class).ethereumMaxCallDataSize();

        if (hevmTransaction.payload().length() > maxJumboEthereumCallDataSize) {
            throw new HandleException(TRANSACTION_OVERSIZE);
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

        rootProxyWorldUpdater.commit();
        ContractID recipientId = null;
        if (!INVALID_CONTRACT_ID.equals(status)) {
            recipientId = hevmTransaction.contractId();
        }

        var result = HederaEvmTransactionResult.fromAborted(senderId, recipientId, status);

        if (context.body().hasEthereumTransaction() && sender != null) {
            result = result.withSignerNonce(sender.getNonce());
        }

        final var ethCallData = (hydratedEthTxData != null && hydratedEthTxData.ethTxData() != null)
                ? Bytes.wrap(hydratedEthTxData.ethTxData().callData())
                : null;
        return CallOutcome.fromResultsWithoutSidecars(
                result.asProtoResultOf(ethTxDataIfApplicable(), rootProxyWorldUpdater, ethCallData),
                result.asEvmTxResultOf(ethTxDataIfApplicable(), ethCallData),
                null,
                null,
                null,
                result);
    }

    private void assertEthTxDataValidIfApplicable() {
        if (hydratedEthTxData != null && !hydratedEthTxData.isAvailable()) {
            throw new HandleException(hydratedEthTxData.status());
        }
    }

    private @Nullable EthTxData ethTxDataIfApplicable() {
        return hydratedEthTxData == null ? null : hydratedEthTxData.ethTxData();
    }

    private void setOpsDurationValues() {
        final var opsDurationConfig = configuration.getConfigData(OpsDurationConfig.class);
        hederaOpsDuration.applyDurationFromConfig(requireNonNull(opsDurationConfig));
    }
}
