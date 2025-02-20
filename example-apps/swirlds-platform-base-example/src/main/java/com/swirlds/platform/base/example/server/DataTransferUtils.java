// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Utility class for transforming information
 */
public class DataTransferUtils {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    private DataTransferUtils() {}

    /**
     * Serializes  {@code object} into a Json String
     *
     * @param object instance to be serialized
     * @param <T>    class {@code object} belongs to
     * @return the serialized json string
     */
    public static <T> @NonNull String serializeToJson(final @Nullable T object) {
        if (Objects.isNull(object)) {
            return "";
        }
        try {
            return MAPPER.writeValueAsString(object);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Could not serialize object of type " + object.getClass().getName(), e);
        }
    }

    /**
     * Deserializes a json string {@code json} into an object instance of type {@code type}
     *
     * @param json The json string to deserialize
     * @param type The type to deserialize to
     * @return an instance of {@code T}
     */
    public static <T> @Nullable T deserializeJson(final @Nullable String json, final @NonNull Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Could not deserialize an object of type " + type + " out of input string", e);
        }
    }

    /**
     * Deserializes a json string {@code json} into an object instance of type {@code type} from a
     * {@code HttpServerExchange}
     */
    public static <T> @Nullable T deserializeJsonFromExchange(
            final @NonNull HttpExchange exchange, final @NonNull Class<T> clazz) {
        try {
            final StringBuilder requestBody = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    requestBody.append(line);
                }
            }
            return deserializeJson(requestBody.toString(), clazz);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not deserialize into requested type:" + clazz, e);
        }
    }

    /**
     * Separate the path into a list of components.
     *
     * @param path the url
     * @return the path components of the URL
     */
    public static @NonNull List<String> urlToList(final @NonNull String path) {
        return Arrays.stream(path.split("/"))
                .filter(segment -> !segment.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * It does not convert lists into List objects.
     *
     * @param exchange Http exchange
     * @return all url parameters in a map.
     */
    public static @NonNull Map<String, String> getUrlParams(final @NonNull HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8) : pair;
                String value = idx > 0 && pair.length() > idx + 1
                        ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
                        : null;
                params.put(key, value);
            }
        }
        return params;
    }

    /**
     * @param dateString parses a date from string
     */
    public static @NonNull Date parseDate(@NonNull final String dateString) {

        Instant result;
        if (dateString.contains(":")) {
            result = LocalDateTime.parse(dateString, DateTimeFormatter.ISO_DATE_TIME)
                    .atZone(ZoneOffset.systemDefault())
                    .toInstant();
        } else {
            result = LocalDate.parse(dateString, DateTimeFormatter.ISO_DATE)
                    .atStartOfDay(ZoneOffset.systemDefault())
                    .toInstant();
        }
        return Date.from(result);
    }

    /**
     * creates a date from epoch value
     */
    public static Date fromEpoc(long epoch) {
        return Date.from(Instant.ofEpochMilli(epoch));
    }
}
