// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.netty;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * A context for handling REST requests in a Netty server.
 * Provides methods to send HTTP responses.
 */
public record RestContext(ChannelHandlerContext ctx) {
    /**
     * Sends an HTTP 200 OK response with the specified content.
     *
     * @param content the content to include in the response body
     */
    public void ok(@NonNull final String content) {
        final byte[] bytes = content.getBytes();
        final FullHttpResponse response =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Sends an HTTP 400 Bad Request response with the specified error message.
     *
     * @param ctx the ChannelHandlerContext to write the response to
     */
    public static void send404(@NonNull final ChannelHandlerContext ctx) {
        final FullHttpResponse response =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
