// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec;

public interface HapiSpecOpFinisher extends Comparable<HapiSpecOpFinisher> {
    long submitTime();

    void finishFor(HapiSpec spec) throws Throwable;

    @Override
    default int compareTo(HapiSpecOpFinisher o) {
        return Long.compare(submitTime(), o.submitTime());
    }
}
