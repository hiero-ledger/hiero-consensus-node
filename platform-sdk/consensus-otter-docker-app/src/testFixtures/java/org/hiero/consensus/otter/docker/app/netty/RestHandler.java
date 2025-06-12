// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import java.util.Map;
import java.util.function.Consumer;

public class RestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final Map<String, Consumer<RestContext>> getRoutes;

    public RestHandler(final Map<String, Consumer<RestContext>> getRoutes) {
        this.getRoutes = getRoutes;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest req) {
        if (req.method() == HttpMethod.GET && getRoutes.containsKey(req.uri())) {
            getRoutes.get(req.uri()).accept(new RestContext(ctx));
        } else {
            RestContext.send404(ctx);
        }
    }
}
