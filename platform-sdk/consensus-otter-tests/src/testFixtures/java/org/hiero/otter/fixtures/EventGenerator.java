package org.hiero.otter.fixtures;

public interface EventGenerator {

    int UNLIMITED = -1;

    void generateTransactions(int count, Rate rate, Distribution distribution);

    class Rate {
        public static Rate regularRateWithTps(int tps) {
            return null;
        }
    }

    enum Distribution {
        UNIFORM
    }
}
