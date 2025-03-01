// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.lambda;

import static com.hedera.hapi.node.hooks.HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;

import com.hedera.hapi.node.hooks.CallerPays;
import com.hedera.hapi.node.hooks.EvmHookSpec;
import com.hedera.hapi.node.hooks.HookChargingSpec;
import com.hedera.hapi.node.hooks.HookInstall;
import com.hedera.hapi.node.state.hooks.EvmHookType;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.SpecOperation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

public class HookInstaller {
    private static final long NO_EXPLICIT_INDEX = -1L;
    private static final long NO_DEFAULT_GAS_LIMIT = -1L;
    private static final HookChargingSpec DEFAULT_CHARGING_SPEC =
            HookChargingSpec.newBuilder().callerPays(CallerPays.DEFAULT).build();

    private record InitcodeSource(
            @Nullable String initcodeResource,
            @Nullable Bytes initcode,
            long gasLimit,
            @NonNull UploadMethod uploadMethod,
            @NonNull Object... args) {
        enum UploadMethod {
            INLINE,
            FILE
        }
    }

    @Nullable
    private final InitcodeSource initcodeSource;

    private final EvmHookType type;
    private final HookChargingSpec chargingSpec;
    private final long defaultGasLimit;

    private long index;

    public static HookInstaller lambdaBytecode() {
        return new HookInstaller(
                null, EvmHookType.LAMBDA, DEFAULT_CHARGING_SPEC, NO_DEFAULT_GAS_LIMIT, NO_EXPLICIT_INDEX);
    }

    public HookInstaller atIndex(final long index) {
        this.index = index;
        return this;
    }

    private HookInstaller(
            @Nullable final InitcodeSource initcodeSource,
            @NonNull final EvmHookType type,
            @NonNull final HookChargingSpec chargingSpec,
            final long defaultGasLimit,
            final long index) {
        this.initcodeSource = initcodeSource;
        this.type = Objects.requireNonNull(type);
        this.chargingSpec = Objects.requireNonNull(chargingSpec);
        this.defaultGasLimit = defaultGasLimit;
        this.index = index;
    }

    public SpecOperation specSetupOp() {
        return noOp();
    }

    public HookInstall op() {
        final var builder = HookInstall.newBuilder()
                .extensionPoint(ACCOUNT_ALLOWANCE_HOOK)
                .index(index)
                .chargingSpec(chargingSpec);
        final var specBuilder = EvmHookSpec.newBuilder();
        if (defaultGasLimit != NO_DEFAULT_GAS_LIMIT) {
            specBuilder.defaultGasLimit(defaultGasLimit);
        }
        throw new AssertionError("Not implemented");
    }
}
