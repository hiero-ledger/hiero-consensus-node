// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.balanceof;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
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
 * Translates {@code balanceOf} calls to the HTS system contract.
 */
@Singleton
public class BalanceOfTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /**
     * Selector for balanceOf(address) method.
     */
    public static final SystemContractMethod BALANCE_OF = SystemContractMethod.declare(
                    "balanceOf(address)", ReturnTypes.INT)
            .withModifier(Modifier.VIEW)
            .withCategory(Category.TOKEN_QUERY);

    /**
     * Default constructor for injection.
     */
    @Inject
    public BalanceOfTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(BALANCE_OF);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BalanceOfCall callFrom(@NonNull final HtsCallAttempt attempt) {
        final Address owner =
                BALANCE_OF.decodeCall(attempt.input().toArrayUnsafe()).get(0);
        return new BalanceOfCall(
                attempt.enhancement(), attempt.systemContractGasCalculator(), attempt.redirectToken(), owner);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(BALANCE_OF);
    }
}
