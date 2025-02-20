// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import com.google.protobuf.Descriptors;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TopicID;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Implementation support for a {@link ModificationStrategy} that clears
 * entity ids from a target message.
 *
 * @param <T> the type of modification used by the inheriting strategy
 */
public abstract class IdClearingStrategy<T> implements ModificationStrategy<T> {
    @Override
    public boolean hasTarget(@NonNull Descriptors.FieldDescriptor fieldDescriptor, @NonNull Object value) {
        return value instanceof AccountID
                || value instanceof ContractID
                || value instanceof ScheduleID
                || value instanceof FileID
                || value instanceof TokenID
                || value instanceof TopicID;
    }
}
