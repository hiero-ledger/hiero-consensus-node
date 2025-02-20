// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hederahashgraph.api.proto.java.FeeSchedule;
import java.util.List;
import java.util.stream.Collectors;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeeSchedulesListEntry {
    List<ScheduleEntryWrapperPojo> nextFeeSchedule;
    List<ScheduleEntryWrapperPojo> currentFeeSchedule;

    public static List<ScheduleEntryWrapperPojo> from(FeeSchedule grpc) {
        var list = grpc.getTransactionFeeScheduleList().stream()
                .map(ScheduleEntryWrapperPojo::from)
                .collect(Collectors.toList());
        var expiryEntry = new ScheduleEntryWrapperPojo();
        expiryEntry.setExpiryTime(grpc.getExpiryTime().getSeconds());
        list.add(expiryEntry);
        return list;
    }

    public static FeeSchedulesListEntry asNextFeeSchedule(List<ScheduleEntryWrapperPojo> entry) {
        var listEntry = new FeeSchedulesListEntry();
        listEntry.setNextFeeSchedule(entry);
        return listEntry;
    }

    public static FeeSchedulesListEntry asCurrentFeeSchedule(List<ScheduleEntryWrapperPojo> entry) {
        var listEntry = new FeeSchedulesListEntry();
        listEntry.setCurrentFeeSchedule(entry);
        return listEntry;
    }

    public List<ScheduleEntryWrapperPojo> getCurrentFeeSchedule() {
        return currentFeeSchedule;
    }

    public void setCurrentFeeSchedule(List<ScheduleEntryWrapperPojo> currentFeeSchedule) {
        this.currentFeeSchedule = currentFeeSchedule;
    }

    public List<ScheduleEntryWrapperPojo> getNextFeeSchedule() {
        return nextFeeSchedule;
    }

    public void setNextFeeSchedule(List<ScheduleEntryWrapperPojo> nextFeeSchedule) {
        this.nextFeeSchedule = nextFeeSchedule;
    }
}
