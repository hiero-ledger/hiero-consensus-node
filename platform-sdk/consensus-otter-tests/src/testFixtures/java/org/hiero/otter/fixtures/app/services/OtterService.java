// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This interface defines a service of the Otter application.
 */
public interface OtterService {

    /**
     * Get the name of this service.
     *
     * @return the name of this service
     */
    @NonNull
    String name();

    /**
     * Get the schema for the genesis state of this service.
     *
     * @param version the current software version
     * @return the schema for the genesis state of this service
     */
    @NonNull
    Schema genesisSchema(@NonNull SemanticVersion version);
}
