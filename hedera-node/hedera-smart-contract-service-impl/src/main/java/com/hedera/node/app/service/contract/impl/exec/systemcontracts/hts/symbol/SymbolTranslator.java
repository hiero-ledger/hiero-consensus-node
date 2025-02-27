// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.symbol;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
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

/**
 * Translates {@code symbol()} calls to the HTS system contract.
 */
@Singleton
public class SymbolTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for symbol() method. */
    public static final SystemContractMethod SYMBOL = SystemContractMethod.declare("symbol()", ReturnTypes.STRING)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.ERC20, Category.ERC721, Category.TOKEN_QUERY);

    /**
     * Default constructor for injection.
     */
    @Inject
    public SymbolTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(SYMBOL);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(SYMBOL);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SymbolCall callFrom(@NonNull final HtsCallAttempt attempt) {
        return new SymbolCall(attempt.systemContractGasCalculator(), attempt.enhancement(), attempt.redirectToken());
    }
}
