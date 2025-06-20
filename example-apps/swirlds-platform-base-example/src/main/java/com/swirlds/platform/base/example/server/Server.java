// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.server;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.spi.HttpServerProvider;
import com.swirlds.platform.base.example.ext.BaseContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Http Server manager utils
 */
public class Server {
    private static final Logger logger = LogManager.getLogger(Server.class);

    private Server() {}

    /**
     * Creates and starts a Http server with a set of defined handlers
     */
    public static void start(final @NonNull BaseContext context, final @NonNull HttpHandlerRegistry... registries)
            throws IOException {
        final BaseExampleRestApiConfig config = context.configuration().getConfigData(BaseExampleRestApiConfig.class);
        // Create HTTP server instance
        final HttpServerProvider provider = HttpServerProvider.provider();
        final HttpServer server = provider.createHttpServer(new InetSocketAddress(config.host(), config.port()), 0);

        Arrays.stream(registries)
                .map(f -> f.handlers(context))
                .flatMap(Set::stream)
                .forEach(h -> {
                    final String url = config.basePath() + "/" + h.path();
                    HttpContext httpContext = server.createContext(url);
                    httpContext.setHandler(h);
                    logger.info("Registered handler {}: {}", h.getClass().getSimpleName(), url);
                });

        ServerMetrics.registerMetrics(context.metrics());
        // Start the server
        server.start();

        if (config.banner()) {
            logger.info("\n              _      _     _           _                    \n"
                    + " _____      _(_)_ __| | __| |___      | |__   __ _ ___  ___ \n"
                    + "/ __\\ \\ /\\ / / | '__| |/ _` / __|_____| '_ \\ / _` / __|/ _ \\\n"
                    + "\\__ \\\\ V  V /| | |  | | (_| \\__ \\_____| |_) | (_| \\__ \\  __/\n"
                    + "|___/ \\_/\\_/ |_|_|  |_|\\__,_|___/ _   |_.__/ \\__,_|___/\\___|\n"
                    + " ___  __ _ _ __ ___  _ __ | | ___| |                        \n"
                    + "/ __|/ _` | '_ ` _ \\| '_ \\| |/ _ \\ |                        \n"
                    + "\\__ \\ (_| | | | | | | |_) | |  __/_|                        \n"
                    + "|___/\\__,_|_| |_| |_| .__/|_|\\___(_) ");
        }
        logger.info("Server started on {}:{}", config.host(), config.port());
    }
}
