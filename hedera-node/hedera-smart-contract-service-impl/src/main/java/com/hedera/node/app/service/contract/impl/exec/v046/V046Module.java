// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.v046;

import static com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule.INITIAL_CONTRACT_NONCE;
import static com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule.REQUIRE_CODE_DEPOSIT_TO_SUCCEED;
import static org.hyperledger.besu.evm.MainnetEVMs.registerShanghaiOperations;
import static org.hyperledger.besu.evm.operation.SStoreOperation.FRONTIER_MINIMUM;

import com.hedera.node.app.service.contract.impl.annotations.CustomOps;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV046;
import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.FrameRunner;
import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
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
import org.hyperledger.besu.evm.precompile.MainnetPrecompiledContracts;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;

/**
 * Provides the Services 0.46 EVM implementation, which consists of Shanghai operations and
 * Istanbul precompiles plus the Hedera gas calculator, system contracts, and operations
 * as they were configured in the 0.46 release (with treatment of calls to non-existing addresses
 * returning successful results in order to enhance EVM equivalence).
 */
@Module
public interface V046Module {
    @Provides
    @Singleton
    @ServicesV046
    static TransactionProcessor provideTransactionProcessor(
            @NonNull final FrameBuilder frameBuilder,
            @NonNull final FrameRunner frameRunner,
            @ServicesV046 @NonNull final CustomMessageCallProcessor messageCallProcessor,
            @ServicesV046 @NonNull final ContractCreationProcessor contractCreationProcessor,
            @NonNull final CustomGasCharging gasCharging,
            @ServicesV046 @NonNull final FeatureFlags featureFlags) {
        return new TransactionProcessor(
                frameBuilder, frameRunner, gasCharging, messageCallProcessor, contractCreationProcessor, featureFlags);
    }

    @Provides
    @Singleton
    @ServicesV046
    static ContractCreationProcessor provideContractCreationProcessor(
            @ServicesV046 @NonNull final EVM evm,
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
    @ServicesV046
    static CustomMessageCallProcessor provideMessageCallProcessor(
            @ServicesV046 @NonNull final EVM evm,
            @ServicesV046 @NonNull final FeatureFlags featureFlags,
            @ServicesV046 @NonNull final AddressChecks addressChecks,
            @ServicesV046 @NonNull final PrecompileContractRegistry registry,
            @NonNull final Map<Address, HederaSystemContract> systemContracts,
            @NonNull final ContractMetrics contractMetrics) {
        return new CustomMessageCallProcessor(
                evm, featureFlags, registry, addressChecks, systemContracts, contractMetrics);
    }

    @Provides
    @Singleton
    @ServicesV046
    static EVM provideEVM(
            @ServicesV046 @NonNull final Set<Operation> customOperations,
            @NonNull final EvmConfiguration evmConfiguration,
            @NonNull final GasCalculator gasCalculator,
            @CustomOps @NonNull final Set<Operation> customOps) {
        // Use Shanghai EVM with 0.46 custom operations and 0x00 chain id (set at runtime)
        final var operationRegistry = new OperationRegistry();
        registerShanghaiOperations(operationRegistry, gasCalculator, BigInteger.ZERO);
        customOperations.forEach(operationRegistry::put);
        customOps.forEach(operationRegistry::put);
        return new EVM(operationRegistry, gasCalculator, evmConfiguration, EvmSpecVersion.SHANGHAI);
    }

    @Provides
    @Singleton
    @ServicesV046
    static PrecompileContractRegistry providePrecompileContractRegistry(@NonNull final GasCalculator gasCalculator) {
        final var precompileContractRegistry = new PrecompileContractRegistry();
        MainnetPrecompiledContracts.populateForIstanbul(precompileContractRegistry, gasCalculator);
        return precompileContractRegistry;
    }

    @Binds
    @ServicesV046
    FeatureFlags bindFeatureFlags(Version046FeatureFlags featureFlags);

    @Binds
    @ServicesV046
    AddressChecks bindAddressChecks(Version038AddressChecks addressChecks);

    @Provides
    @IntoSet
    @ServicesV046
    static Operation provideBalanceOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV046 @NonNull final AddressChecks addressChecks,
            @ServicesV046 @NonNull final FeatureFlags featureFlags) {
        return new CustomBalanceOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @IntoSet
    @ServicesV046
    static Operation provideDelegateCallOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV046 @NonNull final AddressChecks addressChecks,
            @ServicesV046 @NonNull final FeatureFlags featureFlags) {
        return new CustomDelegateCallOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @IntoSet
    @ServicesV046
    static Operation provideCallCodeOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV046 @NonNull final AddressChecks addressChecks,
            @ServicesV046 @NonNull final FeatureFlags featureFlags) {
        return new CustomCallCodeOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @IntoSet
    @ServicesV046
    static Operation provideStaticCallOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV046 @NonNull final AddressChecks addressChecks,
            @ServicesV046 @NonNull final FeatureFlags featureFlags) {
        return new CustomStaticCallOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @IntoSet
    @ServicesV046
    static Operation provideCallOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV046 @NonNull final FeatureFlags featureFlags,
            @ServicesV046 @NonNull final AddressChecks addressChecks) {
        return new CustomCallOperation(featureFlags, gasCalculator, addressChecks);
    }

