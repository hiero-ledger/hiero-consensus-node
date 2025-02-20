// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.handlers;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class FileHandlers {

    private final FileAppendHandler fileAppendHandler;

    private final FileCreateHandler fileCreateHandler;

    private final FileDeleteHandler fileDeleteHandler;

    private final FileGetContentsHandler fileGetContentsHandler;

    private final FileGetInfoHandler fileGetInfoHandler;

    private final FileSystemDeleteHandler fileSystemDeleteHandler;

    private final FileSystemUndeleteHandler fileSystemUndeleteHandler;

    private final FileUpdateHandler fileUpdateHandler;

    /**
     * Constructor for the FileHandlers.
     *
     * @param fileAppendHandler the file append handler
     * @param fileCreateHandler the file create handler
     * @param fileDeleteHandler the file delete handler
     * @param fileGetContentsHandler the file get contents handler
     * @param fileGetInfoHandler the file get info handler
     * @param fileSystemDeleteHandler the file system delete handler
     * @param fileSystemUndeleteHandler the file system undelete handler
     * @param fileUpdateHandler the file update handler
     */
    @Inject
    public FileHandlers(
            @NonNull final FileAppendHandler fileAppendHandler,
            @NonNull final FileCreateHandler fileCreateHandler,
            @NonNull final FileDeleteHandler fileDeleteHandler,
            @NonNull final FileGetContentsHandler fileGetContentsHandler,
            @NonNull final FileGetInfoHandler fileGetInfoHandler,
            @NonNull final FileSystemDeleteHandler fileSystemDeleteHandler,
            @NonNull final FileSystemUndeleteHandler fileSystemUndeleteHandler,
            @NonNull final FileUpdateHandler fileUpdateHandler) {
        this.fileAppendHandler = requireNonNull(fileAppendHandler, "fileAppendHandler must not be null");
        this.fileCreateHandler = requireNonNull(fileCreateHandler, "fileCreateHandler must not be null");
        this.fileDeleteHandler = requireNonNull(fileDeleteHandler, "fileDeleteHandler must not be null");
        this.fileGetContentsHandler = requireNonNull(fileGetContentsHandler, "fileGetContentsHandler must not be null");
        this.fileGetInfoHandler = requireNonNull(fileGetInfoHandler, "fileGetInfoHandler must not be null");
        this.fileSystemDeleteHandler =
                requireNonNull(fileSystemDeleteHandler, "fileSystemDeleteHandler must not be null");
        this.fileSystemUndeleteHandler =
                requireNonNull(fileSystemUndeleteHandler, "fileSystemUndeleteHandler must not be null");
        this.fileUpdateHandler = requireNonNull(fileUpdateHandler, "fileUpdateHandler must not be null");
    }

    /**
     * Gets the fileAppendHandler.
     *
     * @return the fileAppendHandler
     */
    public FileAppendHandler fileAppendHandler() {
        return fileAppendHandler;
    }

    /**
     * Gets the fileCreateHandler.
     *
     * @return the fileCreateHandler
     */
    public FileCreateHandler fileCreateHandler() {
        return fileCreateHandler;
    }

    /**
     * Gets the fileDeleteHandler.
     *
     * @return the fileDeleteHandler
     */
    public FileDeleteHandler fileDeleteHandler() {
        return fileDeleteHandler;
    }

    /**
     * Gets the fileGetContentsHandler.
     *
     * @return the fileGetContentsHandler
     */
    public FileGetContentsHandler fileGetContentsHandler() {
        return fileGetContentsHandler;
    }

    /**
     * Gets the fileGetInfoHandler.
     *
     * @return the fileGetInfoHandler
     */
    public FileGetInfoHandler fileGetInfoHandler() {
        return fileGetInfoHandler;
    }

    /**
     * Gets the fileSystemDeleteHandler.
     *
     * @return the fileSystemDeleteHandler
     */
    public FileSystemDeleteHandler fileSystemDeleteHandler() {
        return fileSystemDeleteHandler;
    }

    /**
     * Gets the fileSystemUndeleteHandler.
     *
     * @return the fileSystemUndeleteHandler
     */
    public FileSystemUndeleteHandler fileSystemUndeleteHandler() {
        return fileSystemUndeleteHandler;
    }

    /**
     * Gets the fileUpdateHandler.
     *
     * @return the fileUpdateHandler
     */
    public FileUpdateHandler fileUpdateHandler() {
        return fileUpdateHandler;
    }
}
