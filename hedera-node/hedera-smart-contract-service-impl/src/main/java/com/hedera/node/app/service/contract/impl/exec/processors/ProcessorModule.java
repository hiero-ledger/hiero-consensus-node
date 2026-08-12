// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.ExchangeRateSystemContract.EXCHANGE_RATE_SYSTEM_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract.HAS_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract.HSS_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_16C_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.PrngSystemContract.PRNG_PRECOMPILE_ADDRESS;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ExchangeRateSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.PrngSystemContract;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import javax.inject.Singleton;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.contractvalidation.ContractValidationRule;
import org.hyperledger.besu.evm.contractvalidation.MaxCodeSizeRule;
import org.hyperledger.besu.evm.contractvalidation.PrefixCodeRule;
import org.hyperledger.besu.evm.internal.EvmConfiguration;

@Module(includes = {HtsTranslatorsModule.class, HasTranslatorsModule.class, HssTranslatorsModule.class})
public interface ProcessorModule {
    long INITIAL_CONTRACT_NONCE = 1L;
    boolean REQUIRE_CODE_DEPOSIT_TO_SUCCEED = true;
    int NUM_SYSTEM_ACCOUNTS = 750;

    @Provides
    @Singleton
    @IntoSet
    static ContractValidationRule provideMaxCodeSizeRule(@NonNull final EvmConfiguration evmConfiguration) {
        return MaxCodeSizeRule.from(EvmSpecVersion.defaultVersion(), evmConfiguration);
    }

    @Provides
    @Singleton
    @IntoSet
    static ContractValidationRule providePrefixCodeRule() {
        return PrefixCodeRule.of();
    }

    /**
     * Provides a singleton instance of {@link CodeFactory} initialized with zero values.
     *
     * <p>The values {@code maxEofVersion} and {@code maxContainerSize} are set to 0,
     * which means the factory defaults to handling only legacy code (EOF version 0)
     * and sets a strict size limit on EOF code containers.
     *
     * @return a singleton instance of {@link CodeFactory} with strict constraints.
     */
    @Provides
    @Singleton
    static CodeFactory provideCodeFactory() {
        return new CodeFactory(0, 0);
    }

    @Provides
    @Singleton
    static Map<Address, HederaSystemContract> provideHederaSystemContracts(
            @NonNull final HtsSystemContract htsSystemContract,
            @NonNull final ExchangeRateSystemContract exchangeRateSystemContract,
            @NonNull final PrngSystemContract prngSystemContract,
            @NonNull final HasSystemContract hasSystemContract,
            @NonNull final HssSystemContract hssSystemContract) {
        return Map.ofEntries(
                entry(Address.fromHexString(HTS_167_EVM_ADDRESS), requireNonNull(htsSystemContract)),
                entry(Address.fromHexString(HTS_16C_EVM_ADDRESS), requireNonNull(htsSystemContract)),
                entry(
                        Address.fromHexString(EXCHANGE_RATE_SYSTEM_CONTRACT_ADDRESS),
                        requireNonNull(exchangeRateSystemContract)),
                entry(Address.fromHexString(PRNG_PRECOMPILE_ADDRESS), requireNonNull(prngSystemContract)),
                entry(Address.fromHexString(HAS_EVM_ADDRESS), requireNonNull(hasSystemContract)),
                entry(Address.fromHexString(HSS_EVM_ADDRESS), requireNonNull(hssSystemContract)));
    }
}
