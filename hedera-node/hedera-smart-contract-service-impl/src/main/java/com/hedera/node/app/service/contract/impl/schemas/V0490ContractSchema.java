// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.schemas;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.platform.state.StateKey;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * The schema for the {@code v0.49.0} version of the contract service. Since {@code v0.49.7} was
 * the first release of the modularized contract service, this schema defines states to create
 * for both the contract storage and bytecode.
 */
public class V0490ContractSchema extends Schema {

    private static final int MAX_BYTECODES = 50_000;
    private static final int MAX_STORAGE_ENTRIES = 1_000_000;

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(49).patch(0).build();

    public static final String STORAGE_KEY = "STORAGE";
    public static final int STORAGE_STATE_ID = StateKey.KeyOneOfType.CONTRACTSERVICE_I_STORAGE.protoOrdinal();

    public static final String BYTECODE_KEY = "BYTECODE";
    public static final int BYTECODE_STATE_ID = StateKey.KeyOneOfType.CONTRACTSERVICE_I_BYTECODE.protoOrdinal();

    public V0490ContractSchema() {
        super(VERSION);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        // There are no contracts at genesis
    }

    @Override
    @SuppressWarnings("rawtypes")
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(storageDef(), bytecodeDef());
    }

    private @NonNull StateDefinition<SlotKey, SlotValue> storageDef() {
        return StateDefinition.onDisk(
                STORAGE_STATE_ID, STORAGE_KEY, SlotKey.PROTOBUF, SlotValue.PROTOBUF, MAX_STORAGE_ENTRIES);
    }

    private @NonNull StateDefinition<ContractID, Bytecode> bytecodeDef() {
        return StateDefinition.onDisk(
                BYTECODE_STATE_ID, BYTECODE_KEY, ContractID.PROTOBUF, Bytecode.PROTOBUF, MAX_BYTECODES);
    }
}
