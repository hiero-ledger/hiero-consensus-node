// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ETHEREUM_TRANSACTION;
import static com.hedera.hapi.streams.SidecarType.CONTRACT_ACTION;
import static com.hedera.hapi.streams.SidecarType.CONTRACT_BYTECODE;
import static com.hedera.hapi.streams.SidecarType.CONTRACT_STATE_CHANGE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.ACTION_SIDECARS_VALIDATION_VARIABLE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.ACTION_SIDECARS_VARIABLE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.BYTECODE_SIDECARS_VARIABLE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CONFIG_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.HAPI_RECORD_BUILDER_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.OPS_DURATION_COUNTER;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.PENDING_CREATION_BUILDER_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.PROPAGATED_CALL_FAILURE_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.SYSTEM_CONTRACT_GAS_CALCULATOR_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.TINYBAR_VALUES_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.TRACKER_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.infra.StorageAccessTracker;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.code.CodeV0;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Infrastructure component that builds the initial {@link MessageFrame} instance for a transaction.
 * This includes tasks like,
 * <ol>
 *     <li>Putting the {@link Configuration} in the frame context variables.</li>
 *     <li>Setting the gas price and block values from the {@link HederaEvmContext}.</li>
 *     <li>Setting input data and code based on the message call type.</li>
 * </ol>
 */
@Singleton
public class FrameBuilder {
    private static final int MAX_STACK_SIZE = 1024;

    /**
     * Default constructor for injection.
     */
    @Inject
    public FrameBuilder() {
        // Dagger2
    }

    /**
     * Builds the initial {@link MessageFrame} instance for a transaction.
     *
     * @param transaction the transaction
     * @param worldUpdater the world updater for the transaction
     * @param context the Hedera EVM context (gas price, block values, etc.)
     * @param config the active Hedera configuration
     * @param featureFlags the feature flag currently used
     * @param from the sender of the transaction
     * @param to the recipient of the transaction
     * @param intrinsicGas the intrinsic gas cost, needed to calculate remaining gas
     * @return the initial frame
     */
    @SuppressWarnings("java:S107")
    public MessageFrame buildInitialFrameWith(
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final HederaWorldUpdater worldUpdater,
            @NonNull final HederaEvmContext context,
            @NonNull final Configuration config,
            @NonNull final OpsDurationCounter opsDurationCounter,
            @NonNull final FeatureFlags featureFlags,
            @NonNull final Address from,
            @NonNull final Address to,
            final long intrinsicGas) {
        final var value = transaction.weiValue();
        final var ledgerConfig = config.getConfigData(LedgerConfig.class);
        final var nominalCoinbase = asLongZeroAddress(ledgerConfig.fundingAccount());
        final var contextVariables = contextVariablesFrom(config, opsDurationCounter, context);
        final var builder = MessageFrame.builder()
                .maxStackSize(MAX_STACK_SIZE)
                .worldUpdater(worldUpdater.updater())
                .initialGas(transaction.gasAvailable(intrinsicGas))
                .originator(from)
                .gasPrice(Wei.of(context.gasPrice()))
                .blobGasPrice(Wei.ONE) // Per Hedera CANCUN adaptation
                .sender(from)
                .value(value)
                .apparentValue(value)
                .blockValues(context.blockValuesOf(transaction.gasLimit()))
                .completer(unused -> {})
                .isStatic(context.staticCall())
                .miningBeneficiary(nominalCoinbase)
                .blockHashLookup(context.blocks()::blockHashOf)
                .contextVariables(contextVariables);
        if (transaction.isCreate()) {
            return finishedAsCreate(to, builder, transaction);
        } else {
            return finishedAsCall(
                    to, worldUpdater, builder, transaction, featureFlags, config.getConfigData(ContractsConfig.class));
        }
    }

