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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.getevmaddressalias;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Modifier;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code evmAddressAlias} calls to the HAS system contract.
 */
@Singleton
public class EvmAddressAliasTranslator extends AbstractCallTranslator<HasCallAttempt> {
    /** Selector for getEvmAddressAlias(address) method. */
    public static final SystemContractMethod EVM_ADDRESS_ALIAS = SystemContractMethod.declare(
                    "getEvmAddressAlias(address)", ReturnTypes.RESPONSE_CODE_ADDRESS)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.ALIASES);

    @Inject
    public EvmAddressAliasTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger
        super(SystemContractMethod.SystemContract.HAS, systemContractMethodRegistry, contractMetrics);

        registerMethods(EVM_ADDRESS_ALIAS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull EvmAddressAliasCall callFrom(@NonNull final HasCallAttempt attempt) {
        final Address address = EvmAddressAliasTranslator.EVM_ADDRESS_ALIAS
                .decodeCall(attempt.input().toArrayUnsafe())
                .get(0);
        return new EvmAddressAliasCall(attempt, address);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt, "attempt");
        return attempt.isMethod(EVM_ADDRESS_ALIAS);
    }
}
