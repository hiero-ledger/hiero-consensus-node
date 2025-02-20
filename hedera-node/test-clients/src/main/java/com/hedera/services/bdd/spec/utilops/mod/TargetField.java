// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import com.google.protobuf.Descriptors;

public record TargetField(Descriptors.FieldDescriptor descriptor, boolean isInScheduledTransaction) {
    public String name() {
        return descriptor.getFullName();
    }
}
