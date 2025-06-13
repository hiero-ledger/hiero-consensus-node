// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.netty;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A Netty handler for processing REST requests.
 * It supports GET requests and routes them to appropriate handlers based on the request URI.
 */
public class RestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final Map<String, Consumer<RestContext>> getRoutes;

    /**
     * Constructs a RestHandler with the specified GET routes.
     *
     * @param getRoutes a map of URI paths to their corresponding handler functions
     */
    public RestHandler(@NonNull final Map<String, Consumer<RestContext>> getRoutes) {
        this.getRoutes = getRoutes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void channelRead0(@NonNull final ChannelHandlerContext ctx, @NonNull final FullHttpRequest req) {
        if (req.method() == HttpMethod.GET && getRoutes.containsKey(req.uri())) {
            getRoutes.get(req.uri()).accept(new RestContext(ctx));
        } else {
            RestContext.send404(ctx);
        }
    }
}
