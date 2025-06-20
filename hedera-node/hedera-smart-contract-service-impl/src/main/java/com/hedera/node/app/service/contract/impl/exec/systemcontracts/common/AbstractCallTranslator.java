// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.SystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Basic implementation support for a {@link CallTranslator} that returns a translated
 * call when the {@link AbstractCallAttempt} matches and null otherwise.
 * @param <T> the type of the abstract call translator
 */
public abstract class AbstractCallTranslator<T extends AbstractCallAttempt<T>> implements CallTranslator<T> {

    private final SystemContract systemContractKind;
    private final SystemContractMethodRegistry systemContractMethodRegistry;
    private final ContractMetrics contractMetrics;

    public AbstractCallTranslator(
            @NonNull final SystemContract systemContractKind,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        this.systemContractKind = requireNonNull(systemContractKind);
        this.systemContractMethodRegistry = requireNonNull(systemContractMethodRegistry);
        this.contractMetrics = requireNonNull(contractMetrics);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable Call translateCallAttempt(@NonNull final T attempt) {
        requireNonNull(attempt);
        final var call = identifyMethod(attempt)
                .map(systemContractMethodRegistry::fromMissingContractGetWithContract)
                .map(systemContractMethod -> callFrom(attempt, systemContractMethod))
                .orElse(null);
        if (call != null) {
            if (call.getSystemContractMethod() == null) {
                contractMetrics.logWarnMissingSystemContractMethodOnCall(
                        identifyMethod(attempt).orElseThrow());
            }
        }
        return call;
    }

    public void registerMethods(@NonNull final SystemContractMethod... methods) {
        requireNonNull(methods);
        for (@NonNull final var method : methods) {
            requireNonNull(method);
            registerMethod(method, method.withContract(systemContractKind));
        }
    }

    private void registerMethod(
            @NonNull final SystemContractMethod methodWithoutContract,
            @NonNull final SystemContractMethod methodWithContract) {
        requireNonNull(methodWithoutContract);
        requireNonNull(methodWithContract);
        methodWithContract.verifyComplete();

        if (systemContractMethodRegistry != null) {
            systemContractMethodRegistry.register(methodWithoutContract, methodWithContract);
        }
    }

    @VisibleForTesting
    public @NonNull String kind() {
        return systemContractKind != null ? systemContractKind.name() : "<UNKNOWN-CONTRACT>";
    }
}
