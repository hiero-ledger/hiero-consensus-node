// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprLocalLedgerMetadata;

public class V0700ClprSchema extends Schema<SemanticVersion> {
    public static final String CLPR_LEDGER_CONFIGURATIONS_STATE_KEY =
            StateKey.KeyOneOfType.CLPRSERVICE_I_CONFIGURATIONS.toString();
    public static final int CLPR_LEDGER_CONFIGURATIONS_STATE_ID =
            StateKey.KeyOneOfType.CLPRSERVICE_I_CONFIGURATIONS.protoOrdinal();
    private static final long MAX_LEDGER_CONFIGURATION_ENTRIES = 50_000L;

    public static final String CLPR_LEDGER_METADATA_STATE_KEY = StateKey.KeyOneOfType.CLPRSERVICE_I_METADATA.toString();
    public static final int CLPR_LEDGER_METADATA_STATE_ID = SingletonType.CLPRSERVICE_I_METADATA.protoOrdinal();

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(70).patch(0).build();

    public V0700ClprSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(ledgerConfigurationMap(), ledgerMetadataSingleton());
    }

    private static StateDefinition<ClprLedgerId, ClprLedgerConfiguration> ledgerConfigurationMap() {
        return StateDefinition.onDisk(
                CLPR_LEDGER_CONFIGURATIONS_STATE_ID,
                CLPR_LEDGER_CONFIGURATIONS_STATE_KEY,
                ClprLedgerId.PROTOBUF,
                ClprLedgerConfiguration.PROTOBUF,
                MAX_LEDGER_CONFIGURATION_ENTRIES);
    }

    private static StateDefinition ledgerMetadataSingleton() {
        return StateDefinition.singleton(
                CLPR_LEDGER_METADATA_STATE_ID, CLPR_LEDGER_METADATA_STATE_KEY, ClprLocalLedgerMetadata.PROTOBUF);
    }
}
