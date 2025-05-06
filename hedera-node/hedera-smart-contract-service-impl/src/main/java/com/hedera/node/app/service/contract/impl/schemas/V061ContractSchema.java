// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.schemas;

import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.hapi.node.state.hooks.LambdaSlotKey;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public class V061ContractSchema extends Schema {
    private static final int MAX_LAMBDA_STORAGE = 1_000_000_000;
    private static final int MAX_EVM_HOOK_STATES = 1_000_000_000;

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(61).build();

    public static final String EVM_HOOK_STATES_KEY = "EVM_HOOK_STATES";
    public static final String LAMBDA_STORAGE_KEY = "LAMBDA_STORAGE";

    public V061ContractSchema() {
        super(VERSION);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.onDisk(
                        EVM_HOOK_STATES_KEY, HookId.PROTOBUF, EvmHookState.PROTOBUF, MAX_EVM_HOOK_STATES),
                StateDefinition.onDisk(
                        LAMBDA_STORAGE_KEY, LambdaSlotKey.PROTOBUF, SlotValue.PROTOBUF, MAX_LAMBDA_STORAGE));
    }
}
