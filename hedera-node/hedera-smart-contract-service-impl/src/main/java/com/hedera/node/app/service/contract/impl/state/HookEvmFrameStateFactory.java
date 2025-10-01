// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.hapi.node.base.HookId;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
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
    private final WritableEvmHookStore evmHookStore;
    private final HookId hookId;

    public HookEvmFrameStateFactory(
            @NonNull final HederaOperations hederaOperations,
            @NonNull final HederaNativeOperations hederaNativeOperations,
            @NonNull final CodeFactory codeFactory,
            @NonNull final WritableEvmHookStore evmHookStore,
            @NonNull final HookId hookId) {
        this.hederaOperations = Objects.requireNonNull(hederaOperations);
        this.hederaNativeOperations = Objects.requireNonNull(hederaNativeOperations);
        this.codeFactory = Objects.requireNonNull(codeFactory);
        this.evmHookStore = Objects.requireNonNull(evmHookStore);
        this.hookId = Objects.requireNonNull(hookId);
    }

    @Override
    public EvmFrameState get() {
        return new HookEvmFrameState(
                hederaNativeOperations, hederaOperations.getStore(), codeFactory, evmHookStore, hookId);
    }
}
