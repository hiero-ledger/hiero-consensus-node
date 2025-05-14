// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.io;

import static java.util.Arrays.asList;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;

/**
 * Contains utilities for loading files and other resources from disk.
 */
public abstract class ResourceLoader {

    private static final ClassLoader classLoader = ResourceLoader.class.getClassLoader();

    /**
     * Returns a File instance pointing to the requested resource
     *
     * @param path
     * 		the path of the resource
     * @return a File representing the resource
     */
    public static Path getFile(final String path) throws URISyntaxException {
        final URL resource = classLoader.getResource(path);
        checkIfFound(resource, path);
        final URI uri = resource.toURI();
        checkIfFound(uri, path);
        File file = new File(uri);
        checkIfFound(file, path);
        return file.toPath();
    }

    /**
     * Checks if a resource has been found
     *
     * @param toCheck
     * 		the object to check
     * @param path
     * 		the path of the resource
     */
    private static void checkIfFound(final Object toCheck, final String path) {
        if (toCheck == null) {
            resourceNotFound(path);
        }
        if (toCheck instanceof String s && s.isEmpty()) {
            resourceNotFound(path);
        }
        if (toCheck instanceof File f && !f.exists()) {
            resourceNotFound(path);
        }
    }

    /**
     * Throws a ResourceNotFoundException
     *
     * @param path
     * 		the resource that was supposed to be loaded
     */
    private static void resourceNotFound(final String path) {
        URL url = classLoader.getResource(".");
        String exPath;
        if (url != null) {
            exPath = url.getFile() + path;
        } else {
            exPath = path;
        }
        throw new ResourceNotFoundException(exPath);
    }

    /**
     * Load a url given a path. The path is considered to be relative to the resources directory.
     *
     * @param path
     * 		the path to the file relative to the resources directory
     * @return a URL for the requested file
     */
    public static URL loadURL(final String path) {
        URL url = classLoader.getResource(path);
        checkIfFound(url, path);
        return url;
    }

    /**
     * Look for a file in one or more potential locations.
     *
     * @param fileName
     * 		he name of the file to look for
     * @param possibleLocations
     * 		possible locations where the file may be found
     * @return the first file found with the given name
     * @throws FileNotFoundException
     * 		if the file is in none of the possible locations
     */
    public static File searchForFile(String fileName, List<String> possibleLocations) throws FileNotFoundException {
        for (String possibleLocation : possibleLocations) {
            Path path = Paths.get(possibleLocation, fileName);
            File file = new File(path.toUri());
            if (file.exists()) {
                return file;
            }
        }

        // File not found, throw exception
        StringBuilder sb = new StringBuilder("Unable to find file ")
                .append(fileName)
                .append(" in any of the following locations:\n");
        for (String possibleLocation : possibleLocations) {
            Path path = Paths.get(possibleLocation, fileName);
            File file = new File(path.toUri());
            sb.append(file).append("\n");
        }

        throw new FileNotFoundException(sb.toString());
    }

    /**
     * Find and return the log4j configuration.
     */
    public static File searchForLog4jConfig() throws FileNotFoundException {
        return searchForFile(
                "log4j2-test.xml",
                asList(
                        // Normal place to put log4j config
                        "src/test/resources",
                        // If no config is provided, use config from platform
                        "../../../swirlds-platform-core/src/test/resources",
                        // If no config is provided, use config from platform
                        "../swirlds-platform-core/src/test/resources",
                        // AWS location
                        "swirlds-platform-core/src/test/java/main/resources"));
    }

    /**
     * Load the log4j context.
     */
    public static void loadLog4jContext() throws FileNotFoundException {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        final File log4jConfig = searchForLog4jConfig();
        context.setConfigLocation(log4jConfig.toURI());
    }
}
