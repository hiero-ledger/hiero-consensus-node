// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A simple REST server using Netty that handles GET and POST requests.
 * It allows adding custom handlers for specific paths.
 */
public class NettyRestServer {

    private static final Logger log = LogManager.getLogger();

    private final int port;
    private final Map<String, Function<FullHttpRequest, Object>> getRoutes = new HashMap<>();
    private final Map<String, BiFunction<FullHttpRequest, byte[], Object>> postRoutes = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Constructs a NettyRestServer that listens on the specified port.
     *
     * @param port the port to listen on
     */
    public NettyRestServer(final int port) {
        this.port = port;
    }

    /**
     * Adds a GET route with the specified path and handler.
     *
     * @param path    the path for the GET request
     * @param handler the handler function that processes the request
     */
    public void addGet(@NonNull final String path, @NonNull final Function<FullHttpRequest, Object> handler) {
        getRoutes.put(path, handler);
    }

    /**
     * Adds a POST route with the specified path and handler.
     *
     * @param path    the path for the POST request
     * @param handler the handler function that processes the request and accepts the request body
     */
    public void addPost(@NonNull final String path, @NonNull final BiFunction<FullHttpRequest, byte[], Object> handler) {
        postRoutes.put(path, handler);
    }

    /**
     * Starts the Netty server and binds it to the specified port.
     *
     * @throws InterruptedException if the server is interrupted while starting
     */
    public void start() throws InterruptedException {
        final EventLoopGroup boss = new NioEventLoopGroup(1);
        final EventLoopGroup worker = new NioEventLoopGroup();

        try {
            final ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(65536));
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                @Override
                                protected void channelRead0(@NonNull final ChannelHandlerContext ctx, @NonNull final FullHttpRequest request) {
                                    final String uri = request.uri().split("\\?")[0];
                                    final HttpMethod method = request.method();
                                    Object result = null;

                                    try {
                                        if (HttpMethod.GET.equals(method) && getRoutes.containsKey(uri)) {
                                            result = getRoutes.get(uri).apply(request);
                                        } else if (HttpMethod.POST.equals(method) && postRoutes.containsKey(uri)) {
                                            final ByteBuf content = request.content();
                                            final byte[] body = new byte[content.readableBytes()];
                                            content.readBytes(body);
                                            result = postRoutes.get(uri).apply(request, body);
                                        } else {
                                            sendResponse(ctx, HttpResponseStatus.NOT_FOUND, "Not found");
                                            return;
                                        }
                                        final byte[] json = objectMapper.writeValueAsBytes(result);
                                        sendResponse(ctx, HttpResponseStatus.OK, json);
                                    } catch (final IllegalArgumentException e) {
                                        sendResponse(
                                                ctx,
                                                HttpResponseStatus.BAD_REQUEST,
                                                "Invalid request: " + e.getMessage());
                                    } catch (final IllegalStateException e) {
                                        sendResponse(ctx, HttpResponseStatus.CONFLICT, e.getMessage());
                                    } catch (final Throwable e) {
                                        sendResponse(
                                                ctx,
                                                HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                                "Error: " + e.getMessage());
                                    }
                                }

                                private void sendResponse(
                                        @NonNull final ChannelHandlerContext ctx, @NonNull final HttpResponseStatus status, @NonNull final String message) {
                                    sendResponse(ctx, status, message.getBytes(StandardCharsets.UTF_8));
                                }

                                private void sendResponse(
                                        @NonNull final ChannelHandlerContext ctx, @NonNull final HttpResponseStatus status, @NonNull final byte[] bytes) {
                                    final FullHttpResponse response = new DefaultFullHttpResponse(
                                            HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
                                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
                                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                                }
                            });
                        }
                    });

            final Channel ch = bootstrap.bind(port).sync().channel();
            log.info("Server started on http://localhost:{}", port);
            ch.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}
