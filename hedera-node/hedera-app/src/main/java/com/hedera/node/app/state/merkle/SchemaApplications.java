// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import static com.hedera.node.app.state.merkle.SchemaApplicationType.MIGRATION;
import static com.hedera.node.app.state.merkle.SchemaApplicationType.RESTART;
import static com.hedera.node.app.state.merkle.SchemaApplicationType.STATE_DEFINITIONS;
import static com.hedera.node.app.state.merkle.VersionUtils.alreadyIncludesStateDefs;
import static com.hedera.node.app.state.merkle.VersionUtils.isSameVersion;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.EnumSet;
import java.util.Set;

/**
 * Analyzes the ways in which the {@link MerkleSchemaRegistry} should apply a {@link Schema}
 * to the {@link MerkleNodeState}.
 *
 * @see SchemaApplicationType
 */
public class SchemaApplications {
    /**
     * Computes the {@link SchemaApplicationType}s for the given {@link Schema}.
     *
     * @param deserializedVersion the version of the deserialized state
     * @param latestVersion the latest schema version of the relevant service
     * @param schema the schema to analyze
     * @param config the configuration of the node
     * @return the ways the schema should be applied
     */
    public Set<SchemaApplicationType> computeApplications(
            @Nullable final SemanticVersion deserializedVersion,
            @NonNull final SemanticVersion latestVersion,
            @NonNull final Schema<SemanticVersion> schema,
            @NonNull final Configuration config) {
        requireNonNull(schema);
        requireNonNull(config);
        requireNonNull(latestVersion);
        final var uses = EnumSet.noneOf(SchemaApplicationType.class);
        // Always add state definitions, even if later schemas will remove them all
        if (hasStateDefinitions(schema, config)) {
            uses.add(STATE_DEFINITIONS);
        }
        // We only skip migration if the deserialized version is at least as new as the schema
        // version (which implies the deserialized state already went through this migration)
        if (!alreadyIncludesStateDefs(deserializedVersion, schema.getVersion())) {
            uses.add(MIGRATION);
        }
        // We only do restart if the schema is the latest one available
        if (isSameVersion(latestVersion, schema.getVersion())) {
            uses.add(RESTART);
        }
        return uses;
    }

    private boolean hasStateDefinitions(@NonNull final Schema schema, @NonNull final Configuration config) {
        return !schema.statesToCreate(config).isEmpty()
                || !schema.statesToRemove().isEmpty();
    }
}
