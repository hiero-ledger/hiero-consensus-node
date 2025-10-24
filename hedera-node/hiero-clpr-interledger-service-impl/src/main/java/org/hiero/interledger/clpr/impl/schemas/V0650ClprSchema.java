// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.StateKey;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;

public class V0650ClprSchema extends Schema<SemanticVersion> {
    // The protobuf field number for the StateKey/StateValue entry in `virtual_map_state.proto`.
    // This must match the `= 53` entry in that proto for ClprService_I_CONFIGURATIONS.
    // Prefer using the generated enum constant (StateKey.KeyOneOfType.CLPRSERVICE_I_CONFIGURATIONS)
    public static final String CLPR_LEDGER_CONFIGURATIONS_STATE_KEY =
            StateKey.KeyOneOfType.CLPRSERVICE_I_CONFIGURATIONS.toString();
    public static final int CLPR_LEDGER_CONFIGURATIONS_STATE_ID =
            StateKey.KeyOneOfType.CLPRSERVICE_I_CONFIGURATIONS.protoOrdinal();
    // TODO: Determine the appropriate max given ephemeral spheres creating definitions
    private static final long MAX_LEDGER_CONFIGURATION_ENTRIES = 50_000L;

    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(65).patch(0).build();

    /**
     * Create a new instance
     */
    public V0650ClprSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(ledgerConfigurationMap());
    }

    private static StateDefinition<ClprLedgerId, ClprLedgerConfiguration> ledgerConfigurationMap() {
        // Call the public overload of StateDefinition.onDisk that accepts the state id
        return StateDefinition.onDisk(
                CLPR_LEDGER_CONFIGURATIONS_STATE_ID,
                CLPR_LEDGER_CONFIGURATIONS_STATE_KEY,
                ClprLedgerId.PROTOBUF,
                ClprLedgerConfiguration.PROTOBUF,
                MAX_LEDGER_CONFIGURATION_ENTRIES);
    }
}
