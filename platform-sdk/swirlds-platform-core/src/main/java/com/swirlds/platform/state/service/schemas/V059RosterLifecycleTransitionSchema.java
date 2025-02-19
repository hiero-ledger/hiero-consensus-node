/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.state.service.schemas;

import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_KEY;
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
        final var stateSingleton = ctx.newStates().<PlatformState>getSingleton(PLATFORM_STATE_KEY);
        final var state = requireNonNull(stateSingleton.get());
        // Null out the legacy address book fields
        stateSingleton.put(state.copyBuilder()
                .previousAddressBook((com.hedera.hapi.platform.state.AddressBook) null)
                .addressBook(((com.hedera.hapi.platform.state.AddressBook) null))
                .build());
    }
}
