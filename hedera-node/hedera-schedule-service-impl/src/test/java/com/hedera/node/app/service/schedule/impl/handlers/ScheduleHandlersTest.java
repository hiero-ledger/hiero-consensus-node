// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleHandlersTest {
    @Mock
    private ScheduleCreateHandler scheduleCreateHandler;

    @Mock
    private ScheduleDeleteHandler scheduleDeleteHandler;

    @Mock
    private ScheduleGetInfoHandler scheduleGetInfoHandler;

    @Mock
    private ScheduleSignHandler scheduleSignHandler;

    @SuppressWarnings("DataFlowIssue")
    @Test
    void constructorNullArgsThrow() {
        Assertions.assertThatThrownBy(() ->
                        new ScheduleHandlers(null, scheduleDeleteHandler, scheduleGetInfoHandler, scheduleSignHandler))
                .isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() ->
                        new ScheduleHandlers(scheduleCreateHandler, null, scheduleGetInfoHandler, scheduleSignHandler))
                .isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() ->
                        new ScheduleHandlers(scheduleCreateHandler, scheduleDeleteHandler, null, scheduleSignHandler))
                .isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() -> new ScheduleHandlers(
                        scheduleCreateHandler, scheduleDeleteHandler, scheduleGetInfoHandler, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void gettersWork() {
        final var subject = new ScheduleHandlers(
                scheduleCreateHandler, scheduleDeleteHandler, scheduleGetInfoHandler, scheduleSignHandler);

        Assertions.assertThat(subject.scheduleCreateHandler()).isEqualTo(scheduleCreateHandler);
        Assertions.assertThat(subject.scheduleDeleteHandler()).isEqualTo(scheduleDeleteHandler);
        Assertions.assertThat(subject.scheduleGetInfoHandler()).isEqualTo(scheduleGetInfoHandler);
        Assertions.assertThat(subject.scheduleSignHandler()).isEqualTo(scheduleSignHandler);
    }
}
