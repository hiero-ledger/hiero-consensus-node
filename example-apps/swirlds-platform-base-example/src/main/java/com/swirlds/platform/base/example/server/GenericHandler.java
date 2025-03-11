// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.server;

import com.google.common.base.Preconditions;
import com.sun.net.httpserver.HttpExchange;
import com.swirlds.common.metrics.extensions.CountPerSecond;
import com.swirlds.platform.base.example.ext.BaseContext;
import com.swirlds.platform.base.example.store.persistence.exception.EntityNotFoundException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A generic {@link HttpHandlerDefinition} that deals with most generic http operations. Users can set individuals
 * {@link GetHandler} {@link PostHandler} {@link PutHandler} and {@link DeleteHandler} to customize the behaviour for
 * each individual http request.
 *
 * @param <T>
 */
public class GenericHandler<T> implements HttpHandlerDefinition {
    private static final Logger logger = LogManager.getLogger(GenericHandler.class.getName());
    private final String path;
    private final BaseContext context;
    private final Class<T> resultType;
    private final CountPerSecond tps;

    private GetHandler getHandler;
    private PostHandler<T> postHandler;
    private PutHandler<T> putHandler;
    private DeleteHandler deleteHandler;

    protected GenericHandler(
            final @NonNull String path, final @NonNull BaseContext context, final @NonNull Class<T> resultType) {
        this.path = Objects.requireNonNull(path);
        this.context = Objects.requireNonNull(context);
        this.resultType = Objects.requireNonNull(resultType);
        this.tps = new CountPerSecond(context.metrics(), ServerMetrics.REQUESTS_PER_SECOND);
    }

    @Override
    public void handle(final @NonNull HttpExchange exchange) {
        try {

            final String requestMethod = exchange.getRequestMethod();
            long start = System.nanoTime();

            final URI requestURI = exchange.getRequestURI();
            Object result = null;
            int statusCode;
            try {
                switch (requestMethod) {
                    case "GET":
                        result = handleGet(exchange);
                        statusCode = 200;
                        break;
                    case "POST":
                        result = this.handlePost(exchange);
                        statusCode = 201;
                        break;
                    case "PUT":
                        result = this.handlePut(exchange);
                        statusCode = 200;
                        break;
                    case "DELETE":
                        this.handleDelete(exchange);
                        statusCode = 202;
                        break;
                    case "OPTIONS": {
                        handleOptionsRequest(exchange);
                        return;
                    }
                    default:
                        throw new UnsupportedOperationException(requestMethod + " operation not supported");
                }
            } catch (UnsupportedOperationException e) {
                statusCode = 405;
                result = Map.of("error", "Operation not supported for " + requestMethod);
            } catch (IllegalArgumentException e) {
                statusCode = 400;
                result = Map.of("error", e.getMessage());
            } catch (EntityNotFoundException e) {
                statusCode = 404;
                result = Map.of("error", e.getEntityType() + " with code " + e.getId() + " not found");
            } catch (RuntimeException e) {
                statusCode = 500;
                result = Map.of("error", "Internal error");
            }

            long duration = System.nanoTime() - start;
            context.metrics().getOrCreate(ServerMetrics.REQUEST_TOTAL).increment();
            context.metrics().getOrCreate(ServerMetrics.REQUEST_AVG_TIME).update(duration);
            tps.count();
            if (statusCode >= 300) {
                context.metrics().getOrCreate(ServerMetrics.ERROR_TOTAL).increment();
            }
            if (result != null) {
                final String response = DataTransferUtils.serializeToJson(result);
                exchange.getResponseHeaders().put("Content-Type", contentType());
                exchange.sendResponseHeaders(statusCode, response.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } else {
                exchange.sendResponseHeaders(statusCode, 0);
            }
            exchange.close();

            logger.trace(
                    "Received Request to:{}{} took:{}ns answered status:{} body:{}",
                    requestMethod,
                    requestURI,
                    duration,
                    statusCode,
                    result);
        } catch (Exception e) {
            logger.error("Unexpected error handling {} {}", exchange.getRequestMethod(), exchange.getRequestURI(), e);
        }
    }

    /**
     * @return the contentType that will be returned in the http response
     */
    @NonNull
    protected List<String> contentType() {
        return Collections.singletonList("application/json");
    }

    protected void handleDelete(final @NonNull HttpExchange exchange) {
        requireHandler(deleteHandler, "delete");
        final String key = getIdFromExchange(exchange);
        Preconditions.checkArgument(Objects.nonNull(key), "key is required");
        this.deleteHandler.accept(key);
    }

    private @NonNull T handlePut(final @NonNull HttpExchange exchange) {
        requireHandler(putHandler, "put");
        final String key = getIdFromExchange(exchange);
        final T body = DataTransferUtils.deserializeJsonFromExchange(exchange, getResultType());
        Preconditions.checkArgument(Objects.nonNull(key), "key is required");
        Preconditions.checkArgument(Objects.nonNull(body), "Body is required");
        return this.putHandler.apply(key, body);
    }

    private @NonNull T handlePost(final @NonNull HttpExchange exchange) {
        requireHandler(postHandler, "post");
        final T body = DataTransferUtils.deserializeJsonFromExchange(exchange, getResultType());
        Preconditions.checkArgument(Objects.nonNull(body), "Body is required");
        return this.postHandler.apply(body);
    }

    private @NonNull Object handleGet(final @NonNull HttpExchange exchange) {
        requireHandler(getHandler, "get");
        final String key = getIdFromExchange(exchange);

        return this.getHandler.apply(key, DataTransferUtils.getUrlParams(exchange));
    }

    /**
     * Handle the options request. Can be overridden by subclasses. By default, provides cors support.
     */
    protected void handleOptionsRequest(@NonNull final HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Max-Age", "3600");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "content-type, authorization");
        exchange.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, -1);
    }

    @NonNull
    @Override
    public String path() {
        return path;
    }

    private @Nullable String getIdFromExchange(@NonNull final HttpExchange exchange) {
        final List<String> urlParts =
                DataTransferUtils.urlToList(exchange.getRequestURI().toString());
        final String id = urlParts.getLast();
        return !id.equalsIgnoreCase(getControllerName()) ? id : null;
    }

    private @NonNull Class<T> getResultType() {
        return this.resultType;
    }

    private @NonNull String getControllerName() {
        return DataTransferUtils.urlToList(this.path).getLast();
    }

    /**
     * Checks if the handler for the operation is set or throws {@link UnsupportedOperationException}
     *
     * @param handler   the handler to check
     * @param operation the name of the requested operation
     */
    private static void requireHandler(@Nullable final Object handler, @NonNull final String operation) {
        if (handler == null) {
            throw new UnsupportedOperationException(operation + " not supported");
        }
    }

    protected void setGetHandler(final GetHandler getHandler) {
        this.getHandler = getHandler;
    }

    protected void setPostHandler(final PostHandler<T> postHandler) {
        this.postHandler = postHandler;
    }

    protected void setPutHandler(final PutHandler<T> putHandler) {
        this.putHandler = putHandler;
    }

    protected void setDeleteHandler(final DeleteHandler deleteHandler) {
        this.deleteHandler = deleteHandler;
    }

    public interface PostHandler<T> extends Function<T, T> {}

    public interface PutHandler<T> extends BiFunction<String, T, T> {}

    public interface GetHandler extends BiFunction<String, Map<String, String>, Object> {}

    public interface DeleteHandler extends Consumer<String> {}
}
