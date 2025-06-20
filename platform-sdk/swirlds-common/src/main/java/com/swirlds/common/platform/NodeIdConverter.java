// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.platform;

import com.swirlds.config.api.converter.ConfigConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;

/**
 * Implementation of {@link ConfigConverter} that supports {@link NodeId} as data type for the config.
 */
public class NodeIdConverter implements ConfigConverter<NodeId> {

    @Override
    public NodeId convert(@NonNull final String value) throws IllegalArgumentException, NullPointerException {
        Objects.requireNonNull(value, "Parameter 'value' cannot be null");
        return NodeId.of(Long.parseLong(value.trim()));
    }
}
