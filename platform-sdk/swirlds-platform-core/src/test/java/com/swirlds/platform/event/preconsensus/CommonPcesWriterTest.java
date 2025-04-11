// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static org.hiero.consensus.model.event.AncientMode.GENERATION_THRESHOLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.test.fixtures.TestFileSystemManager;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.test.fixtures.platform.TestPlatformContexts;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.io.IOException;
import java.util.stream.Stream;
import org.hiero.base.crypto.DigestType;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;

class CommonPcesWriterTest {

    private PcesFileManager fileManager;
    private CommonPcesWriter commonPcesWriter;
    private PcesMutableFile pcesMutableFile;


    protected static Stream<Arguments> ancientModes() {
        return Stream.of(Arguments.of(GENERATION_THRESHOLD), Arguments.of(BIRTH_ROUND_THRESHOLD));
    }


    @BeforeEach
    void setUp() throws Exception {
        final PlatformContext platformContext = TestPlatformContextBuilder.create().build();

        fileManager = new PcesFileManager(platformContext, new PcesFileTracker(BIRTH_ROUND_THRESHOLD), NodeId.of(0), 100);

        // Initialize CommonPcesWriter with mocks
        commonPcesWriter = new CommonPcesWriter(platformContext, fileManager);
    }

    @Test
    void testBeginStreamingNewEvents() {
        commonPcesWriter.beginStreamingNewEvents();
        assertTrue(commonPcesWriter.isStreamingNewEvents(), "New event streaming should start.");
    }

    @Test
    void testBeginStreamingNewEventsAlreadyStreaming() {
        commonPcesWriter.beginStreamingNewEvents();
        // Expect a log error but no exception thrown
        commonPcesWriter.beginStreamingNewEvents();
    }

    @Test
    void testRegisterDiscontinuity() throws IOException {
        commonPcesWriter.beginStreamingNewEvents();
        commonPcesWriter.prepareOutputStream(mock(PlatformEvent.class));
        commonPcesWriter.registerDiscontinuity(10L);

        // Verify file closing and file manager interactions
        verify(fileManager, times(1)).registerDiscontinuity(10L);
        verify(pcesMutableFile, times(1)).close();
        verify(fileManager, times(1)).registerDiscontinuity(10L);
    }

    @Test
    void testUpdateNonAncientEventBoundary() {
        EventWindow mockWindow = new EventWindow(999, 100, 10, BIRTH_ROUND_THRESHOLD);

        commonPcesWriter.updateNonAncientEventBoundary(mockWindow);

        assertEquals(100L, commonPcesWriter.getNonAncientBoundary(), "Non-ancient boundary should be updated.");
    }

    @Test
    void testSetMinimumAncientIdentifierToStore() throws IOException {
        commonPcesWriter.beginStreamingNewEvents();
        commonPcesWriter.setMinimumAncientIdentifierToStore(50L);

        verify(fileManager, times(1)).pruneOldFiles(50L);
    }

    @Test
    void testPrepareOutputStreamCreatesNewFile() throws IOException {
        PlatformEvent mockEvent = mock(PlatformEvent.class);
        when(mockEvent.getDescriptor())
                .thenReturn(new EventDescriptorWrapper(EventDescriptor.newBuilder()
                        .birthRound(150)
                        .generation(150)
                        .hash(Bytes.wrap(new byte[DigestType.SHA_384.digestLength()]))
                        .build()));

        boolean fileClosed = commonPcesWriter.prepareOutputStream(mockEvent);
        assertFalse(fileClosed, "A new file should have been created but not closed.");
    }

    @Test
    void testCloseCurrentMutableFile() throws IOException {
        commonPcesWriter.beginStreamingNewEvents();
        commonPcesWriter.prepareOutputStream(mock(PlatformEvent.class));
        commonPcesWriter.closeCurrentMutableFile();
        verify(pcesMutableFile, times(1)).close();
    }
}
