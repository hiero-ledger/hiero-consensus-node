// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.schemas;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;

public class V0650ClprSchema extends Schema {
    public static final String CLPR_LEDGER_CONFIGURATION_KEY = "CLPR_LEDGER_CONFIGURATION";
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
        super(VERSION);
    }

    @SuppressWarnings("rawtypes")
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(ledgerConfigurationMap());
    }

    private static StateDefinition<ClprLedgerId, ClprLedgerConfiguration> ledgerConfigurationMap() {
        return StateDefinition.onDisk(
                CLPR_LEDGER_CONFIGURATION_KEY,
                ClprLedgerId.PROTOBUF,
                ClprLedgerConfiguration.PROTOBUF,
                MAX_LEDGER_CONFIGURATION_ENTRIES);
    }
}
