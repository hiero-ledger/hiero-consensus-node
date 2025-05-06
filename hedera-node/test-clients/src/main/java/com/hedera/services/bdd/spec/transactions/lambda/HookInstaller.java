// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.lambda;

import static com.hedera.hapi.node.hooks.HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;

import com.hedera.hapi.node.hooks.CallerPays;
import com.hedera.hapi.node.hooks.EvmHookSpec;
import com.hedera.hapi.node.hooks.HookChargingSpec;
import com.hedera.hapi.node.hooks.HookInstall;
import com.hedera.hapi.node.hooks.LambdaEvmHook;
import com.hedera.hapi.node.hooks.PureEvmHook;
import com.hedera.hapi.node.state.hooks.EvmHookType;
import com.hedera.services.bdd.spec.SpecOperation;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.Objects;

/**
 * Factory class for creating {@link HookInstall} operations.
 */
public class HookInstaller {
    private static final long NO_DEFAULT_GAS_LIMIT = -1L;
    private static final HookChargingSpec DEFAULT_CHARGING_SPEC =
            HookChargingSpec.newBuilder().callerPays(CallerPays.DEFAULT).build();

    private final long index;
    private final EvmHookType type;
    private final HookChargingSpec chargingSpec;
    private final long defaultGasLimit;


    /**
     * Returns a {@link HookInstaller} for a lambda hook using the given index.
     * @param index the index of the hook
     * @return a {@link HookInstaller} for a lambda hook
     */
    public static HookInstaller lambdaAt(final long index) {
        return new HookInstaller(
                EvmHookType.LAMBDA, DEFAULT_CHARGING_SPEC, NO_DEFAULT_GAS_LIMIT, index);
    }

    private HookInstaller(
            @NonNull final EvmHookType type,
            @NonNull final HookChargingSpec chargingSpec,
            final long defaultGasLimit,
            final long index) {
        this.type = Objects.requireNonNull(type);
        this.chargingSpec = Objects.requireNonNull(chargingSpec);
        this.defaultGasLimit = defaultGasLimit;
        this.index = index;
    }

    public SpecOperation specSetupOp() {
        return noOp();
    }

    /**
     * Returns the {@link HookInstall} operation.
     */
    public HookInstall getInstallation() {
        final var specBuilder = EvmHookSpec.newBuilder();
        if (defaultGasLimit != NO_DEFAULT_GAS_LIMIT) {
            specBuilder.defaultGasLimit(defaultGasLimit);
        }
        final var builder = HookInstall.newBuilder()
                .extensionPoint(ACCOUNT_ALLOWANCE_HOOK)
                .index(index)
                .chargingSpec(chargingSpec);
        switch (type) {
            case PURE -> builder.pureEvmHook(PureEvmHook.newBuilder().spec(specBuilder));
            case LAMBDA -> builder.lambdaEvmHook(LambdaEvmHook.newBuilder().spec(specBuilder).build());
        }
        return builder.build();
    }
}
