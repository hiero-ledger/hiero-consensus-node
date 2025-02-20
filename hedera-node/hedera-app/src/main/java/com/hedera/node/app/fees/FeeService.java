// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import com.hedera.node.app.fees.schemas.V0490FeeSchema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;

public class FeeService implements Service {
    public static final String NAME = "FeeService";

    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490FeeSchema());
    }
}