    @Provides
    @IntoSet
    @ServicesV046
    static Operation provideChainIdOperation(@NonNull final GasCalculator gasCalculator) {
        return new CustomChainIdOperation(gasCalculator);
    }

    @Provides
    @IntoSet
    @ServicesV046
    static Operation provideCreateOperation(@NonNull final GasCalculator gasCalculator) {
        return new CustomCreateOperation(gasCalculator);
    }

    @Provides
    @IntoSet
    @ServicesV046
    static Operation provideCreate2Operation(
            @NonNull final GasCalculator gasCalculator, @ServicesV046 @NonNull final FeatureFlags featureFlags) {
        return new CustomCreate2Operation(gasCalculator, featureFlags);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV046
    static Operation provideLog0Operation(@NonNull final GasCalculator gasCalculator) {
        return new CustomLogOperation(0, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV046
    static Operation provideLog1Operation(final GasCalculator gasCalculator) {
        return new CustomLogOperation(1, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV046
    static Operation provideLog2Operation(final GasCalculator gasCalculator) {
        return new CustomLogOperation(2, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV046
    static Operation provideLog3Operation(final GasCalculator gasCalculator) {
        return new CustomLogOperation(3, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV046
    static Operation provideLog4Operation(final GasCalculator gasCalculator) {
        return new CustomLogOperation(4, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV046
    static Operation provideExtCodeHashOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV046 @NonNull final AddressChecks addressChecks,
            @ServicesV046 @NonNull final FeatureFlags featureFlags) {
        return new CustomExtCodeHashOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV046
    static Operation provideExtCodeSizeOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV046 @NonNull final AddressChecks addressChecks,
            @ServicesV046 @NonNull final FeatureFlags featureFlags) {
        return new CustomExtCodeSizeOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV046
    static Operation provideExtCodeCopyOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesV046 @NonNull final AddressChecks addressChecks,
            @ServicesV046 @NonNull final FeatureFlags featureFlags) {
        return new CustomExtCodeCopyOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV046
    static Operation providePrevRandaoOperation(@NonNull final GasCalculator gasCalculator) {
        return new CustomPrevRandaoOperation(gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesV046
    static Operation provideSelfDestructOperation(
            @NonNull final GasCalculator gasCalculator, @ServicesV046 @NonNull final AddressChecks addressChecks) {
        return new CustomSelfDestructOperation(gasCalculator, addressChecks, UseEIP6780Semantics.NO);
    }

    @Provides
    @IntoSet
    @ServicesV046
    static Operation provideSLoadOperation(
            @NonNull final GasCalculator gasCalculator, @ServicesV046 @NonNull final FeatureFlags featureFlags) {
        return new CustomSLoadOperation(featureFlags, new SLoadOperation(gasCalculator));
    }

    @Provides
    @IntoSet
    @ServicesV046
    static Operation provideSStoreOperation(
            @NonNull final GasCalculator gasCalculator, @ServicesV046 @NonNull final FeatureFlags featureFlags) {
        return new CustomSStoreOperation(featureFlags, new SStoreOperation(gasCalculator, FRONTIER_MINIMUM));
    }
}
