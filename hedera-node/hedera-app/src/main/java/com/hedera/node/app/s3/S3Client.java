// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.s3;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Simple standalone S3 client for downloading and listing objects from S3.
 */
@SuppressWarnings("JavadocLinkAsPlainText")
public final class S3Client implements AutoCloseable {
    /* Set the system property to allow restricted headers in HttpClient */
    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "Host,Content-Length");
    }
    /** SHA256 hash of an empty request body **/
    private static final String EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    /** The size limit of response body to be read for an exceptional response */
    private static final int ERROR_BODY_MAX_LENGTH = 32_768;

    /** The S3 endpoint URL **/
    private final String endpoint;
    /** The S3 bucket name **/
    private final String bucketName;
    /** The HTTP client used for making requests **/
    private final HttpClient httpClient;

    /**
     * Constructor for S3Client.
     *
     * @param endpoint The S3 endpoint URL (e.g. "https://s3.amazonaws.com/").
     * @param bucketName The name of the S3 bucket.
     * @throws S3ClientInitializationException if an error occurs during
     * client initialization or preconditions are not met.
     */
    public S3Client(final String endpoint, final String bucketName) throws S3ClientInitializationException {
        try {
            this.endpoint = requireNotBlank(endpoint).endsWith("/") ? endpoint : endpoint + "/";
            this.bucketName = requireNotBlank(bucketName);
            this.httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            // The document builder factory will be used for response body parsing
            final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            documentBuilderFactory.setNamespaceAware(true);
        } catch (final Exception e) {
            throw new S3ClientInitializationException(e);
        }
    }

    /**
     * Closes the HTTP client.
     */
    @Override
    public void close() {
        this.httpClient.close();
    }

    /**
     * Downloads a file from S3, assumes the file is small enough as uses single part download
     * (i.e. less than 5GB).
     *
     * @param key the key for the object in S3 (e.g., "myfolder/myfile.txt"); cannot be blank
     * @param path the filepath to write all the bytes to
     * @return the number of bytes downloaded, or -1 if file doesn't exist in S3
     * @throws S3ResponseException if a non-200 response is received from S3 during file download
     * @throws IOException if an error occurs while reading the response body in case of non 200 response
     */
    public long downloadFile(final String key, final Path path) throws S3ResponseException, IOException {
        requireNotBlank(key);
        // build the URL for the request
        final String url = endpoint + bucketName + "/" + urlEncode(key);
        // make the request
        final HttpResponse<InputStream> response = request(url, Collections.emptyMap(), BodyHandlers.ofInputStream());
        // check status code and return value
        final int responseStatusCode = response.statusCode();
        try (final InputStream in = response.body()) { // ensure body stream is always closed
            if (responseStatusCode == 404) {
                return -1;
            } else if (responseStatusCode != 200) {
                final byte[] responseBody = in.readNBytes(ERROR_BODY_MAX_LENGTH);
                final HttpHeaders responseHeaders = response.headers();
                final String message = "Failed to download text file: key=%s".formatted(key);
                throw new S3ResponseException(responseStatusCode, responseBody, responseHeaders, message);
            } else {
                return Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Performs an HTTP request to S3 to the specified URL with the given parameters.
     *
     * @param url The URL to send the request to
     * @param headers The request headers to send
     * @param bodyHandler The body handler for parsing response
     * @return HTTP response and result parsed using the provided body handler
     */
    private <T> HttpResponse<T> request(
            final String url, final Map<String, String> headers, final BodyHandler<T> bodyHandler) {
        try {
            // the region-specific endpoint to the target object expressed in path style
            final URI endpointUrl = new URI(url);
            final Map<String, String> localHeaders = new TreeMap<>(headers);

            localHeaders.put("x-amz-content-sha256", EMPTY_BODY_SHA256);

            // build the request
            HttpRequest.Builder requestBuilder =
                    HttpRequest.newBuilder(endpointUrl).GET();
            requestBuilder = requestBuilder.headers(localHeaders.entrySet().stream()
                    .flatMap(entry -> Stream.of(entry.getKey(), entry.getValue()))
                    .toArray(String[]::new));
            final HttpRequest request = requestBuilder.build();
            return httpClient.send(request, bodyHandler);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final InterruptedException | URISyntaxException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /**
     * Encodes the given URL using UTF-8 encoding.
     *
     * @param url the URL to encode
     * @return the encoded URL
     */
    private static String urlEncode(final String url) {
        final String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8).replace("+", "%20");
        return encoded.replace("%2F", "/");
    }

    /// Check if the value is not null and not blank.
    private static String requireNotBlank(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Blank or null value");
        }
        return value;
    }
}
