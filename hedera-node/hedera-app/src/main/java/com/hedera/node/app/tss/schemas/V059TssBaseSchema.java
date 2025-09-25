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
    public Set<Integer> statesToRemove() {
        return Set.of(
                V0560TssBaseSchema.TSS_MESSAGES_STATE_ID,
                V0560TssBaseSchema.TSS_VOTES_STATE_ID,
                V0580TssBaseSchema.TSS_ENCRYPTION_KEYS_STATE_ID);
    }
}
