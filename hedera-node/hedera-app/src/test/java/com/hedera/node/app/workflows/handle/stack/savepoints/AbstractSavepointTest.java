// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack.savepoints;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.app.state.WrappedState;
import com.hedera.node.app.workflows.handle.stack.BuilderSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AbstractSavepointTest {
    @Mock
    private WrappedState state;

    @Mock
    private BuilderSink parentSink;

    private AbstractSavepoint subject;

    @BeforeEach
    void setUp() {
        subject = new AbstractSavepoint(state, parentSink, 1, 2) {
            @Override
            void commitBuilders() {
                // No-op
            }
        };
    }

    @Test
    void forgetsFeesCollectedAfterRollback() {
        subject.trackCollectedNodeFee(1L);
        subject.trackCollectedNodeFee(2L);
        subject.trackCollectedNodeFee(3L);

        assertEquals(6L, subject.getNodeFeesCollected());

        subject.rollback();

        assertEquals(0L, subject.getNodeFeesCollected());
    }

    @Test
    void flushesFeesToParentSavepointIfMeaningful() {
        final var childSubject = new AbstractSavepoint(state, subject, 1, 2) {
            @Override
            void commitBuilders() {
                // No-op
            }
        };

        childSubject.trackCollectedNodeFee(42L);
        childSubject.commit();

        assertEquals(42L, subject.getNodeFeesCollected());

        assertDoesNotThrow(() -> subject.commit());
    }
}
