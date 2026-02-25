// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.helpers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.swirlds.logging.legacy.payload.LogPayload;
import com.swirlds.logging.legacy.payload.PayloadParsingException;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Utility class for parsing and extracting information from log payloads. This class provides methods to parse a log
 * payload into a specific type and to extract human-readable messages and JSON data from the payload.
 */
public class LogPayloadUtils {

    private LogPayloadUtils() {}

    /**
     * This object is thread safe after initialization
     */
    private static final ObjectMapper mapper;

    /**
     * Static initializer because configuration of the ObjectMapper is not thread-safe.
     */
    static {
        mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // make output a readable ISO-8601 format :2021-09-30T16:02:01.656445Z
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.registerModule(new JavaTimeModule());
    }

    /**
     * Parse a payload into a requested data type.
     *
     * @param type the type that the payload is expected to have. Caller is expected to verify this before attempting to
     * parse.
     * @param data a payload in serialized form
     * @param <T> the implementation of the {@link LogPayload}
     * @return the parsed payload
     */
    @NonNull
    public static <T extends LogPayload> T parsePayload(@NonNull final Class<T> type, @NonNull final String data) {
        final int startIndex = data.indexOf('{');
        final int endIndex = data.lastIndexOf('}') + 1;
        final String jsonString = data.substring(startIndex, endIndex);

        final T payload;
        try {
            payload = mapper.readValue(jsonString, type);
        } catch (final JsonProcessingException e) {
            throw new PayloadParsingException(
                    String.format("Unable to map json data onto object%nObject:%n%s%nData:%n%s", type.getName(), data),
                    e);
        }

        return payload;
    }
}
