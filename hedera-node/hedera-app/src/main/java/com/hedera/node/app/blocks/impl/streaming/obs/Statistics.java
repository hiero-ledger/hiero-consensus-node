// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static com.hedera.node.app.blocks.impl.streaming.obs.ObsUtils.round;

public interface Statistics {

    ObsUnit unit();

    long samples();

    long sum();

    long min();

    long max();

    double avg();

    double stdDev();

    static String toString(final Statistics stats) {
        String s = "{ (Unit:" + stats.unit();

        s += "|Samples:" + stats.samples();
        s += "|Sum:" + stats.sum();
        s += "|Min:" + stats.min();
        s += "|Max:" + stats.max();
        s += "|Avg:" + round(stats.avg()).toPlainString();
        s += "|StdDev:" + round(stats.stdDev()).toPlainString();

        s += ") }";
        return s;
    }
}
