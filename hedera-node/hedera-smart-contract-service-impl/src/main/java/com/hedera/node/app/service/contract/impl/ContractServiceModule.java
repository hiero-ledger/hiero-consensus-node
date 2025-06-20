// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl;

import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_030;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_034;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_038;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_046;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_050;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_051;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion.VERSION_062;
import static org.hyperledger.besu.evm.internal.EvmConfiguration.WorldUpdaterMode.JOURNALED;

import com.hedera.node.app.service.contract.impl.annotations.ServicesV030;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV034;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV038;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV046;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV050;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV051;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV062;
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
import com.hedera.node.app.service.contract.impl.exec.v062.V062Module;
import com.hedera.node.app.service.contract.impl.hevm.HederaOpsDuration;
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
            V062Module.class,
            ProcessorModule.class
        },
        subcomponents = {TransactionComponent.class, QueryComponent.class})
public interface ContractServiceModule {
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

    @Provides
    @Singleton
    static HederaOpsDuration provideHederaOpsDuration() {
        return new HederaOpsDuration();
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
     * @return the bound transaction processor for version 0.62
     */
    @Binds
    @IntoMap
    @Singleton
    @ServicesVersionKey(VERSION_062)
    TransactionProcessor bindV062Processor(@ServicesV062 @NonNull final TransactionProcessor processor);
}
