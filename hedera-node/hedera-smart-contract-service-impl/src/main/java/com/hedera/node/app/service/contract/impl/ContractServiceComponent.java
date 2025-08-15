// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl;

import com.hedera.node.app.service.contract.impl.annotations.CustomOps;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.handlers.ContractHandlers;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import dagger.BindsInstance;
import dagger.Component;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.tracing.OperationTracer;

/**
 * The contract service component
 */
@Singleton
@Component(modules = ContractServiceModule.class)
public interface ContractServiceComponent {
    /**
     * A factory for creating a {@link ContractServiceComponent}.
     */
    @Component.Factory
    interface Factory {
        /**
         * @param instantSource the source of the current instant
         * @param signatureVerifier the verifier used for signature verification
         * @param verificationStrategies the current verification strategy to use
         * @param addOnTracers all operation tracer callbacks
         * @param contractMetrics holds all metrics for the smart contract service
         * @param systemContractMethodRegistry registry of all system contract methods
         * @param customOps any additional custom operations to use when constructing the EVM
         * @param entityIdFactory a factory for creating entity IDs
         * @return the contract service component
         */
        ContractServiceComponent create(
                @BindsInstance InstantSource instantSource,
                @BindsInstance SignatureVerifier signatureVerifier,
                @BindsInstance VerificationStrategies verificationStrategies,
                @BindsInstance @Nullable Supplier<List<OperationTracer>> addOnTracers,
                @BindsInstance ContractMetrics contractMetrics,
                @BindsInstance SystemContractMethodRegistry systemContractMethodRegistry,
                @BindsInstance @CustomOps Set<Operation> customOps,
                @BindsInstance EntityIdFactory entityIdFactory);
    }

    /**
     * @return all contract transaction handlers
     */
    ContractHandlers handlers();

    /**
     * @return contract metrics collection, instance
     */
    ContractMetrics contractMetrics();

    /**
     * @return method registry for system contracts
     */
    SystemContractMethodRegistry systemContractMethodRegistry();

    @Named("HasTranslators")
    Provider<List<CallTranslator<HasCallAttempt>>> hasCallTranslators();

    @Named("HssTranslators")
    Provider<List<CallTranslator<HssCallAttempt>>> hssCallTranslators();

    @Named("HtsTranslators")
    Provider<List<CallTranslator<HtsCallAttempt>>> htsCallTranslators();
}
