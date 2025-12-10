// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class SimpleFeesJsonLoader {
    /**
     * Loads a SimpleFeesJsonSchema from a resource in the classpath.
     *
     * @param resourcePath the path to the JSON resource (e.g. "/fees/simpleFees.json")
     * @return parsed SimpleFeesJsonSchema instance
     * @throws IOException if the resource is not found or cannot be read
     */
    public static SimpleFeesJsonSchema fromClassPath(String resourcePath) throws IOException {
        try (InputStream is = SimpleFeesJsonSchema.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }

            return new ObjectMapper().readValue(is, SimpleFeesJsonSchema.class);
        }
    }

    /**
     * Loads a SimpleFeesJsonSchema from a file path on disk.
     *
     * @param path path to the JSON file
     * @return parsed SimpleFeesJsonSchema instance
     * @throws IOException if the file cannot be read or parsed
     */
    public static SimpleFeesJsonSchema fromFile(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return new ObjectMapper().readValue(is, SimpleFeesJsonSchema.class);
        }
    }

    /**
     * Parses a SimpleFeesJsonSchema directly from a JSON string.
     *
     * @param jsonContent JSON string representing a fee schedule
     * @return parsed SimpleFeesJsonSchema instance
     * @throws IOException if the JSON string is invalid
     */
    public static SimpleFeesJsonSchema fromString(String jsonContent) throws IOException {
        return new ObjectMapper().readValue(jsonContent, SimpleFeesJsonSchema.class);
    }
}
