// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss.schemas;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Schema removing the states added in {@code 0.56.0} and {@code 0.58.0} for the inexact weights TSS scheme.
 */
@Deprecated(forRemoval = true, since = "0.59.0")
public class V059TssBaseSchema extends Schema {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(59).build();

    public V059TssBaseSchema() {
        super(VERSION);
    }

    @NonNull
    @Override
    public Set<String> statesToRemove() {
        return Set.of("TSS_MESSAGES", "TSS_VOTES", "TSS_ENCRYPTION_KEYS");
    }
}
