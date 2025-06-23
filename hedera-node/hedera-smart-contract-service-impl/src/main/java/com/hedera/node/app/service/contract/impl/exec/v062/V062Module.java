// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v062;

import static com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule.INITIAL_CONTRACT_NONCE;
import static com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule.REQUIRE_CODE_DEPOSIT_TO_SUCCEED;
import static org.hyperledger.besu.evm.MainnetEVMs.registerCancunOperations;
import static org.hyperledger.besu.evm.operation.SStoreOperation.FRONTIER_MINIMUM;

import com.hedera.node.app.service.contract.impl.annotations.CustomOps;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV062;
import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.FrameRunner;
import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomBalanceOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomCallCodeOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomCallOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomChainIdOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomCreate2Operation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomCreateOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomDelegateCallOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomExtCodeCopyOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomExtCodeHashOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomExtCodeSizeOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomLogOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomPrevRandaoOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomSLoadOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomSStoreOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomSelfDestructOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomSelfDestructOperation.UseEIP6780Semantics;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomStaticCallOperation;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomContractCreationProcessor;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameBuilder;
import com.hedera.node.app.service.contract.impl.exec.v038.Version038AddressChecks;
import com.hedera.node.app.service.contract.impl.hevm.HederaEVM;
import com.hedera.node.app.service.contract.impl.hevm.HederaOpsDuration;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Singleton;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.contractvalidation.ContractValidationRule;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.operation.SLoadOperation;
import org.hyperledger.besu.evm.operation.SStoreOperation;
import org.hyperledger.besu.evm.precompile.KZGPointEvalPrecompiledContract;
import org.hyperledger.besu.evm.precompile.MainnetPrecompiledContracts;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;

/**
 * Provides the Services 0.62 EVM implementation, which consists of a new HederaEVM sub-class of the Besu EVM
 * class that allows for tracking of gas usage based on an alternate gas schedule.
 */
@Module
public interface V062Module {

    /** Initialization that must be performed when module is created - typically stuff from Besu's
     * `BesuCommand.run()`
     */
    private static void oneTimeEVMModuleInitialization() {
        // KZG precompile needs to have a native library loaded, "trusted state" loaded
        KZGPointEvalPrecompiledContract.init();
    }

    @Provides
    @Singleton
    @ServicesV062
    static TransactionProcessor provideTransactionProcessor(
            @NonNull final FrameBuilder frameBuilder,
            @NonNull final FrameRunner frameRunner,
            @ServicesV062 @NonNull final CustomMessageCallProcessor messageCallProcessor,
            @ServicesV062 @NonNull final ContractCreationProcessor contractCreationProcessor,
            @NonNull final CustomGasCharging gasCharging,
            @ServicesV062 @NonNull final FeatureFlags featureFlags) {
        return new TransactionProcessor(
                frameBuilder, frameRunner, gasCharging, messageCallProcessor, contractCreationProcessor, featureFlags);
    }

    @Provides
    @Singleton
    @ServicesV062
    static ContractCreationProcessor provideContractCreationProcessor(
            @ServicesV062 @NonNull final EVM evm,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final Set<ContractValidationRule> validationRules) {
        return new CustomContractCreationProcessor(
                evm,
                gasCalculator,
                REQUIRE_CODE_DEPOSIT_TO_SUCCEED,
                List.copyOf(validationRules),
                INITIAL_CONTRACT_NONCE);
    }

    @Provides
    @Singleton
    @ServicesV062
    static CustomMessageCallProcessor provideMessageCallProcessor(
            @ServicesV062 @NonNull final EVM evm,
            @ServicesV062 @NonNull final FeatureFlags featureFlags,
            @ServicesV062 @NonNull final AddressChecks addressChecks,
            @ServicesV062 @NonNull final PrecompileContractRegistry registry,
            @NonNull final Map<Address, HederaSystemContract> systemContracts,
            @NonNull final HederaOpsDuration hederaOpsDuration) {
        return new CustomMessageCallProcessor(
                evm, featureFlags, registry, addressChecks, systemContracts, hederaOpsDuration);
    }

    @Provides
    @Singleton
    @ServicesV062
    static EVM provideEVM(
            @ServicesV062 @NonNull final Set<Operation> customOperations,
            @NonNull final EvmConfiguration evmConfiguration,
            @NonNull final GasCalculator gasCalculator,
            @CustomOps @NonNull final Set<Operation> customOps,
            @NonNull final HederaOpsDuration hederaOpsDuration) {

        oneTimeEVMModuleInitialization();

        final var operationRegistry = new OperationRegistry();
        registerCancunOperations(operationRegistry, gasCalculator, BigInteger.ZERO);
        customOperations.forEach(operationRegistry::put);
        customOps.forEach(operationRegistry::put);
        // Create a return a custom HederaEVM instance
        return new HederaEVM(
                operationRegistry, gasCalculator, evmConfiguration, EvmSpecVersion.CANCUN, hederaOpsDuration);
    }

