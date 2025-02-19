/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.ids;

import com.hedera.node.app.ids.schemas.V0490EntityIdSchema;
import com.hedera.node.app.ids.schemas.V0590EntityIdSchema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Service for providing incrementing entity id numbers. It stores the most recent entity id in state.
 */
public class EntityIdService implements Service {
    public static final String NAME = "EntityIdService";

    /** {@inheritDoc} */
    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    /** {@inheritDoc} */
    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490EntityIdSchema());
        registry.register(new V0590EntityIdSchema());
    }
}
