// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;

import com.swirlds.state.State;
import com.swirlds.state.StateChangeListener;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class StateTest {
    @Test
    void defaultCommitListenerIsNotSupported() {
        final var subject = mock(State.class);
        final var listener = mock(StateChangeListener.class);

        doCallRealMethod().when(subject).registerCommitListener(listener);
        assertThatThrownBy(() -> subject.registerCommitListener(listener))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void changeListenerHasDefaultNoopImplementations() {
        final var subject = new StateChangeListener() {
            @Override
            public Set<StateType> stateTypes() {
                return EnumSet.allOf(StateType.class);
            }
        };

        assertThatCode(() -> subject.mapUpdateChange(0, "key", "value")).doesNotThrowAnyException();
        assertThatCode(() -> subject.mapDeleteChange(0, "key")).doesNotThrowAnyException();
        assertThatCode(() -> subject.singletonUpdateChange(0, "value")).doesNotThrowAnyException();
        assertThatCode(() -> subject.queuePushChange(0, "value")).doesNotThrowAnyException();
        assertThatCode(() -> subject.queuePopChange(0)).doesNotThrowAnyException();
    }
}
