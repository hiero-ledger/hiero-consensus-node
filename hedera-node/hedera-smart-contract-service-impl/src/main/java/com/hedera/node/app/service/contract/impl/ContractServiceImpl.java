// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.DefaultVerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.handlers.ContractHandlers;
import com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema;
import com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.tracing.OperationTracer;

/**
 * Implementation of the {@link ContractService}.
 */
public class ContractServiceImpl implements ContractService {

    /**
     * Minimum gas required for contract operations.
     */
    public static final long INTRINSIC_GAS_LOWER_BOUND = 21_000L;

    private final ContractServiceComponent component;

    /**
     * @param appContext the current application context
     */
    public ContractServiceImpl(@NonNull final AppContext appContext, @NonNull final Metrics metrics) {
        this(appContext, metrics, null, null, Set.of());
    }

    /**
     * @param appContext the current application context
     * @param verificationStrategies the current verification strategy used
     * @param addOnTracers all operation tracer callbacks
     * @param customOps any additional custom operations to use when constructing the EVM
     */
    public ContractServiceImpl(
            @NonNull final AppContext appContext,
            @NonNull final Metrics metrics,
            @Nullable final VerificationStrategies verificationStrategies,
            @Nullable final Supplier<List<OperationTracer>> addOnTracers,
            @NonNull final Set<Operation> customOps) {
        requireNonNull(appContext);
        requireNonNull(customOps);
        requireNonNull(metrics);
        final Supplier<ContractsConfig> contractsConfigSupplier =
                () -> appContext.configSupplier().get().getConfigData(ContractsConfig.class);
        final var systemContractMethodRegistry = new SystemContractMethodRegistry();
        final var contractMetrics = new ContractMetrics(metrics, contractsConfigSupplier, systemContractMethodRegistry);

        this.component = DaggerContractServiceComponent.factory()
                .create(
                        appContext.instantSource(),
                        // (FUTURE) Inject the signature verifier instance into the IsAuthorizedSystemContract
                        // C.f. https://github.com/hashgraph/hedera-services/issues/14248
                        appContext.signatureVerifier(),
                        Optional.ofNullable(verificationStrategies).orElseGet(DefaultVerificationStrategies::new),
                        addOnTracers,
                        contractMetrics,
                        systemContractMethodRegistry,
                        customOps,
                        appContext.idFactory());
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.registerAll(new V0490ContractSchema(), new V065ContractSchema());
    }

    public void createMetrics() {
        final var contractMetrics = requireNonNull(component.contractMetrics());

        // Force call translators to be instantiated now, so that all the system contract methods
        // will be registered, so the secondary metrics can be created.  (Left to its own devices
        // Dagger would delay instantiating them until transactions started flowing.)
        final var allTranslators = allCallTranslators();

        contractMetrics.createContractPrimaryMetrics();
        contractMetrics.createContractSecondaryMetrics();
    }

    /**
     * @return all contract transaction handlers
     */
    public ContractHandlers handlers() {
        return component.handlers();
    }

    private @NonNull List<CallTranslator<? extends AbstractCallAttempt<?>>> allCallTranslators() {
        final var allCallTranslators = new ArrayList<CallTranslator<? extends AbstractCallAttempt<?>>>();
        allCallTranslators.addAll(component.hasCallTranslators().get());
        allCallTranslators.addAll(component.hssCallTranslators().get());
        allCallTranslators.addAll(component.htsCallTranslators().get());
        return allCallTranslators;
    }

    @VisibleForTesting
    private Map<String, String> metricsInventory() {
        final var inventory = new TreeMap<String, String>();
        inventory.put("methods", component.systemContractMethodRegistry().allMethodsAsTable());
        inventory.put("metrics", component.contractMetrics().allCountersAsTable());
        return inventory;
    }
}
