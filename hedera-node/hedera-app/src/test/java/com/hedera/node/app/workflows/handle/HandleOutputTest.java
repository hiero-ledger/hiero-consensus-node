// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.app.state.recordcache.BlockRecordSource;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandleOutputTest {
    private static final Instant LAST_TIME = Instant.ofEpochSecond(1_234_567L, 890);

    @Mock
    private BlockRecordSource blockRecordSource;

    @Mock
    private RecordSource recordSource;

    @Test
    void throwsIfMissingRecordSourceWhenRequired() {
        final var subject = new HandleOutput(blockRecordSource, null, LAST_TIME);
        assertThrows(NullPointerException.class, subject::recordSourceOrThrow);
    }

    @Test
    void throwsIfMissingBlockRecordSourceWhenRequired() {
        final var subject = new HandleOutput(null, recordSource, LAST_TIME);
        assertThrows(NullPointerException.class, subject::blockRecordSourceOrThrow);
    }

    @Test
    void returnsRecordSourceWhenPresent() {
        final var subject = new HandleOutput(null, recordSource, LAST_TIME);
        assertEquals(recordSource, subject.recordSourceOrThrow());
    }

    @Test
    void returnsBlockRecordSourceWhenPresent() {
        final var subject = new HandleOutput(blockRecordSource, null, LAST_TIME);
        assertEquals(blockRecordSource, subject.blockRecordSourceOrThrow());
    }

    @Test
    void prefersBlockRecordSourceWhenItHasOutputs() {
        given(blockRecordSource.hasOutputs()).willReturn(true);
        final var withBothSources = new HandleOutput(blockRecordSource, recordSource, LAST_TIME);
        assertEquals(blockRecordSource, withBothSources.preferredRecordSource());
    }

    @Test
    void fallsBackToRecordSourceWhenBlockSourceHasNoOutputs() {
        final var withSuppressedBlockOutput = new HandleOutput(blockRecordSource, recordSource, LAST_TIME);
        assertEquals(recordSource, withSuppressedBlockOutput.preferredRecordSource());
    }

    @Test
    void prefersTheOnlyAvailableSource() {
        assertEquals(blockRecordSource, new HandleOutput(blockRecordSource, null, LAST_TIME).preferredRecordSource());
        assertEquals(recordSource, new HandleOutput(null, recordSource, LAST_TIME).preferredRecordSource());
    }
}
