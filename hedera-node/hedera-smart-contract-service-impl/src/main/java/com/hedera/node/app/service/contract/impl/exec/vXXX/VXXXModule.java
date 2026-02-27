// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.vXXX;

import static com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule.INITIAL_CONTRACT_NONCE;
import static com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule.REQUIRE_CODE_DEPOSIT_TO_SUCCEED;
import static org.hyperledger.besu.evm.MainnetEVMs.registerCancunOperations;
import static org.hyperledger.besu.evm.operation.SStoreOperation.FRONTIER_MINIMUM;

import com.hedera.node.app.service.contract.impl.annotations.CustomOps;
import com.hedera.node.app.service.contract.impl.annotations.ServicesVXXX;
import com.hedera.node.app.service.contract.impl.bonneville.BonnevilleEVM;
import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.FrameRunner;
import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.operations.*;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomSelfDestructOperation.UseEIP6780Semantics;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomContractCreationProcessor;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameBuilder;
import com.hedera.node.app.service.contract.impl.exec.v038.Version038AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.v067.Version067FeatureFlags;
import com.hedera.node.app.service.contract.impl.hevm.HEVM;
import com.hedera.node.app.service.contract.impl.hevm.HederaEVM;
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
import org.hyperledger.besu.evm.code.CodeFactory;
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
 * Provides the Services X.XX BEVM implementation, which is a brand new EVM for
 * the Bonneville project.
 */
@Module
public interface VXXXModule {

    @Provides
    @Singleton
    @ServicesVXXX
    static TransactionProcessor provideTransactionProcessor(
            @NonNull final FrameBuilder frameBuilder,
            @NonNull final FrameRunner frameRunner,
            @ServicesVXXX @NonNull final CustomMessageCallProcessor messageCallProcessor,
            @ServicesVXXX @NonNull final ContractCreationProcessor contractCreationProcessor,
            @NonNull final CustomGasCharging gasCharging,
            @ServicesVXXX @NonNull final FeatureFlags featureFlags,
            @NonNull final CodeFactory codeFactory) {
        return new TransactionProcessor(
                frameBuilder,
                frameRunner,
                gasCharging,
                messageCallProcessor,
                contractCreationProcessor,
                featureFlags,
                codeFactory);
    }

    @Provides
    @Singleton
    @ServicesVXXX
    static ContractCreationProcessor provideContractCreationProcessor(
            @ServicesVXXX HEVM evm, Set<ContractValidationRule> validationRules) {
        return new CustomContractCreationProcessor(
                evm, REQUIRE_CODE_DEPOSIT_TO_SUCCEED, List.copyOf(validationRules), INITIAL_CONTRACT_NONCE);
    }

    @Provides
    @Singleton
    @ServicesVXXX
    static CustomMessageCallProcessor provideMessageCallProcessor(
            @ServicesVXXX HEVM evm,
            @ServicesVXXX FeatureFlags featureFlags,
            @ServicesVXXX AddressChecks addressChecks,
            @ServicesVXXX PrecompileContractRegistry registry,
            Map<Address, HederaSystemContract> systemContracts,
            ContractMetrics contractMetrics) {
        return new CustomMessageCallProcessor(
                evm, featureFlags, registry, addressChecks, systemContracts, contractMetrics);
    }

    @Provides
    @Singleton
    @ServicesVXXX
    static HEVM provideEVM(
            @ServicesVXXX Set<Operation> customOperations,
            EvmConfiguration evmConfiguration,
            GasCalculator gasCalculator,
            @CustomOps Set<Operation> customOps,
            @ServicesVXXX @NonNull FeatureFlags featureFlags) {
        // Use Cancun EVM with 0.51 custom operations and 0x00 chain id (set at runtime)
        final var operationRegistry = new OperationRegistry();
        registerCancunOperations(operationRegistry, gasCalculator, BigInteger.ZERO);
        customOperations.forEach(operationRegistry::put);
        customOps.forEach(operationRegistry::put);
        if( System.getenv("UseBonnevilleEVM") == null ) {
            KZGPointEvalPrecompiledContract.init();
            return new     HederaEVM(operationRegistry, gasCalculator, evmConfiguration, EvmSpecVersion.CANCUN);
        } else {
            return new BonnevilleEVM(operationRegistry, gasCalculator, evmConfiguration, EvmSpecVersion.CANCUN, featureFlags);
        }
    }

    @Provides
    @Singleton
    @ServicesVXXX
    static PrecompileContractRegistry providePrecompileContractRegistry(GasCalculator gasCalculator) {
        final var precompileContractRegistry = new PrecompileContractRegistry();
        MainnetPrecompiledContracts.populateForCancun(precompileContractRegistry, gasCalculator);
        return precompileContractRegistry;
    }

    @Binds
    @ServicesVXXX
    FeatureFlags bindFeatureFlags( Version067FeatureFlags featureFlags);

