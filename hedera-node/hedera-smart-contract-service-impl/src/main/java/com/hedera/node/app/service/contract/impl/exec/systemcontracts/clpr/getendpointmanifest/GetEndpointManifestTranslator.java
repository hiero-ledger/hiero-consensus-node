// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getendpointmanifest;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code getEndpointManifest()} calls to the CLPR system contract (spec §6.5).
 * Returns the PBJ-serialized {@code ClprEndpointManifest} singleton as ABI-encoded bytes,
 * enabling on-chain callers to obtain the manifest for building manifest-recovery bundles.
 */
@Singleton
public class GetEndpointManifestTranslator extends AbstractCallTranslator<ClprCallAttempt> {

    public static final SystemContractMethod GET_ENDPOINT_MANIFEST =
            SystemContractMethod.declare("getEndpointManifest()", "(bytes)").withCategories(Category.CLPR);

    @Inject
    public GetEndpointManifestTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.CLPR, systemContractMethodRegistry, contractMetrics);
        registerMethods(GET_ENDPOINT_MANIFEST);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final ClprCallAttempt attempt) {
        return attempt.isMethod(GET_ENDPOINT_MANIFEST);
    }

    @Override
    public Call callFrom(@NonNull final ClprCallAttempt attempt) {
        return new GetEndpointManifestCall(attempt.enhancement(), attempt.systemContractGasCalculator());
    }
}
