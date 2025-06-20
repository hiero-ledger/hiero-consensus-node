// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.domain.throttling;

import java.util.ArrayList;
import java.util.List;

public class ThrottleGroup<E extends Enum<E>> {
    private int opsPerSec;
    private long milliOpsPerSec;
    private List<E> operations = new ArrayList<>();

    public ThrottleGroup() {
        // Needed by Jackson
    }

    public ThrottleGroup(final long milliOpsPerSec, final List<E> operations) {
        this.milliOpsPerSec = milliOpsPerSec;
        this.operations = operations;
    }

    public void setMilliOpsPerSec(long milliOpsPerSec) {
        this.milliOpsPerSec = milliOpsPerSec;
    }

    public int getOpsPerSec() {
        return opsPerSec;
    }

    public void setOpsPerSec(int opsPerSec) {
        this.opsPerSec = opsPerSec;
    }

    public List<E> getOperations() {
        return operations;
    }

    public void setOperations(List<E> operations) {
        this.operations = operations;
    }

    public long getMilliOpsPerSec() {
        return milliOpsPerSec;
    }

    public long impliedMilliOpsPerSec() {
        return milliOpsPerSec > 0 ? milliOpsPerSec : 1_000 * opsPerSec;
    }
}