    @Binds
    @ServicesVXXX
    AddressChecks bindAddressChecks(Version038AddressChecks addressChecks);

    @Provides
    @IntoSet
    @ServicesVXXX
    static Operation provideBalanceOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesVXXX @NonNull final AddressChecks addressChecks,
            @ServicesVXXX @NonNull final FeatureFlags featureFlags) {
        return new CustomBalanceOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @IntoSet
    @ServicesVXXX
    static Operation provideDelegateCallOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesVXXX @NonNull final AddressChecks addressChecks,
            @ServicesVXXX @NonNull final FeatureFlags featureFlags) {
        return new CustomDelegateCallOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @IntoSet
    @ServicesVXXX
    static Operation provideCallCodeOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesVXXX @NonNull final AddressChecks addressChecks,
            @ServicesVXXX @NonNull final FeatureFlags featureFlags) {
        return new CustomCallCodeOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @IntoSet
    @ServicesVXXX
    static Operation provideStaticCallOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesVXXX @NonNull final AddressChecks addressChecks,
            @ServicesVXXX @NonNull final FeatureFlags featureFlags) {
        return new CustomStaticCallOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @IntoSet
    @ServicesVXXX
    static Operation provideCallOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesVXXX @NonNull final FeatureFlags featureFlags,
            @ServicesVXXX @NonNull final AddressChecks addressChecks) {
        return new CustomCallOperation(featureFlags, gasCalculator, addressChecks);
    }

    @Provides
    @IntoSet
    @ServicesVXXX
    static Operation provideChainIdOperation(@NonNull final GasCalculator gasCalculator) {
        return new CustomChainIdOperation(gasCalculator);
    }

    @Provides
    @IntoSet
    @ServicesVXXX
    static Operation provideCreateOperation(
            @NonNull final GasCalculator gasCalculator, @NonNull final CodeFactory codeFactory) {
        return new CustomCreateOperation(gasCalculator, codeFactory);
    }

    @Provides
    @IntoSet
    @ServicesVXXX
    static Operation provideCreate2Operation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesVXXX @NonNull final FeatureFlags featureFlags,
            @NonNull final CodeFactory codeFactory) {
        return new CustomCreate2Operation(gasCalculator, featureFlags, codeFactory);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesVXXX
    static Operation provideLog0Operation(@NonNull final GasCalculator gasCalculator) {
        return new CustomLogOperation(0, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesVXXX
    static Operation provideLog1Operation(final GasCalculator gasCalculator) {
        return new CustomLogOperation(1, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesVXXX
    static Operation provideLog2Operation(final GasCalculator gasCalculator) {
        return new CustomLogOperation(2, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesVXXX
    static Operation provideLog3Operation(final GasCalculator gasCalculator) {
        return new CustomLogOperation(3, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesVXXX
    static Operation provideLog4Operation(final GasCalculator gasCalculator) {
        return new CustomLogOperation(4, gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesVXXX
    static Operation provideExtCodeHashOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesVXXX @NonNull final AddressChecks addressChecks,
            @ServicesVXXX @NonNull final FeatureFlags featureFlags) {
        return new CustomExtCodeHashOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesVXXX
    static Operation provideExtCodeSizeOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesVXXX @NonNull final AddressChecks addressChecks,
            @ServicesVXXX @NonNull final FeatureFlags featureFlags) {
        return new CustomExtCodeSizeOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesVXXX
    static Operation provideExtCodeCopyOperation(
            @NonNull final GasCalculator gasCalculator,
            @ServicesVXXX @NonNull final AddressChecks addressChecks,
            @ServicesVXXX @NonNull final FeatureFlags featureFlags) {
        return new CustomExtCodeCopyOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesVXXX
    static Operation providePrevRandaoOperation(@NonNull final GasCalculator gasCalculator) {
        return new CustomPrevRandaoOperation(gasCalculator);
    }

    @Provides
    @Singleton
    @IntoSet
    @ServicesVXXX
    static Operation provideSelfDestructOperation(
            @NonNull final GasCalculator gasCalculator, @ServicesVXXX @NonNull final AddressChecks addressChecks) {
        return new CustomSelfDestructOperation(gasCalculator, addressChecks, UseEIP6780Semantics.YES);
    }

    @Provides
    @IntoSet
    @ServicesVXXX
    static Operation provideSLoadOperation(
            @NonNull final GasCalculator gasCalculator, @ServicesVXXX @NonNull final FeatureFlags featureFlags) {
        return new CustomSLoadOperation(featureFlags, new SLoadOperation(gasCalculator));
    }

    @Provides
    @IntoSet
    @ServicesVXXX
    static Operation provideSStoreOperation(
            @NonNull final GasCalculator gasCalculator, @ServicesVXXX @NonNull final FeatureFlags featureFlags) {
        return new CustomSStoreOperation(featureFlags, new SStoreOperation(gasCalculator, FRONTIER_MINIMUM));
    }
}
