/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import org.hyperledger.besu.evm.contractvalidation.ContractValidationRule;
import org.hyperledger.besu.evm.contractvalidation.MaxCodeSizeRule;
import org.hyperledger.besu.evm.contractvalidation.PrefixCodeRule;

@Module(includes = {HtsTranslatorsModule.class, HasTranslatorsModule.class, HssTranslatorsModule.class})
public interface ProcessorModule {
    long INITIAL_CONTRACT_NONCE = 1L;
    boolean REQUIRE_CODE_DEPOSIT_TO_SUCCEED = true;
    int NUM_SYSTEM_ACCOUNTS = 750;

    @Provides
    @Singleton
    @IntoSet
    static ContractValidationRule provideMaxCodeSizeRule() {
        return MaxCodeSizeRule.of(0x6000);
    }

    @Provides
    @Singleton
    @IntoSet
    static ContractValidationRule providePrefixCodeRule() {
        return PrefixCodeRule.of();
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