    private Map<String, Object> contextVariablesFrom(
            @NonNull final Configuration config,
            @NonNull final OpsDurationCounter opsDurationCounter,
            @NonNull final HederaEvmContext context) {
        final Map<String, Object> contextEntries = new HashMap<>();
        contextEntries.put(CONFIG_CONTEXT_VARIABLE, config);
        contextEntries.put(TINYBAR_VALUES_CONTEXT_VARIABLE, context.tinybarValues());
        contextEntries.put(SYSTEM_CONTRACT_GAS_CALCULATOR_CONTEXT_VARIABLE, context.systemContractGasCalculator());
        contextEntries.put(PROPAGATED_CALL_FAILURE_CONTEXT_VARIABLE, new PropagatedCallFailureRef());
        final var contractConfig = config.getConfigData(ContractsConfig.class);
        final var sidecars = contractConfig.sidecars();
        if (sidecars.contains(CONTRACT_STATE_CHANGE)) {
            contextEntries.put(TRACKER_CONTEXT_VARIABLE, new StorageAccessTracker());
        }
        if (sidecars.contains(CONTRACT_ACTION)) {
            contextEntries.put(ACTION_SIDECARS_VARIABLE, true);
            if (contractConfig.sidecarValidationEnabled()) {
                contextEntries.put(ACTION_SIDECARS_VALIDATION_VARIABLE, true);
            }
        }
        if (sidecars.contains(CONTRACT_BYTECODE)) {
            contextEntries.put(BYTECODE_SIDECARS_VARIABLE, true);
        }
        if (context.isTransaction()) {
            contextEntries.put(HAPI_RECORD_BUILDER_CONTEXT_VARIABLE, context.streamBuilder());
            contextEntries.put(
                    PENDING_CREATION_BUILDER_CONTEXT_VARIABLE, context.pendingCreationRecordBuilderReference());
        }
        contextEntries.put(OPS_DURATION_COUNTER, opsDurationCounter);
        return contextEntries;
    }

    private MessageFrame finishedAsCreate(
            @NonNull final Address to,
            @NonNull final MessageFrame.Builder builder,
            @NonNull final HederaEvmTransaction transaction) {
        return builder.type(MessageFrame.Type.CONTRACT_CREATION)
                .address(to)
                .contract(to)
                .inputData(Bytes.EMPTY)
                .code(CodeFactory.createCode(transaction.evmPayload(), 0, false))
                .build();
    }

    private MessageFrame finishedAsCall(
            @NonNull final Address to,
            @NonNull final HederaWorldUpdater worldUpdater,
            @NonNull final MessageFrame.Builder builder,
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final FeatureFlags featureFlags,
            @NonNull final ContractsConfig config) {
        Code code = CodeV0.EMPTY_CODE;
        final var contractId = transaction.contractIdOrThrow();
        final var contractMustBePresent = contractMustBePresent(config, featureFlags, contractId);

        // Handle deleted contracts
        if (contractDeleted(worldUpdater, contractId)) {
            // if the contract has been deleted, throw an exception
            // unless the transaction permits missing contract byte code
            validateTrue(!contractMustBePresent || transaction.permitsMissingContract(), INVALID_ETHEREUM_TRANSACTION);
            return builder.type(MessageFrame.Type.MESSAGE_CALL)
                    .address(to)
                    .contract(to)
                    .inputData(transaction.evmPayload())
                    .code(code)
                    .build();
        }

        final var account = worldUpdater.getHederaAccount(contractId);
        if (account != null) {
            // Hedera account for contract is present, get the byte code
            code = account.getEvmCode(Bytes.wrap(transaction.payload().toByteArray()));

            // If after getting the code, it is empty, then check if this is allowed
            if (code.equals(CodeV0.EMPTY_CODE)) {
                validateTrue(emptyCodePossiblyAllowed(contractMustBePresent, transaction), INVALID_CONTRACT_ID);
            }
        } else {
            // Only do this check if the contract must be present
            if (contractMustBePresent) {
                validateTrue(transaction.permitsMissingContract(), INVALID_ETHEREUM_TRANSACTION);
            }
        }

        return builder.type(MessageFrame.Type.MESSAGE_CALL)
                .address(to)
                .contract(to)
                .inputData(transaction.evmPayload())
                .code(code)
                .build();
    }

    private boolean contractDeleted(
            @NonNull final HederaWorldUpdater worldUpdater, @NonNull final ContractID contractId) {
        final var contract = worldUpdater
                .enhancement()
                .nativeOperations()
                .readableAccountStore()
                .getContractById(contractId);

        if (contract != null) {
            return contract.deleted();
        }

        return false;
    }

    private boolean contractMustBePresent(
            @NonNull final ContractsConfig config,
            @NonNull final FeatureFlags featureFlags,
            @NonNull final ContractID contractID) {
        final var possiblyGrandFatheredEntityNumOf = contractID.hasContractNum() ? contractID.contractNum() : null;
        return !featureFlags.isAllowCallsToNonContractAccountsEnabled(config, possiblyGrandFatheredEntityNumOf);
    }

    private boolean emptyCodePossiblyAllowed(
            final boolean contractMustBePresent, @NonNull final HederaEvmTransaction transaction) {
        // Empty code is allowed if the transaction is an Ethereum transaction or has a value or the contract does not
        // have to be present via config
        return transaction.isEthereumTransaction() || transaction.hasValue() || !contractMustBePresent;
    }
}
