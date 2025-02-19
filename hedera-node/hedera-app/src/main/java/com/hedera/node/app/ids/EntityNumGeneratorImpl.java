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

package com.hedera.node.app.ids;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.spi.ids.EntityNumGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;

/**
 * Default implementation of {@link EntityNumGenerator}.
 */
public class EntityNumGeneratorImpl implements EntityNumGenerator {

    private final WritableEntityIdStore entityIdStore;

    @Inject
    public EntityNumGeneratorImpl(@NonNull final WritableEntityIdStore entityIdStore) {
        this.entityIdStore = requireNonNull(entityIdStore);
    }

    @Override
    public long newEntityNum() {
        return entityIdStore.incrementAndGet();
    }

    @Override
    public long peekAtNewEntityNum() {
        return entityIdStore.peekAtNextNumber();
    }
}
