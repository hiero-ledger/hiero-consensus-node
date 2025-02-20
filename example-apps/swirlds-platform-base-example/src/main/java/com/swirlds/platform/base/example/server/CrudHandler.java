// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.server;

import com.swirlds.platform.base.example.ext.BaseContext;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A http handler that delegates to a {@link CrudService}
 */
public class CrudHandler<T> extends GenericHandler<T> {

    /**
     * Creates a {@link HttpHandlerDefinition} that delegates to a {@link CrudService}
     *
     * @param path the path this handler will manage
     * @param context {@link BaseContext} to use
     * @param service the delegated {@link CrudService}
     */
    public CrudHandler(
            @NonNull final String path, @NonNull final BaseContext context, @NonNull final CrudService<T> service) {
        super(path, context, service.getResultType());
        this.setDeleteHandler(service::delete);
        this.setPostHandler(service::create);
        this.setPutHandler(service::update);
        this.setGetHandler((id, queryParameters) -> {
            if (id != null) {
                return service.retrieve(id);
            } else {
                return service.retrieveAll(queryParameters);
            }
        });
    }
}
