// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.test.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.file.impl.handlers.FileAppendHandler;
import com.hedera.node.app.service.file.impl.handlers.FileCreateHandler;
import com.hedera.node.app.service.file.impl.handlers.FileDeleteHandler;
import com.hedera.node.app.service.file.impl.handlers.FileGetContentsHandler;
import com.hedera.node.app.service.file.impl.handlers.FileGetInfoHandler;
import com.hedera.node.app.service.file.impl.handlers.FileHandlers;
import com.hedera.node.app.service.file.impl.handlers.FileSystemDeleteHandler;
import com.hedera.node.app.service.file.impl.handlers.FileSystemUndeleteHandler;
import com.hedera.node.app.service.file.impl.handlers.FileUpdateHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FileHandlersTest {
    private FileAppendHandler fileAppendHandler;
    private FileCreateHandler fileCreateHandler;
    private FileDeleteHandler fileDeleteHandler;
    private FileGetContentsHandler fileGetContentsHandler;
    private FileGetInfoHandler fileGetInfoHandler;
    private FileSystemDeleteHandler fileSystemDeleteHandler;
    private FileSystemUndeleteHandler fileSystemUndeleteHandler;
    private FileUpdateHandler fileUpdateHandler;

    private FileHandlers fileHandlers;

    @BeforeEach
    public void setUp() {
        fileAppendHandler = mock(FileAppendHandler.class);
        fileCreateHandler = mock(FileCreateHandler.class);
        fileDeleteHandler = mock(FileDeleteHandler.class);
        fileGetContentsHandler = mock(FileGetContentsHandler.class);
        fileGetInfoHandler = mock(FileGetInfoHandler.class);
        fileSystemDeleteHandler = mock(FileSystemDeleteHandler.class);
        fileSystemUndeleteHandler = mock(FileSystemUndeleteHandler.class);
        fileUpdateHandler = mock(FileUpdateHandler.class);

        fileHandlers = new FileHandlers(
                fileAppendHandler,
                fileCreateHandler,
                fileDeleteHandler,
                fileGetContentsHandler,
                fileGetInfoHandler,
                fileSystemDeleteHandler,
                fileSystemUndeleteHandler,
                fileUpdateHandler);
    }

    @Test
    void fileAppendHandlerReturnsCorrectInstance() {
        assertEquals(
                fileAppendHandler,
                fileHandlers.fileAppendHandler(),
                "fileAppendHandler does not return correct instance");
    }

    @Test
    void fileCreateHandlerReturnsCorrectInstance() {
        assertEquals(
                fileCreateHandler,
                fileHandlers.fileCreateHandler(),
                "fileCreateHandler does not return correct instance");
    }

    @Test
    void fileDeleteHandlerReturnsCorrectInstance() {
        assertEquals(
                fileDeleteHandler,
                fileHandlers.fileDeleteHandler(),
                "fileDeleteHandler does not return correct instance");
    }

    @Test
    void fileGetContentsHandlerReturnsCorrectInstance() {
        assertEquals(
                fileGetContentsHandler,
                fileHandlers.fileGetContentsHandler(),
                "fileGetContentsHandler does not return correct instance");
    }

    @Test
    void fileGetInfoHandlerReturnsCorrectInstance() {
        assertEquals(
                fileGetInfoHandler,
                fileHandlers.fileGetInfoHandler(),
                "fileGetInfoHandler does not return correct instance");
    }

    @Test
    void fileSystemDeleteHandlerReturnsCorrectInstance() {
        assertEquals(
                fileSystemDeleteHandler,
                fileHandlers.fileSystemDeleteHandler(),
                "fileSystemDeleteHandler does not return correct instance");
    }

    @Test
    void fileSystemUndeleteHandlerReturnsCorrectInstance() {
        assertEquals(
                fileSystemUndeleteHandler,
                fileHandlers.fileSystemUndeleteHandler(),
                "fileSystemUndeleteHandler does not return correct instance");
    }

    @Test
    void fileUpdateHandlerReturnsCorrectInstance() {
        assertEquals(
                fileUpdateHandler,
                fileHandlers.fileUpdateHandler(),
                "fileUpdateHandler does not return correct instance");
    }
}
