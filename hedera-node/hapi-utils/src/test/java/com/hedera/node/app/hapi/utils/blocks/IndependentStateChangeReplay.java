// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import com.google.protobuf.DynamicMessage;
import java.util.List;

/** Independent stock-protobuf reference, deliberately not using the production byte splicer. */
final class IndependentStateChangeReplay {
    private IndependentStateChangeReplay() {}

    static DynamicMessage reference(DynamicMessage prior, DynamicMessage partial, List<Integer> cleared) {
        final var builder = prior.toBuilder();
        for (int number : cleared)
            builder.clearField(prior.getDescriptorForType().findFieldByNumber(number));
        partial.getAllFields().forEach(builder::setField);
        return builder.build();
    }
}
