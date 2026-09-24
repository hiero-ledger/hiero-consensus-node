// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.swirlds.state.State;
import org.junit.jupiter.api.Test;

class WorkingStateAccessorTest {
    private final WorkingStateAccessor subject = new WorkingStateAccessor();

    @Test
    void updatesWorkingState() {
        final State state = mock(State.class);

        subject.setState(state);

        assertSame(state, subject.getState());
    }

    @Test
    void rejectsNullState() {
        assertThrows(NullPointerException.class, () -> subject.setState(null));
    }
}
