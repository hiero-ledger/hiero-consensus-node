// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.tss.schemas.V0560TssBaseSchema;
import com.hedera.node.app.tss.schemas.V0580TssBaseSchema;
import com.hedera.node.app.tss.schemas.V059TssBaseSchema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Default implementation of the {@link TssBaseService}.
 */
@Deprecated(forRemoval = true, since = "0.59.0")
public class TssBaseServiceImpl implements TssBaseService {
    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V0560TssBaseSchema());
        registry.register(new V0580TssBaseSchema());
        registry.register(new V059TssBaseSchema());
    }
}
