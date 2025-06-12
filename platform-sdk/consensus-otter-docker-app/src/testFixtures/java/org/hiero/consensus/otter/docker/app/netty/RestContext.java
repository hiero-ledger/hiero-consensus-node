// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

public record RestContext(ChannelHandlerContext ctx) {
    public void ok(String content) {
        byte[] bytes = content.getBytes();
        FullHttpResponse response =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    public static void send404(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
