// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import javax.inject.Inject;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * A factory for {@link EvmFrameState} instances that are scoped to the current state of the world in
 * the ongoing transaction.
 */
public class ScopedEvmFrameStateFactory implements EvmFrameStateFactory {
    private final HederaOperations hederaOperations;
    private final HederaNativeOperations hederaNativeOperations;
    private final CodeFactory codeFactory;

    @Inject
    public ScopedEvmFrameStateFactory(
            @NonNull final HederaOperations hederaOperations,
            @NonNull final HederaNativeOperations hederaNativeOperations,
            @NonNull final CodeFactory codeFactory) {
        this.hederaOperations = Objects.requireNonNull(hederaOperations);
        this.hederaNativeOperations = Objects.requireNonNull(hederaNativeOperations);
        this.codeFactory = codeFactory;
    }

    @Override
    public EvmFrameState get() {
        return new DispatchingEvmFrameState(hederaNativeOperations, hederaOperations.getStore(), codeFactory);
    }
}
