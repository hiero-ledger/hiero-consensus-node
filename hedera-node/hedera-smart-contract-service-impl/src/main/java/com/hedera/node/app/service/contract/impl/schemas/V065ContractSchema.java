// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.hapi.node.state.hooks.LambdaSlotKey;
import com.hedera.hapi.platform.state.StateKey;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * The state definitions for <a href="https://hips.hedera.com/hip/hip-1195">HIP-1195, "Hiero hooks and an application to allowances"</a>.
 * These include,
 * <ul>
 *     <li>A key/value state for EVM hook metadata.</li>
 *     <li>A key/value state for lambda hook storage slots.</li>
 * </ul>
 */
public class V065ContractSchema extends Schema<SemanticVersion> {

    private static final int MAX_LAMBDA_STORAGE = 1_000_000;
    private static final int MAX_EVM_HOOK_STATES = 50_000;

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(65).build();

    public static final String EVM_HOOK_STATES_KEY = "EVM_HOOK_STATES";
    public static final int EVM_HOOK_STATES_STATE_ID =
            StateKey.KeyOneOfType.CONTRACTSERVICE_I_EVM_HOOK_STATES.protoOrdinal();

    public static final String LAMBDA_STORAGE_KEY = "LAMBDA_STORAGE";
    public static final int LAMBDA_STORAGE_STATE_ID =
            StateKey.KeyOneOfType.CONTRACTSERVICE_I_LAMBDA_STORAGE.protoOrdinal();

    public V065ContractSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.onDisk(
                        EVM_HOOK_STATES_STATE_ID,
                        EVM_HOOK_STATES_KEY,
                        HookId.PROTOBUF,
                        EvmHookState.PROTOBUF,
                        MAX_EVM_HOOK_STATES),
                StateDefinition.onDisk(
                        LAMBDA_STORAGE_STATE_ID,
                        LAMBDA_STORAGE_KEY,
                        LambdaSlotKey.PROTOBUF,
                        SlotValue.PROTOBUF,
                        MAX_LAMBDA_STORAGE));
    }
}
