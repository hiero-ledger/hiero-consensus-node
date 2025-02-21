// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Provides boiler plate implementation for LogPayload methods.
 */
public abstract class AbstractLogPayload implements LogPayload {

    private String message;

    /**
     * Reuse this object, but do not share between threads.
     */
    private static final ObjectMapper mapper;

    static {
        mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // make output a readable ISO-8601 format :2021-09-30T16:02:01.656445Z
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.registerModule(new JavaTimeModule());
    }

    public AbstractLogPayload() {}

    public AbstractLogPayload(final String message) {
        setMessage(message);
    }

    /**
     * {@inheritDoc}
     */
    @JsonIgnore
    @Override
    public final String getMessage() {
        return message;
    }

    /**
     * {@inheritDoc}
     */
    @JsonIgnore
    @Override
    public final void setMessage(String message) {
        if (message.indexOf('{') != -1) {
            throw new IllegalArgumentException(
                    """
                    The character '{' is not permitted in the message field.
                    Illegal message: "%s"
                    """
                            .formatted(message));
        }
        this.message = message;
    }

    /**
     * Write the json part of the payload.
     */
    private String serializeData() {
        try {
            return mapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @JsonIgnore
    @Override
    public final String toString() {
        return message + " " + serializeData() + " " + getMessageIdentifier(this.getClass());
    }

    /**
     * @param c
     * 		the class of the payload
     * @return the unique string that is used to identify this message
     */
    @JsonIgnore
    public static String getMessageIdentifier(Class<? extends AbstractLogPayload> c) {
        return "[" + c.getName() + "]";
    }

    /**
     * Extract the payload type from a string. Safe to call on strings that may not have proper formatting.
     *
     * @param data
     * 		a (potentially) formatted string
     * @return the type if it is present in the string, otherwise return "".
     */
    public static String extractPayloadType(final String data) {
        try {
            // The payload type is written at the end of the string between "[]" brackets
            final int startIndex = data.lastIndexOf('[') + 1;
            final int endIndex = data.lastIndexOf(']');
            return data.substring(startIndex, endIndex);
        } catch (IndexOutOfBoundsException ignored) {
            return "";
        }
    }

    /**
     * Extract json data from a string.
     *
     * @param data
     * 		a (potentially) formatted string
     * @return json data if it is present
     * @throws PayloadParsingException
     * 		thrown if well-formatted json data is not found
     */
    private static JsonNode extractJsonData(final String data) {
        try {
            // The json data will start with the first '{' and end with the last '}'
            final int startIndex = data.indexOf('{');
            final int endIndex = data.lastIndexOf('}') + 1;
            final String jsonString = data.substring(startIndex, endIndex);
            return mapper.readTree(jsonString);
        } catch (IndexOutOfBoundsException | JsonProcessingException e) {
            throw new PayloadParsingException(e);
        }
    }

    /**
     * Attempt to extract the human readable message from the data.
     *
     * @param data
     * 		a (potentially) formatted string
     * @return the human readable string, if present.
     */
    private static String extractMessage(final String data) {
        try {
            // The message is all of the text up until the first instance of '{', minus the ' ' that proceeds the '{'
            final int endIndex = data.indexOf('{') - 1;
            return data.substring(0, endIndex);
        } catch (IndexOutOfBoundsException ignored) {
            return "";
        }
    }

    /**
     * Parse a payload into a requested data type.
     *
     * @param type
     * 		the type that the payload is expected to have. Caller is expected to verify this before
     * 		attempting to parse.
     * @param data
     * 		a payload in serialized form
     * @param <T>
     * 		the implementation of the {@link LogPayload}
     * @return the parsed payload
     */
    public static <T extends LogPayload> T parsePayload(final Class<T> type, final String data) {
        T payload;
        try {
            payload = mapper.treeToValue(extractJsonData(data), type);
        } catch (JsonProcessingException e) {
            throw new PayloadParsingException("Unable to map json data onto object", e);
        }

        payload.setMessage(extractMessage(data));

        return payload;
    }
}
