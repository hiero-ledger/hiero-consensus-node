// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Singleton that provides access to the various handlers for the Schedule Service.
 */
@Singleton
public class ScheduleHandlers {

    private final ScheduleCreateHandler scheduleCreateHandler;

    private final ScheduleDeleteHandler scheduleDeleteHandler;

    private final ScheduleGetInfoHandler scheduleGetInfoHandler;

    private final ScheduleSignHandler scheduleSignHandler;

    /**
     * Instantiates a new Schedule handler.
     *
     * @param scheduleCreateHandler  the schedule create handler
     * @param scheduleDeleteHandler  the schedule delete handler
     * @param scheduleGetInfoHandler the schedule get info handler
     * @param scheduleSignHandler    the schedule sign handler
     */
    @Inject
    public ScheduleHandlers(
            @NonNull final ScheduleCreateHandler scheduleCreateHandler,
            @NonNull final ScheduleDeleteHandler scheduleDeleteHandler,
            @NonNull final ScheduleGetInfoHandler scheduleGetInfoHandler,
            @NonNull final ScheduleSignHandler scheduleSignHandler) {
        this.scheduleCreateHandler = requireNonNull(scheduleCreateHandler, "scheduleCreateHandler must not be null");
        this.scheduleDeleteHandler = requireNonNull(scheduleDeleteHandler, "scheduleDeleteHandler must not be null");
        this.scheduleGetInfoHandler = requireNonNull(scheduleGetInfoHandler, "scheduleGetInfoHandler must not be null");
        this.scheduleSignHandler = requireNonNull(scheduleSignHandler, "scheduleSignHandler must not be null");
    }

    /**
     * Schedule create handler.
     *
     * @return the schedule create handler
     */
    public ScheduleCreateHandler scheduleCreateHandler() {
        return scheduleCreateHandler;
    }

    /**
     * Schedule delete handler.
     *
     * @return the schedule delete handler
     */
    public ScheduleDeleteHandler scheduleDeleteHandler() {
        return scheduleDeleteHandler;
    }

    /**
     * Schedule get info handler.
     *
     * @return the schedule get info handler
     */
    public ScheduleGetInfoHandler scheduleGetInfoHandler() {
        return scheduleGetInfoHandler;
    }

    /**
     * Schedule sign handler.
     *
     * @return the schedule sign handler
     */
    public ScheduleSignHandler scheduleSignHandler() {
        return scheduleSignHandler;
    }
}
