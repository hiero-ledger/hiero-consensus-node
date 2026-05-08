// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.BREAK_QUIESCENCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.DONT_QUIESCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.QUIESCE;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.swirlds.platform.system.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuiescenceCommandsTest {

    @Mock
    private Platform platform;

    private QuiescenceCommands subject;

    @BeforeEach
    void setUp() {
        subject = new QuiescenceCommands(platform);
    }

    @Test
    void initialLastSentIsDontQuiesce() {
        assertThat(subject.lastSent()).isEqualTo(DONT_QUIESCE);
    }

    @Test
    void sendIfChangedDispatchesAndUpdatesOnTransition() {
        assertThat(subject.sendIfChanged(QUIESCE)).isTrue();

        verify(platform).quiescenceCommand(QUIESCE);
        assertThat(subject.lastSent()).isEqualTo(QUIESCE);
    }

    @Test
    void sendIfChangedSkipsWhenAlreadyAtTarget() {
        subject.sendIfChanged(QUIESCE);

        assertThat(subject.sendIfChanged(QUIESCE)).isFalse();

        verify(platform, times(1)).quiescenceCommand(QUIESCE);
    }

    @Test
    void sendIfChangedDispatchesAcrossDistinctTransitions() {
        subject.sendIfChanged(QUIESCE);
        subject.sendIfChanged(BREAK_QUIESCENCE);
        subject.sendIfChanged(DONT_QUIESCE);

        verify(platform).quiescenceCommand(QUIESCE);
        verify(platform).quiescenceCommand(BREAK_QUIESCENCE);
        verify(platform).quiescenceCommand(DONT_QUIESCE);
        assertThat(subject.lastSent()).isEqualTo(DONT_QUIESCE);
    }

    @Test
    void sendDispatchesUnconditionallyAndUpdates() {
        subject.send(DONT_QUIESCE);

        verify(platform).quiescenceCommand(DONT_QUIESCE);
        assertThat(subject.lastSent()).isEqualTo(DONT_QUIESCE);
    }

    @Test
    void sendDispatchesEvenWhenAlreadyAtTarget() {
        subject.send(DONT_QUIESCE);
        subject.send(DONT_QUIESCE);

        verify(platform, times(2)).quiescenceCommand(DONT_QUIESCE);
    }

    @Test
    void sendIfChangedDoesNotDispatchPlatformWhenInitialDontQuiesceMatches() {
        assertThat(subject.sendIfChanged(DONT_QUIESCE)).isFalse();

        verify(platform, never()).quiescenceCommand(DONT_QUIESCE);
    }
}
