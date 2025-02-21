// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class ResourceLoader<T> {

    /**
     * The class which is requesting the resources.
     */
    private final Class<T> requester;

    /**
     * The list of class loaders to use to resolve the resources in order.
     */
    private final List<ClassLoader> resolvers;

    /**
     * Constructs a new {@link ResourceLoader} for the specified class which is requesting the resources.
     *
     * @param requester the class which is requesting the resources.
     * @throws NullPointerException if the requester is {@code null}.
     */
    public ResourceLoader(final Class<T> requester) {
        Objects.requireNonNull(requester, "requester cannot be null");
        this.requester = requester;

        this.resolvers = new ArrayList<>();
        this.resolvers.add(this.requester.getClassLoader());
        this.resolvers.add(ClassLoader.getSystemClassLoader());
        this.resolvers.add(ClassLoader.getPlatformClassLoader());
    }

    /**
     * Locates the resource on the classpath at the given path and copies the file to a temporary directory.
     * Additionally, the file permissions are set to be world readable, writable, and executable.
     *
     * @param path the relative or absolute path to the resource to load.
     * @return the path to the resource in the temporary directory.
     * @throws IOException if the resource cannot be loaded or an I/O error occurs.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public Path load(final String path) throws IOException {
        Objects.requireNonNull(path, "path cannot be null");

        final InputStream resourceStream = acquireResourceStream(path);
        final String fileName = Path.of(path).getFileName().toString();
        final Path tempDirectory = createTempDirectory();
        final Path tempFile = tempDirectory.resolve(fileName);

        Files.copy(resourceStream, tempFile);
        if (isPosixCompliant()) {
            Files.setPosixFilePermissions(tempFile, PosixFilePermissions.fromString("rwxrwxrwx"));
        } else {
            final File f = tempFile.toFile();
            f.setExecutable(true, false);
            f.setReadable(true, false);
            f.setWritable(true, false);
        }

        return tempFile;
    }

    /**
     * Locates the directory on the classpath at the given path and copies the files recursively to a temporary directory.
     * Additionally, the file and directory permissions are set to be world readable, writable, and executable.
     *
     * @param path the relative or absolute path to the directory to load.
     * @return the path to the root of the temporary directory.
     * @throws IOException if the resource cannot be loaded or an I/O error occurs.
     */
    @SuppressWarnings({"ResultOfMethodCallIgnored", "java:S899"})
    public Path loadDirectory(final String path) throws IOException {
        Objects.requireNonNull(path, "path cannot be null");

        final URI resourceUri = acquireResourceUri(path);
        final Path tempDirectory = createTempDirectory();
        try (final FileSystem fs = acquireFileSystem(resourceUri)) {
            final Path root = fs.getPath(resourceUri.getPath());
            try (final Stream<Path> stream = Files.walk(root)) {
                stream.forEach(source -> {
                    try {
                        final Path destination =
                                tempDirectory.resolve(root.relativize(source).toString());
                        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                        if (isPosixCompliant()) {
                            Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rwxrwxrwx"));
                        } else {
                            final File f = destination.toFile();
                            f.setExecutable(true, false);
                            f.setReadable(true, false);
                            f.setWritable(true, false);
                        }
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (final IOException | UncheckedIOException e) {
                throw new IOException("Unable to load directory: " + path, e);
            }
        } catch (final UnsupportedOperationException ignored) {
            // Suppressed
        }

        return tempDirectory;
    }

    /**
     * Acquires the resource stream for the specified path.
     *
     * @param path the path to the resource to acquire.
     * @return an {@link InputStream} for the specified resource.
     * @throws IOException          if the resource cannot be acquired or an I/O error occurs.
     * @throws NullPointerException if the path is {@code null}.
     */
    private InputStream acquireResourceStream(final String path) throws IOException {
        Objects.requireNonNull(path, "path cannot be null");

        for (final ClassLoader resolver : resolvers) {
            final InputStream stream = resolver.getResourceAsStream(path);
            if (stream != null) {
                return stream;
            }
        }

        throw new IOException("Unable to acquire resource stream for path: " + path);
    }

    /**
     * Acquires the resource URI for the specified path.
     *
     * @param path the path to the resource to acquire.
     * @return a {@link URI} for the specified resource.
     * @throws IOException          if the resource cannot be acquired or an I/O error occurs.
     * @throws NullPointerException if the path is {@code null}.
     */
    private URI acquireResourceUri(final String path) throws IOException {
        Objects.requireNonNull(path, "path cannot be null");

        for (final ClassLoader resolver : resolvers) {
            final URL url = resolver.getResource(path);
            if (url != null) {
                try {
                    return url.toURI();
                } catch (URISyntaxException e) {
                    throw new IOException("Unable to acquire resource URI for path: " + path, e);
                }
            }
        }

        throw new IOException("Unable to acquire resource URI for path: " + path);
    }

    private FileSystem acquireFileSystem(final URI uri) throws IOException {
        try {
            if (uri.getScheme().equals("file")) {
                return FileSystems.getFileSystem(uri.resolve("/"));
            }

            return FileSystems.getFileSystem(uri);
        } catch (final FileSystemNotFoundException e) {
            return FileSystems.newFileSystem(uri, Collections.emptyMap());
        }
    }

    /**
     * Creates a temporary directory for the requester.
     *
     * @return the path to the temporary directory.
     * @throws IOException if the temporary directory cannot be created or an I/O error occurs.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private Path createTempDirectory() throws IOException {
        final Path tempDirectory = Files.createTempDirectory(requester.getSimpleName());
        tempDirectory.toFile().mkdir();
        tempDirectory.toFile().deleteOnExit();
        return tempDirectory;
    }

    /**
     * Is the system we're running on Posix compliant?
     *
     * @return True if posix compliant.
     */
    protected boolean isPosixCompliant() {
        try {
            return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        } catch (FileSystemNotFoundException | ProviderNotFoundException | SecurityException e) {
            return false;
        }
    }
}
