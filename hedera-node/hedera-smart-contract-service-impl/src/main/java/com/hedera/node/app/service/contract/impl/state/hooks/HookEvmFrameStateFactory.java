// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state.hooks;

import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.state.EvmFrameState;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStateFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hyperledger.besu.evm.code.CodeFactory;

/**
 * A factory for {@link EvmFrameState} instances that are scoped to the current state of the world in
 * the ongoing transaction.
 */
public class HookEvmFrameStateFactory implements EvmFrameStateFactory {
    private final HederaOperations hederaOperations;
    private final HederaNativeOperations hederaNativeOperations;
    private final CodeFactory codeFactory;
    private final EvmHookState hook;

    public HookEvmFrameStateFactory(
            @NonNull final HederaOperations hederaOperations,
            @NonNull final HederaNativeOperations hederaNativeOperations,
            @NonNull final CodeFactory codeFactory,
            @NonNull final EvmHookState hook) {
        this.hederaOperations = Objects.requireNonNull(hederaOperations);
        this.hederaNativeOperations = Objects.requireNonNull(hederaNativeOperations);
        this.codeFactory = Objects.requireNonNull(codeFactory);
        this.hook = Objects.requireNonNull(hook);
    }

    @Override
    public EvmFrameState get() {
        return new HookEvmFrameState(
                hederaNativeOperations,
                hederaOperations.getStore(),
                hederaNativeOperations.readableHookStore(),
                codeFactory,
                hook);
    }
}
