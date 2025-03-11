// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service.schemas;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A to null out the legacy {@link PlatformState} address book fields. Can be
 * removed after no production states use these fields.
 */
@Deprecated
public class V059RosterLifecycleTransitionSchema extends Schema {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(59).build();

    public V059RosterLifecycleTransitionSchema() {
        super(VERSION);
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        requireNonNull(ctx);
        // AddressBooks have been removed from the PlatformState, and so this migrate() is now a no-op.
        // The Services team may remove this schema when and how appropriate.
    }
}
