// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.entityid.EntityNumGenerator;
import com.hedera.node.app.service.entityid.WritableEntityIdStore;
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
