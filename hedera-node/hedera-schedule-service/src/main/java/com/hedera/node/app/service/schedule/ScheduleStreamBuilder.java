// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule;

import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This record builder interface defines the methods needed to record schedule transactions as well
 * as adding a reference ID to the scheduled child transaction when it is executed.
 * <p>
 * It is important to note that any implementation cannot merely implement this interface.  Any
 * implementation must also implement all other record builder interfaces, as the child transaction
 * to which an instance of the implementation will be provided may be substantially any non-query
 * transaction other than a ScheduleCreate, ScheduleSign, or ScheduleDelete.
 */
public interface ScheduleStreamBuilder extends StreamBuilder {
    /**
     * Schedule ref schedule record builder.
     *
     * @param scheduleRef the schedule ref
     * @return the schedule record builder
     */
    @NonNull
    ScheduleStreamBuilder scheduleRef(ScheduleID scheduleRef);

    /**
     * Schedule id schedule record builder.
     *
     * @param scheduleID the schedule id
     * @return the schedule record builder
     */
    @NonNull
    ScheduleStreamBuilder scheduleID(ScheduleID scheduleID);

    /**
     * Scheduled transaction id schedule record builder.
     *
     * @param scheduledTransactionID the scheduled transaction id
     * @return the schedule record builder
     */
    @NonNull
    ScheduleStreamBuilder scheduledTransactionID(TransactionID scheduledTransactionID);
}
