// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.s3;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * A checked exception thrown when an S3 response is not successful.
 */
public final class S3ResponseException extends S3ClientException {
    /** The size limit for the toString response body length */
    private static final int MAX_RESPONSE_BODY_STRING_LENGTH = 2048;
    /** The size limit for the toString headers length */
    private static final int MAX_HEADER_STRING_LENGTH = 2048;
    /** The four-space indent used for formatting the exception string */
    private static final String FOUR_SPACE_INDENT = "    ";
    /** The response status code */
    private final int responseStatusCode;
    /** The response body, nullable */
    private final byte[] responseBody;
    /** The response headers, nullable */
    private final HttpHeaders responseHeaders;

    public S3ResponseException(
            final int responseStatusCode,
            @Nullable final byte[] responseBody,
            @Nullable final HttpHeaders responseHeaders) {
        super();
        this.responseStatusCode = responseStatusCode;
        this.responseBody = responseBody;
        this.responseHeaders = responseHeaders;
    }

    public S3ResponseException(
            final int responseStatusCode,
            @Nullable final byte[] responseBody,
            @Nullable final HttpHeaders responseHeaders,
            final String message) {
        super(message);
        this.responseStatusCode = responseStatusCode;
        this.responseBody = responseBody;
        this.responseHeaders = responseHeaders;
    }

    public S3ResponseException(
            final int responseStatusCode,
            @Nullable final byte[] responseBody,
            @Nullable final HttpHeaders responseHeaders,
            final Throwable cause) {
        super(cause);
        this.responseStatusCode = responseStatusCode;
        this.responseBody = responseBody;
        this.responseHeaders = responseHeaders;
    }

    public S3ResponseException(
            final int responseStatusCode,
            @Nullable final byte[] responseBody,
            @Nullable final HttpHeaders responseHeaders,
            final String message,
            final Throwable cause) {
        super(message, cause);
        this.responseStatusCode = responseStatusCode;
        this.responseBody = responseBody;
        this.responseHeaders = responseHeaders;
    }

    public int getResponseStatusCode() {
        return responseStatusCode;
    }

    public byte[] getResponseBody() {
        return responseBody;
    }

    public HttpHeaders getResponseHeaders() {
        return responseHeaders;
    }

    /**
     * @return a {@link String} representation of this exception including the
     * response code, headers if available and body if available. Header and
     * body strings are limited to a maximum length: header limit - {@value MAX_HEADER_STRING_LENGTH}
     * and body limit - {@value MAX_RESPONSE_BODY_STRING_LENGTH}.
     */
    @Override
    public String toString() {
        // start with standard exception string building
        final String className = getClass().getName();
        final String message = getLocalizedMessage();
        final StringBuilder sb = new StringBuilder(className);
        if (message != null) {
            // if there is a message, append it
            sb.append(": ").append(message);
        }
        sb.append(System.lineSeparator());
        appendResponseCode(sb);
        appendHeaders(sb);
        appendBody(sb);
        return sb.toString();
    }

    /**
     * Append the response code to the StringBuilder.
     */
    private void appendResponseCode(final StringBuilder sb) {
        // append the response status code
        sb.append(FOUR_SPACE_INDENT).append("Response status code: ").append(responseStatusCode);
    }

    /**
     * Append the response headers to the StringBuilder.
     * If there are no headers, this method does nothing.
     * Size limit for the headers string is {@value MAX_HEADER_STRING_LENGTH}.
     */
    private void appendHeaders(final StringBuilder sb) {
        if (responseHeaders != null) {
            final Map<String, List<String>> headersMap = responseHeaders.map();
            if (headersMap != null && !headersMap.isEmpty()) {
                // for each header, append the header name and value(s)
                sb.append(System.lineSeparator());
                sb.append(FOUR_SPACE_INDENT).append("Response headers:");
                // we limit the size of the printed headers
                int headerSizeCount = 0;
                for (final Entry<String, List<String>> entry : headersMap.entrySet()) {
                    // for each header, we get the key and values
                    final String headerKey = entry.getKey() + ": ";
                    sb.append(System.lineSeparator());
                    if (headerSizeCount + headerKey.length() > MAX_HEADER_STRING_LENGTH) {
                        // if string limit size is exceeded, break
                        sb.append(FOUR_SPACE_INDENT.repeat(2)).append("...");
                        break;
                    } else {
                        // append the header key
                        sb.append(FOUR_SPACE_INDENT.repeat(2)).append(headerKey);
                        headerSizeCount += headerKey.length();
                    }
                    // append the header values, usually we expect only one value per header
                    final List<String> values = entry.getValue();
                    boolean isFirstValue = true;
                    for (final String value : values) {
                        // if string limit size is exceeded, break
                        if (headerSizeCount + value.length() > MAX_HEADER_STRING_LENGTH) {
                            // if the value size exceeds the limit, break
                            sb.append(" ...");
                            break;
                        } else {
                            // append the value, separate with a comma for multi-value headers
                            if (!isFirstValue) {
                                sb.append(", ");
                                headerSizeCount += 2;
                            } else {
                                isFirstValue = false;
                            }
                            sb.append(value);
                            headerSizeCount += value.length();
                        }
                    }
                }
            }
        }
    }

    /**
     * Append the response body to the StringBuilder.
     * If there is no response body, this method does nothing.
     * Size limit for the response body string is {@value MAX_RESPONSE_BODY_STRING_LENGTH}.
     */
    private void appendBody(final StringBuilder sb) {
        if (responseBody != null && responseBody.length > 0) {
            // if there is a response body, append it
            sb.append(System.lineSeparator());
            // we limit the size of the printed response body
            sb.append("    Response body: ");
            if (responseBody.length > MAX_RESPONSE_BODY_STRING_LENGTH) {
                sb.append(new String(responseBody, 0, MAX_RESPONSE_BODY_STRING_LENGTH))
                        .append(" ...");
            } else {
                // if the response body is small enough, append it fully
                sb.append(new String(responseBody));
            }
        }
    }
}
