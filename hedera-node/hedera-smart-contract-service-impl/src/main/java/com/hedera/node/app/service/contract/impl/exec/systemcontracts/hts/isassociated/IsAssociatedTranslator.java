// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.isassociated;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
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
public class IsAssociatedTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for isAssociated() method. */
    public static final SystemContractMethod IS_ASSOCIATED = SystemContractMethod.declare(
                    "isAssociated()", ReturnTypes.BOOL)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.TOKEN_QUERY, Category.ASSOCIATION);

    /**
     * Default constructor for injection.
     */
    @Inject
    public IsAssociatedTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(IS_ASSOCIATED);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        if (!attempt.isRedirect()) return Optional.empty();
        return attempt.isMethod(IS_ASSOCIATED);
    }

    @Override
    public final Call callFrom(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return new IsAssociatedCall(
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                attempt.senderId(),
                attempt.redirectToken());
    }
}
