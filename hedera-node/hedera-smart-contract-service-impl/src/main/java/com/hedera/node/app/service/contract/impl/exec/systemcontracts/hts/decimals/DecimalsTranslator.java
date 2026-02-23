// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.decimals;

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

/**
 * Translates {@code decimals} calls to the HTS system contract.
 */
@Singleton
public class DecimalsTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for decimals() method. */
    public static final SystemContractMethod DECIMALS = SystemContractMethod.declare("decimals()", ReturnTypes.BYTE)
            .withModifier(Modifier.VIEW)
            .withCategory(Category.TOKEN_QUERY);

    /**
     * Default constructor for injection.
     */
    @Inject
    public DecimalsTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(DECIMALS);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(DECIMALS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        return new DecimalsCall(attempt.enhancement(), attempt.systemContractGasCalculator(), attempt.redirectToken());
    }
}
