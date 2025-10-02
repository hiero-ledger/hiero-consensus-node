// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl;

import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_030;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_034;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_038;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_046;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_050;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_051;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_065;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_066;
import static org.hyperledger.besu.evm.internal.EvmConfiguration.WorldUpdaterMode.JOURNALED;

import com.hedera.node.app.service.contract.impl.annotations.ServicesV030;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV034;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV038;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV046;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV050;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV051;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV065;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV066;
import com.hedera.node.app.service.contract.impl.annotations.ServicesVersionKey;
import com.hedera.node.app.service.contract.impl.exec.QueryComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule;
import com.hedera.node.app.service.contract.impl.exec.v030.V030Module;
import com.hedera.node.app.service.contract.impl.exec.v034.V034Module;
import com.hedera.node.app.service.contract.impl.exec.v038.V038Module;
import com.hedera.node.app.service.contract.impl.exec.v046.V046Module;
import com.hedera.node.app.service.contract.impl.exec.v050.V050Module;
import com.hedera.node.app.service.contract.impl.exec.v051.V051Module;
import com.hedera.node.app.service.contract.impl.exec.v065.V065Module;
import com.hedera.node.app.service.contract.impl.exec.v066.V066Module;
import com.hedera.node.app.service.contract.impl.handlers.ContractCallHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractCallLocalHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractCreateHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractDeleteHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractGetBySolidityIDHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractGetBytecodeHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractGetInfoHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractGetRecordsHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractHandlers;
import com.hedera.node.app.service.contract.impl.handlers.ContractSystemDeleteHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractSystemUndeleteHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractUpdateHandler;
import com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler;
import com.hedera.node.app.service.contract.impl.handlers.HookDispatchHandler;
import com.hedera.node.app.service.contract.impl.handlers.LambdaSStoreHandler;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;

/**
 * Provides bindings for the {@link TransactionProcessor} implementations used by each
 * version of the Hedera EVM, along with infrastructure like the {@link GasCalculator}
 * and Hedera {@link PrecompiledContract} instances that have not changed since the
 * first EVM version we explicitly support (which is {@code v0.30}).
 */
@Module(
        includes = {
            V030Module.class,
            V034Module.class,
            V038Module.class,
            V046Module.class,
            V050Module.class,
            V051Module.class,
            V065Module.class,
            V066Module.class,
            ProcessorModule.class
        },
        subcomponents = {TransactionComponent.class, QueryComponent.class})
public interface ContractServiceModule {
    @Provides
    @Singleton
    static ContractHandlers provideHandlers(
            @NonNull final ContractCallHandler contractCallHandler,
            @NonNull final ContractCallLocalHandler contractCallLocalHandler,
            @NonNull final ContractCreateHandler contractCreateHandler,
            @NonNull final ContractDeleteHandler contractDeleteHandler,
            @NonNull final ContractGetBySolidityIDHandler contractGetBySolidityIDHandler,
            @NonNull final ContractGetBytecodeHandler contractGetBytecodeHandler,
            @NonNull final ContractGetInfoHandler contractGetInfoHandler,
            @NonNull final ContractGetRecordsHandler contractGetRecordsHandler,
            @NonNull final ContractSystemDeleteHandler contractSystemDeleteHandler,
            @NonNull final ContractSystemUndeleteHandler contractSystemUndeleteHandler,
            @NonNull final ContractUpdateHandler contractUpdateHandler,
            @NonNull final EthereumTransactionHandler ethereumTransactionHandler,
            @NonNull final LambdaSStoreHandler lambdaSStoreHandler,
            @NonNull final HookDispatchHandler hookDispatchHandler) {
        return new ContractHandlers(
                contractCallHandler,
                contractCallLocalHandler,
                contractCreateHandler,
                contractDeleteHandler,
                contractGetBySolidityIDHandler,
                contractGetBytecodeHandler,
                contractGetInfoHandler,
                contractGetRecordsHandler,
                contractSystemDeleteHandler,
                contractSystemUndeleteHandler,
                contractUpdateHandler,
                ethereumTransactionHandler,
                lambdaSStoreHandler,
                hookDispatchHandler);
    }

    /**
     * Binds the {@link GasCalculator} to the {@link CustomGasCalculator}.
     *
     * @param gasCalculator the implementation of the {@link GasCalculator}
     * @return  the bound implementation
     */
    @Binds
    @Singleton
    GasCalculator bindGasCalculator(@NonNull final CustomGasCalculator gasCalculator);

    /**
     * @return the EVM configuration to use
     */
    @Provides
    @Singleton
    static EvmConfiguration provideEvmConfiguration() {
        return new EvmConfiguration(EvmConfiguration.DEFAULT.jumpDestCacheWeightKB(), JOURNALED);
    }

    /**
     * @param processor the transaction processor
     * @return the bound transaction processor for version 0.30
     */
    @Binds
    @IntoMap
    @Singleton
    @ServicesVersionKey(VERSION_030)
    TransactionProcessor bindV030Processor(@ServicesV030 @NonNull final TransactionProcessor processor);

    /**
     * @param processor the transaction processor
     * @return the bound transaction processor for version 0.34
     */
    @Binds
    @IntoMap
    @Singleton
    @ServicesVersionKey(VERSION_034)
    TransactionProcessor bindV034Processor(@ServicesV034 @NonNull final TransactionProcessor processor);

    /**
     * @param processor the transaction processor
     * @return the bound transaction processor for version 0.38
     */
    @Binds
    @IntoMap
    @Singleton
    @ServicesVersionKey(VERSION_038)
    TransactionProcessor bindV038Processor(@ServicesV038 @NonNull final TransactionProcessor processor);

    /**
     * @param processor the transaction processor
     * @return the bound transaction processor for version 0.46
     */
    @Binds
    @IntoMap
    @Singleton
    @ServicesVersionKey(VERSION_046)
    TransactionProcessor bindV046Processor(@ServicesV046 @NonNull final TransactionProcessor processor);

    /**
     * @param processor the transaction processor
     * @return the bound transaction processor for version 0.50
     */
    @Binds
    @IntoMap
    @Singleton
    @ServicesVersionKey(VERSION_050)
    TransactionProcessor bindV050Processor(@ServicesV050 @NonNull final TransactionProcessor processor);

    /**
     * @param processor the transaction processor
     * @return the bound transaction processor for version 0.51
     */
    @Binds
    @IntoMap
    @Singleton
    @ServicesVersionKey(VERSION_051)
    TransactionProcessor bindV051Processor(@ServicesV051 @NonNull final TransactionProcessor processor);

    /**
     * @param processor the transaction processor
     * @return the bound transaction processor for version 0.65
     */
    @Binds
    @IntoMap
    @Singleton
    @ServicesVersionKey(VERSION_065)
    TransactionProcessor bindV065Processor(@ServicesV065 @NonNull final TransactionProcessor processor);

    /**
     * @param processor the transaction processor
     * @return the bound transaction processor for version 0.66
     */
    @Binds
    @IntoMap
    @Singleton
    @ServicesVersionKey(VERSION_066)
    TransactionProcessor bindV066Processor(@ServicesV066 @NonNull final TransactionProcessor processor);
}
