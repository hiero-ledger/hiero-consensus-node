// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static java.util.Objects.requireNonNull;

/**
 * Singleton that provides access to the various handlers for the Schedule Service.
 */
public record ScheduleHandlers(
        ScheduleCreateHandler scheduleCreateHandler,
        ScheduleDeleteHandler scheduleDeleteHandler,
        ScheduleGetInfoHandler scheduleGetInfoHandler,
        ScheduleSignHandler scheduleSignHandler) {
    public ScheduleHandlers {
        requireNonNull(scheduleCreateHandler);
        requireNonNull(scheduleDeleteHandler);
        requireNonNull(scheduleGetInfoHandler);
        requireNonNull(scheduleSignHandler);
    }
}
