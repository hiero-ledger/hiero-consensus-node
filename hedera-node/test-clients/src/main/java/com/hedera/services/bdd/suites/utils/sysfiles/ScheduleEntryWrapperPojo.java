// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hederahashgraph.api.proto.java.TransactionFeeSchedule;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScheduleEntryWrapperPojo {
    Long expiryTime;
    ScheduleEntryPojo transactionFeeSchedule;

    public static ScheduleEntryWrapperPojo from(TransactionFeeSchedule grpc) {
        var pojo = new ScheduleEntryWrapperPojo();
        pojo.setTransactionFeeSchedule(ScheduleEntryPojo.from(grpc));
        return pojo;
    }

    public ScheduleEntryPojo getTransactionFeeSchedule() {
        return transactionFeeSchedule;
    }

    public void setTransactionFeeSchedule(ScheduleEntryPojo transactionFeeSchedule) {
        this.transactionFeeSchedule = transactionFeeSchedule;
    }

    public Long getExpiryTime() {
        return expiryTime;
    }

    public void setExpiryTime(Long expiryTime) {
        this.expiryTime = expiryTime;
    }
}
