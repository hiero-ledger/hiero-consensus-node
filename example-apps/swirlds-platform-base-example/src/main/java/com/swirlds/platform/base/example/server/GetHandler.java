// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.server;

import com.swirlds.platform.base.example.ext.BaseContext;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link HttpHandlerDefinition} that deals with get requests
 */
public class GetHandler<T> extends GenericHandler<T> {

    public GetHandler(
            @NonNull final String path,
            @NonNull final BaseContext context,
            @NonNull final Class<T> consumeType,
            @NonNull final GetHandler getHandler) {
        super(path, context, consumeType);
        setGetHandler(getHandler);
    }
}
