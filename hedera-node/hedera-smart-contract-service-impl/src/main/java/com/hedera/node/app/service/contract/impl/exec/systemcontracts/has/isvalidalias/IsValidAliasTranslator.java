// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isvalidalias;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
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

@Singleton
public class IsValidAliasTranslator extends AbstractCallTranslator<HasCallAttempt> {

    /** Selector for isValidAlias(address) method. */
    public static final SystemContractMethod IS_VALID_ALIAS = SystemContractMethod.declare(
                    "isValidAlias(address)", ReturnTypes.BOOL)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.ALIASES);

    /**
     * Default constructor for injection.
     */
    @Inject
    public IsValidAliasTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger
        super(SystemContractMethod.SystemContract.HAS, systemContractMethodRegistry, contractMetrics);

        registerMethods(IS_VALID_ALIAS);
    }

    @Override
    public @NonNull Call callFrom(@NonNull HasCallAttempt attempt) {
        final Address address =
                IS_VALID_ALIAS.decodeCall(attempt.input().toArrayUnsafe()).get(0);
        return new IsValidAliasCall(attempt, address);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt, "attempt");

        return attempt.isMethod(IS_VALID_ALIAS);
    }
}
