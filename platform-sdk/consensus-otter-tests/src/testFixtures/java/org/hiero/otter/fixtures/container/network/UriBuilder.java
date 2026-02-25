// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * A builder for constructing URIs with a fluent interface.
 */
public class UriBuilder {

    private final StringBuilder path = new StringBuilder();
    private final String scheme;
    private final String host;
    private final int port;

    /**
     * Constructor
     *
     * @param baseUri the base URI to start from
     */
    public UriBuilder(@NonNull final URI baseUri) {
        this.scheme = baseUri.getScheme();
        this.host = baseUri.getHost();
        this.port = baseUri.getPort();
        this.path.append(baseUri.getPath());
    }

    /**
     * Appends a path segment to the URI.
     *
     * @param segment the path segment to append
     * @return the updated UriBuilder instance
     * @throws NullPointerException if {@code segment} is {@code null}
     * @throws IllegalArgumentException if {@code segment} is empty
     */
    public UriBuilder path(@NonNull final String segment) {
        if (segment.isEmpty()) {
            throw new IllegalArgumentException("segment must not be empty");
        }

        // Ensure path ends with / before adding segment
        if (!path.isEmpty() && path.charAt(path.length() - 1) != '/') {
            path.append('/');
        }

        // Remove leading / from segment to avoid double slashes
        final String cleanSegment = segment.startsWith("/") ? segment.substring(1) : segment;
        path.append(cleanSegment);

        return this;
    }

    /**
     * Builds the URI.
     *
     * @return the constructed URI
     */
    public URI build() {
        try {
            return new URI(scheme, null, host, port, path.toString(), null, null);
        } catch (final URISyntaxException e) {
            throw new RuntimeException("Creating URI failed", e);
        }
    }
}
