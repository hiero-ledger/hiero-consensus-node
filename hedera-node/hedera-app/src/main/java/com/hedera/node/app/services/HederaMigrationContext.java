// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.MigrationContext;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link MigrationContext} for use when migrating a node from a previous version of Hedera.
 */
public interface HederaMigrationContext extends MigrationContext<SemanticVersion> {

    /**
     * Returns the startup networks in use.
     */
    @NonNull
    StartupNetworks startupNetworks();
}
