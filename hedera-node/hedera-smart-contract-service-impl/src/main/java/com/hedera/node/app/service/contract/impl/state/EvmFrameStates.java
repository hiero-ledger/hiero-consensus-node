// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * A factory for per-transaction {@link EvmFrameStateFactory} instances.
 */
@FunctionalInterface
public interface EvmFrameStates {
    /**
     * Build the per-transaction EvmFrameStateFactory.
     */
    EvmFrameStateFactory from(
            @NonNull HederaOperations hederaOperations,
            @NonNull HederaNativeOperations hederaNativeOperations,
            @NonNull CodeFactory codeFactory);

    /**
     * Default strategy: produce a ScopedEvmFrameStateFactory.
     */
    EvmFrameStates DEFAULT = ScopedEvmFrameStateFactory::new;
}
