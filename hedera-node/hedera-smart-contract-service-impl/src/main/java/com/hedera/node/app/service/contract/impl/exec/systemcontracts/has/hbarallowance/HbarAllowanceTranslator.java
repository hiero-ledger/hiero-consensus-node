// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Modifier;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code hbarAllowance()} calls to the HAS system contract.
 */
@Singleton
public class HbarAllowanceTranslator extends AbstractCallTranslator<HasCallAttempt> {

    /** Selector for hbarAllowance(address) method. */
    public static final SystemContractMethod HBAR_ALLOWANCE_PROXY = SystemContractMethod.declare(
                    "hbarAllowance(address)", ReturnTypes.RESPONSE_CODE_INT256)
            .withVia(CallVia.PROXY)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.ALLOWANCE);

    /** Selector for hbarAllowance(address,address) method. */
    public static final SystemContractMethod HBAR_ALLOWANCE = SystemContractMethod.declare(
                    "hbarAllowance(address,address)", ReturnTypes.RESPONSE_CODE_INT256)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.ALLOWANCE);

    @Inject
    public HbarAllowanceTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HAS, systemContractMethodRegistry, contractMetrics);

        registerMethods(HBAR_ALLOWANCE, HBAR_ALLOWANCE_PROXY);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(HBAR_ALLOWANCE, HBAR_ALLOWANCE_PROXY);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt);
        if (attempt.isSelector(HBAR_ALLOWANCE)) {
            return decodeAllowance(attempt);
        } else if (attempt.isSelector(HBAR_ALLOWANCE_PROXY)) {
            return decodeAllowanceProxy(attempt);
        }
        return null;
    }

    @NonNull
    private Call decodeAllowance(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt);
        final var call = HBAR_ALLOWANCE.decodeCall(attempt.inputBytes());

        var owner = attempt.addressIdConverter().convert(call.get(0));
        var spender = attempt.addressIdConverter().convert(call.get(1));
        return new HbarAllowanceCall(attempt, owner, spender);
    }

    @NonNull
    private Call decodeAllowanceProxy(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt);
        final var call = HBAR_ALLOWANCE_PROXY.decodeCall(attempt.inputBytes());
        var spender = attempt.addressIdConverter().convert(call.get(0));
        return new HbarAllowanceCall(attempt, attempt.redirectAccountId(), spender);
    }
}
