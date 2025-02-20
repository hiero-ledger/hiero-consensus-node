// SPDX-License-Identifier: Apache-2.0
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
