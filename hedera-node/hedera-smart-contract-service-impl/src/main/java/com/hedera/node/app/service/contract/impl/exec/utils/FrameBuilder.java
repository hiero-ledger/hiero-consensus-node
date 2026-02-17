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
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.HOOK_OWNER_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.INVALID_ADDRESS_CONTEXT_VARIABLE;
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
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.code.CodeV0;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.worldstate.CodeDelegationHelper;

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
    private static final Logger LOG = LogManager.getLogger(FrameBuilder.class);

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
     * @param initialGas the initial gas amount available for execution
     * @param codeFactory the factory used to construct an instance of {@link org.hyperledger.besu.evm.Code}
     * *                    from raw bytecode.
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
            final long initialGas,
            @NonNull final CodeFactory codeFactory,
            @NonNull final GasCalculator gasCalculator) {
        final var value = transaction.weiValue();
        final var ledgerConfig = config.getConfigData(LedgerConfig.class);
        final var nominalCoinbase = asLongZeroAddress(ledgerConfig.fundingAccount());
        final var contextVariables =
                contextVariablesFrom(config, opsDurationCounter, context, transaction.hookOwnerAddress(worldUpdater));
        final var builder = MessageFrame.builder()
                .maxStackSize(MAX_STACK_SIZE)
                .worldUpdater(worldUpdater.updater())
                .initialGas(initialGas)
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
            return finishedAsCreate(to, builder, transaction, codeFactory);
        } else {
            return finishedAsCall(
                    to,
                    worldUpdater,
                    builder,
                    transaction,
                    featureFlags,
                    config.getConfigData(ContractsConfig.class),
                    codeFactory,
                    gasCalculator);
        }
    }

    private Map<String, Object> contextVariablesFrom(
            @NonNull final Configuration config,
            @NonNull final OpsDurationCounter opsDurationCounter,
            @NonNull final HederaEvmContext context,
            @Nullable final Address hookOwnerAddress) {
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
        if (hookOwnerAddress != null) {
            contextEntries.put(HOOK_OWNER_ADDRESS, hookOwnerAddress);
        }
        contextEntries.put(INVALID_ADDRESS_CONTEXT_VARIABLE, new InvalidAddressContext());
        return contextEntries;
    }

    private MessageFrame finishedAsCreate(
            @NonNull final Address to,
            @NonNull final MessageFrame.Builder builder,
            @NonNull final HederaEvmTransaction transaction,
            final CodeFactory codeFactory) {
        LOG.warn("XX FrameBuilder.finishedAsCreate");
        return builder.type(MessageFrame.Type.CONTRACT_CREATION)
                .address(to)
                .contract(to)
                .inputData(Bytes.EMPTY)
                .code(codeFactory.createCode(transaction.evmPayload(), false))
                .build();
    }

    private MessageFrame finishedAsCall(
            @NonNull final Address to,
            @NonNull final HederaWorldUpdater worldUpdater,
            @NonNull final MessageFrame.Builder builder,
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final FeatureFlags featureFlags,
            @NonNull final ContractsConfig config,
            @NonNull final CodeFactory codeFactory,
            @NonNull final GasCalculator gasCalculator) {
        LOG.warn("XX FrameBuilder.finishedAsCall");

        final var contractId = transaction.contractIdOrThrow();
        LOG.warn("XX FrameBuilder.finishedAsCall contractId = {}", contractId);

        final var contractMustBePresent = contractMustBePresent(config, featureFlags, contractId);

        // Handle deleted contracts
        if (contractDeleted(worldUpdater, contractId)) {
            LOG.warn("XX FrameBuilder.finishedAsCall contract deleted = true");

            // if the contract has been deleted, throw an exception
            // unless the transaction permits missing contract byte code
            validateTrue(!contractMustBePresent || transaction.permitsMissingContract(), INVALID_ETHEREUM_TRANSACTION);
            return builder.type(MessageFrame.Type.MESSAGE_CALL)
                    .address(to)
                    .contract(to)
                    .inputData(transaction.evmPayload())
                    .code(CodeV0.EMPTY_CODE)
                    .build();
        }

        final var account = worldUpdater.getHederaAccount(contractId);
        LOG.warn("XX FrameBuilder.finishedAsCall account  = {}", account);
        LOG.warn(
                "XX FrameBuilder.finishedAsCall account clz = {}",
                account.getClass().getName());

        final Code code;
        if (account != null) {
            LOG.warn("XX FrameBuilder.finishedAsCall account is not null {}", account.getAddress());

            // Hedera account for contract is present, get the byte code
            final var accountCode = account.getCode();

            final var eligibleForCodeDelegation =
                    account.isRegularAccount() || account.isTokenFacade() || account.isScheduleTxnFacade();
            LOG.warn(
                    "XX FrameBuilder.finishedAsCall eligible? {} ; {} {} {} ; has cd? {}",
                    eligibleForCodeDelegation,
                    account.isRegularAccount(),
                    account.isTokenFacade(),
                    account.isScheduleTxnFacade(),
                    CodeDelegationHelper.hasCodeDelegation(accountCode));

            if (CodeDelegationHelper.hasCodeDelegation(accountCode) && eligibleForCodeDelegation) {
                // Resolve the target account of the delegation and use its code
                final var targetAddress =
                        Address.wrap(accountCode.slice(CodeDelegationHelper.CODE_DELEGATION_PREFIX.size()));
                LOG.warn("XX FrameBuilder.finishedAsCall delegating to {}", targetAddress);

                final Account targetAccount = worldUpdater.getHederaAccount(targetAddress);
                if (targetAccount == null || gasCalculator.isPrecompile(targetAddress)) {
                    LOG.warn("XX FrameBuilder.finishedAsCall delegating to {} (code is empty!)", targetAddress);

                    code = CodeV0.EMPTY_CODE;
                } else {
                    LOG.warn("XX FrameBuilder.finishedAsCall delegating to {} (code non empty)", targetAddress);
                    code = codeFactory.createCode(targetAccount.getCode());
                }
            } else {
                LOG.warn("XX FrameBuilder.finishedAsCall NO DELEGATION");

                // No delegation, so try to resolve the code of the smart contract.
                if (!accountCode.isEmpty()) {
                    // A non-delegation code is there (it must be a regular smart contract), so just use it.
                    code = codeFactory.createCode(accountCode);
                } else {
                    // The code is empty.
                    // First validate if this is allowed, and if so, proceed.
                    validateTrue(emptyCodePossiblyAllowed(contractMustBePresent, transaction), INVALID_CONTRACT_ID);
                    code = CodeV0.EMPTY_CODE;
                }
            }
        } else {
            LOG.warn("XX FrameBuilder.finishedAsCall account is null");

            // The target account doesn't exist. Verify that it's allowed, and if so, proceed with empty code.

            // Only do this check if the contract must be present
            if (contractMustBePresent) {
                validateTrue(transaction.permitsMissingContract(), INVALID_ETHEREUM_TRANSACTION);
            }
            code = CodeV0.EMPTY_CODE;
        }

        // TODO(AccessLists): Add EIP-7702 addresses to access list (see `accessListWarmUpAddresses` in besu)
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
