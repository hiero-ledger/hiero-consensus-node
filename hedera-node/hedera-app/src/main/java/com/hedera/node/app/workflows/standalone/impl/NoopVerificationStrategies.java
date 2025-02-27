// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone.impl;

import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy.Decision;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.datatypes.Address;

/**
 * Implements a no-op {@link VerificationStrategies} for use in standalone contract execution.
 * The strategy considers all keys to have valid signatures under all conditions.
 */
public enum NoopVerificationStrategies implements VerificationStrategies {
    NOOP_VERIFICATION_STRATEGIES;

    private static final VerificationStrategy NOOP_VERIFICATION_STRATEGY = key -> Decision.VALID;

    @Override
    public VerificationStrategy activatingOnlyContractKeysFor(
            @NonNull final Address sender,
            final boolean requiresDelegatePermission,
            @NonNull final HederaNativeOperations nativeOperations) {
        return NOOP_VERIFICATION_STRATEGY;
    }
}
