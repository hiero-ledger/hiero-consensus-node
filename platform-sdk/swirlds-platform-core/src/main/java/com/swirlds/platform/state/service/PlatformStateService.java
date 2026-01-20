// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service;

import static java.util.Objects.requireNonNull;

import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.List;

/**
 * A service that provides the schema for the platform state, used by {@link State}
 * to implement accessors to the platform state.
 */
public enum PlatformStateService implements Service {
    PLATFORM_STATE_SERVICE;
    /**
     * The schemas to register with the {@link SchemaRegistry}.
     */
    private static final Collection<Schema> SCHEMAS = List.of(new V0540PlatformStateSchema());

    public static final String NAME = "PlatformStateService";

    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        SCHEMAS.forEach(registry::register);
    }
}