    @Provides
    @Singleton
    @ServicesV062
    static PrecompileContractRegistry providePrecompileContractRegistry(@NonNull final GasCalculator gasCalculator) {
        final var precompileContractRegistry = new PrecompileContractRegistry();
        MainnetPrecompiledContracts.populateForCancun(precompileContractRegistry, gasCalculator);
        return precompileContractRegistry;
    }

    @Binds
    @ServicesV062
    FeatureFlags bindFeatureFlags(Version062FeatureFlags featureFlags);

    @Binds
    @ServicesV062
    AddressChecks bindAddressChecks(Version038AddressChecks addressChecks);

    @Provides
    @IntoSet
    @ServicesV062
    static Operation provideBalanceOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV062 @NonNull final AddressChecks addressChecks,
            @ServicesV062 @NonNull final FeatureFlags featureFlags) {
        return new CustomBalanceOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @IntoSet
    @ServicesV062
    static Operation provideDelegateCallOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV062 @NonNull final AddressChecks addressChecks,
            @ServicesV062 @NonNull final FeatureFlags featureFlags) {
        return new CustomDelegateCallOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @IntoSet
    @ServicesV062
    static Operation provideCallCodeOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV062 @NonNull final AddressChecks addressChecks,
            @ServicesV062 @NonNull final FeatureFlags featureFlags) {
        return new CustomCallCodeOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @IntoSet
    @ServicesV062
    static Operation provideStaticCallOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV062 @NonNull final AddressChecks addressChecks,
            @ServicesV062 @NonNull final FeatureFlags featureFlags) {
        return new CustomStaticCallOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @IntoSet
    @ServicesV062
    static Operation provideCallOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV062 @NonNull final FeatureFlags featureFlags,
            @ServicesV062 @NonNull final AddressChecks addressChecks) {
        return new CustomCallOperation(featureFlags, gasCalculator, addressChecks);
    }

    @Provides
    @IntoSet
    @ServicesV062
    static Operation provideChainIdOperation(@NonNull final GasCalculator gasCalculator) {
        return new CustomChainIdOperation(gasCalculator);
    }

    @Provides
    @IntoSet
    @ServicesV062
    static Operation provideCreateOperation(@NonNull final GasCalculator gasCalculator) {
        return new CustomCreateOperation(gasCalculator);
    }

    @Provides
    @IntoSet
    @ServicesV062
    static Operation provideCreate2Operation(
            @NonNull final GasCalculator gasCalculator, @ServicesV062 @NonNull final FeatureFlags featureFlags) {
        return new CustomCreate2Operation(gasCalculator, featureFlags);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV062
    static Operation provideLog0Operation(@NonNull final GasCalculator gasCalculator) {
        return new CustomLogOperation(0, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV062
    static Operation provideLog1Operation(final GasCalculator gasCalculator) {
        return new CustomLogOperation(1, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV062
    static Operation provideLog2Operation(final GasCalculator gasCalculator) {
        return new CustomLogOperation(2, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV062
    static Operation provideLog3Operation(final GasCalculator gasCalculator) {
        return new CustomLogOperation(3, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV062
    static Operation provideLog4Operation(final GasCalculator gasCalculator) {
        return new CustomLogOperation(4, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV062
    static Operation provideExtCodeHashOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV062 @NonNull final AddressChecks addressChecks,
            @ServicesV062 @NonNull final FeatureFlags featureFlags) {
        return new CustomExtCodeHashOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV062
    static Operation provideExtCodeSizeOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV062 @NonNull final AddressChecks addressChecks,
            @ServicesV062 @NonNull final FeatureFlags featureFlags) {
        return new CustomExtCodeSizeOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV062
    static Operation provideExtCodeCopyOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV062 @NonNull final AddressChecks addressChecks,
            @ServicesV062 @NonNull final FeatureFlags featureFlags) {
        return new CustomExtCodeCopyOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV062
    static Operation providePrevRandaoOperation(@NonNull final GasCalculator gasCalculator) {
        return new CustomPrevRandaoOperation(gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV062
    static Operation provideSelfDestructOperation(
            @NonNull final GasCalculator gasCalculator, @ServicesV062 @NonNull final AddressChecks addressChecks) {
        // Here we adopt EIP-6780 semantics, for SELFDESTRUCT, for the first time
        return new CustomSelfDestructOperation(gasCalculator, addressChecks, UseEIP6780Semantics.YES);
    }

    @Provides
    @IntoSet
    @ServicesV062
    static Operation provideSLoadOperation(
            @NonNull final GasCalculator gasCalculator, @ServicesV062 @NonNull final FeatureFlags featureFlags) {
        return new CustomSLoadOperation(featureFlags, new SLoadOperation(gasCalculator));
    }

    @Provides
    @IntoSet
    @ServicesV062
    static Operation provideSStoreOperation(
            @NonNull final GasCalculator gasCalculator, @ServicesV062 @NonNull final FeatureFlags featureFlags) {
        return new CustomSStoreOperation(featureFlags, new SStoreOperation(gasCalculator, FRONTIER_MINIMUM));
    }
}
