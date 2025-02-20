// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.server;

import com.swirlds.platform.base.example.ext.BaseContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

/**
 * A PostHandler that does not return a result to the client
 */
public class PostTriggerHandler<T> extends GenericHandler<T> {

    public PostTriggerHandler(
            @NonNull final String path,
            @NonNull final BaseContext context,
            @NonNull final Class<T> consumeType,
            @NonNull final Consumer<T> consumer) {
        super(path, context, consumeType);
        setPostHandler(v -> {
            consumer.accept(v);
            return null;
        });
    }
}
