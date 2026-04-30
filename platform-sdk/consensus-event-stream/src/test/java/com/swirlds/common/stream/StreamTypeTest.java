// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream;

import static com.swirlds.common.test.fixtures.stream.TestStreamType.TEST_STREAM;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;

class StreamTypeTest {
    public static final File NULL_FILE = null;

    public static final String RECORD_FILE_NAME = "test.rcd";
    public static final File RECORD_FILE = new File(RECORD_FILE_NAME);
    public static final String RECORD_SIG_FILE_NAME = "test.rcd_sig";
    public static final File RECORD_SIG_FILE = new File(RECORD_SIG_FILE_NAME);

    public static final String EVENT_FILE_NAME = "test.evts";
    public static final File EVENT_FILE = new File(EVENT_FILE_NAME);
    public static final String EVENT_SIG_FILE_NAME = "test.evts_sig";
    public static final File EVENT_SIG_FILE = new File(EVENT_SIG_FILE_NAME);

    public static final String TEST_FILE_NAME = "name.test";
    public static final File TEST_FILE = new File(TEST_FILE_NAME);
    public static final String TEST_SIG_FILE_NAME = "name.test_sig";
    public static final File TEST_SIG_FILE = new File(TEST_SIG_FILE_NAME);

    public static final String NON_STREAM_FILE_NAME = "test.soc";
    public static final File NON_STREAM_FILE = new File(NON_STREAM_FILE_NAME);

    private static final String IS_STREAM_FILE_ERROR_MSG = "isStreamFile() returns unexpected result";
    private static final String IS_STREAM_SIG_FILE_ERROR_MSG = "isStreamSigFile() returns unexpected result";

    @Test
    void isStreamFileTest() {
        assertTrue(EventStreamType.getInstance().isStreamFile(EVENT_FILE_NAME), IS_STREAM_FILE_ERROR_MSG);
        assertTrue(EventStreamType.getInstance().isStreamFile(EVENT_FILE), IS_STREAM_FILE_ERROR_MSG);

        assertFalse(EventStreamType.getInstance().isStreamFile(EVENT_SIG_FILE_NAME), IS_STREAM_FILE_ERROR_MSG);
        assertFalse(EventStreamType.getInstance().isStreamFile(EVENT_SIG_FILE), IS_STREAM_FILE_ERROR_MSG);

        assertFalse(EventStreamType.getInstance().isStreamFile(RECORD_FILE_NAME), IS_STREAM_FILE_ERROR_MSG);
        assertFalse(EventStreamType.getInstance().isStreamFile(RECORD_FILE), IS_STREAM_FILE_ERROR_MSG);

        assertFalse(EventStreamType.getInstance().isStreamFile(RECORD_SIG_FILE_NAME), IS_STREAM_FILE_ERROR_MSG);
        assertFalse(EventStreamType.getInstance().isStreamFile(RECORD_SIG_FILE), IS_STREAM_FILE_ERROR_MSG);

        assertFalse(EventStreamType.getInstance().isStreamFile(NON_STREAM_FILE_NAME), IS_STREAM_FILE_ERROR_MSG);
        assertFalse(EventStreamType.getInstance().isStreamFile(NON_STREAM_FILE), IS_STREAM_FILE_ERROR_MSG);

        assertTrue(TEST_STREAM.isStreamFile(TEST_FILE_NAME), IS_STREAM_FILE_ERROR_MSG);
        assertTrue(TEST_STREAM.isStreamFile(TEST_FILE), IS_STREAM_FILE_ERROR_MSG);
    }

    @Test
    void isStreamSigFileTest() {
        assertTrue(EventStreamType.getInstance().isStreamSigFile(EVENT_SIG_FILE_NAME), IS_STREAM_SIG_FILE_ERROR_MSG);
        assertTrue(EventStreamType.getInstance().isStreamSigFile(EVENT_SIG_FILE), IS_STREAM_SIG_FILE_ERROR_MSG);

        assertFalse(EventStreamType.getInstance().isStreamSigFile(EVENT_FILE_NAME), IS_STREAM_SIG_FILE_ERROR_MSG);
        assertFalse(EventStreamType.getInstance().isStreamSigFile(EVENT_FILE), IS_STREAM_SIG_FILE_ERROR_MSG);

        assertFalse(EventStreamType.getInstance().isStreamSigFile(RECORD_FILE_NAME), IS_STREAM_SIG_FILE_ERROR_MSG);
        assertFalse(EventStreamType.getInstance().isStreamSigFile(RECORD_FILE), IS_STREAM_SIG_FILE_ERROR_MSG);

        assertFalse(EventStreamType.getInstance().isStreamSigFile(RECORD_SIG_FILE_NAME), IS_STREAM_SIG_FILE_ERROR_MSG);
        assertFalse(EventStreamType.getInstance().isStreamSigFile(RECORD_SIG_FILE), IS_STREAM_SIG_FILE_ERROR_MSG);

        assertFalse(EventStreamType.getInstance().isStreamSigFile(NON_STREAM_FILE_NAME), IS_STREAM_SIG_FILE_ERROR_MSG);
        assertFalse(EventStreamType.getInstance().isStreamSigFile(NON_STREAM_FILE), IS_STREAM_SIG_FILE_ERROR_MSG);

        assertTrue(TEST_STREAM.isStreamSigFile(TEST_SIG_FILE_NAME), IS_STREAM_FILE_ERROR_MSG);
        assertTrue(TEST_STREAM.isStreamSigFile(TEST_SIG_FILE), IS_STREAM_FILE_ERROR_MSG);
    }
}
