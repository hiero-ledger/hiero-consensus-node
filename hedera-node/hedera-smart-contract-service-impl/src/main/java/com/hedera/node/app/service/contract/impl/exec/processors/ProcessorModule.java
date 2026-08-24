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
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import javax.inject.Singleton;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.contractvalidation.ContractValidationRule;
import org.hyperledger.besu.evm.contractvalidation.MaxCodeSizeRule;
import org.hyperledger.besu.evm.contractvalidation.PrefixCodeRule;
import org.hyperledger.besu.evm.internal.EvmConfiguration;

@Module(includes = {HtsTranslatorsModule.class, HasTranslatorsModule.class, HssTranslatorsModule.class})
public interface ProcessorModule {
    long INITIAL_CONTRACT_NONCE = 1L;
    boolean REQUIRE_CODE_DEPOSIT_TO_SUCCEED = true;
    int NUM_SYSTEM_ACCOUNTS = 750;

    /**
     * WARNING: The order of these rules is important and must not change without careful
     * review. {@code ContractCreationProcessor} reports only the FIRST rule that fails as the
     * deployment's {@code errorMessage}. If a deploy violates multiple rules, every node must
     * pick the same one, or their committed records (and state roots) diverge, causing an ISS.
     * Do not replace this {@link List} with a {@link java.util.Set}, and do not reorder without
     * confirming all consensus nodes will observe the change simultaneously
     *
     * @param evmConfiguration the EVM configuration
     * @return the ordered list of contract validation rules
     */
    @Provides
    @Singleton
    static List<ContractValidationRule> provideContractValidationRules(
            @NonNull final EvmConfiguration evmConfiguration) {
        return List.of(MaxCodeSizeRule.from(EvmSpecVersion.defaultVersion(), evmConfiguration), PrefixCodeRule.of());
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
