// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public class NettyRestServer {

    private final int port;
    private final Map<String, Function<FullHttpRequest, Object>> getRoutes = new HashMap<>();
    private final Map<String, BiFunction<FullHttpRequest, byte[], Object>> postRoutes = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NettyRestServer(int port) {
        this.port = port;
    }

    public void addGet(String path, Function<FullHttpRequest, Object> handler) {
        getRoutes.put(path, handler);
    }

    public void addPost(String path, BiFunction<FullHttpRequest, byte[], Object> handler) {
        postRoutes.put(path, handler);
    }

    public void start() throws InterruptedException {
        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(65536));
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                                    String uri = request.uri().split("\\?")[0];
                                    HttpMethod method = request.method();
                                    Object result = null;

                                    try {
                                        if (HttpMethod.GET.equals(method) && getRoutes.containsKey(uri)) {
                                            result = getRoutes.get(uri).apply(request);
                                        } else if (HttpMethod.POST.equals(method) && postRoutes.containsKey(uri)) {
                                            ByteBuf content = request.content();
                                            byte[] body = new byte[content.readableBytes()];
                                            content.readBytes(body);
                                            result = postRoutes.get(uri).apply(request, body);
                                        } else {
                                            sendResponse(ctx, HttpResponseStatus.NOT_FOUND, "Not found");
                                            return;
                                        }
                                        byte[] json = objectMapper.writeValueAsBytes(result);
                                        sendResponse(ctx, HttpResponseStatus.OK, json);
                                    } catch (Exception e) {
                                        sendResponse(
                                                ctx,
                                                HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                                "Error: " + e.getMessage());
                                    }
                                }

                                private void sendResponse(
                                        ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
                                    sendResponse(ctx, status, message.getBytes(StandardCharsets.UTF_8));
                                }

                                private void sendResponse(
                                        ChannelHandlerContext ctx, HttpResponseStatus status, byte[] bytes) {
                                    FullHttpResponse response = new DefaultFullHttpResponse(
                                            HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
                                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
                                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                                }
                            });
                        }
                    });

            Channel ch = bootstrap.bind(port).sync().channel();
            System.out.println("Server started on http://localhost:" + port);
            ch.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}
