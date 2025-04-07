// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import com.swirlds.common.FastCopyable;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class RandomDelayCfg implements SelfSerializable, FastCopyable {

    /**
     * The version history of this class.
     * Versions that have been released must NEVER be given a different value.
     */
    private static class ClassVersion {
        private static final int ORIGINAL = 1;
        /**
         * In this version, serialization was performed by serialize/deserialize.
         */
        private static final int MIGRATE_TO_SERIALIZABLE = 2;
    }

    private static final long CLASS_ID = 0x3e33711ce8192758L;

    static Random random = new Random();

    private int[] possibilities;
    private int[] delayType; // 0 NO DELAY ,   1 SHORT DELAY  ,   2 LONG DELAY

    private int[] shortDelayRange; // two element array for short delay lower limit and upper limit
    private int[] longDelayRange; // two element array for long delay lower limit and upper limit

    private boolean immutable;

    private static int DEFAULT_MAX_ARRAY_SIZE = 1024 * 1024;

    public int[] getPossibilities() {
        return possibilities;
    }

    public void setPossibilities(int[] possibilities) {
        this.possibilities = possibilities;
    }

    public int[] getDelayType() {
        return delayType;
    }

    public void setDelayType(int[] delayType) {
        this.delayType = delayType;
    }

    public int[] getShortDelayRange() {
        return shortDelayRange;
    }

    public void setShortDelayRange(int[] shortDelayRange) {
        this.shortDelayRange = shortDelayRange;
    }

    public int[] getLongDelayRange() {
        return longDelayRange;
    }

    public void setLongDelayRange(int[] longDelayRange) {
        this.longDelayRange = longDelayRange;
    }

    public int getRandomDelay() {
        int delay = 0;
        int randomNumber = random.nextInt(100);

        if (randomNumber < 0 || randomNumber > 100) {
            delay = 0;
        } else {
            try {
                int sum = 0;
                int delayTypeSelected = 0;
                int low = 0;
                int high = 0;

                // random select a delay type
                for (int i = 0; i < possibilities.length; i++) {
                    if (randomNumber >= sum && randomNumber < (sum + possibilities[i])) {
                        delayTypeSelected = delayType[i];
                        break;
                    }
                    sum += possibilities[i];
                }

                if (delayTypeSelected == 1) {
                    low = shortDelayRange[0];
                    high = shortDelayRange[1];
                    delay = ThreadLocalRandom.current().nextInt(low, high + 1);

                } else if (delayTypeSelected == 2) {
                    low = longDelayRange[0];
                    high = longDelayRange[1];
                    delay = ThreadLocalRandom.current().nextInt(low, high + 1);

                } else {
                    delay = 0;
                }

                //				System.out.println("Delay type " + delayTypeSelected + " delay " + delay);

            } catch (ArrayIndexOutOfBoundsException e) {
                e.printStackTrace();
            }
        }
        return delay;
    }

    @Override
    public PayloadCfgSimple copy() {
        throwIfImmutable();
        return null;
    }

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeIntArray(possibilities);
        out.writeIntArray(delayType);
        out.writeIntArray(shortDelayRange);
        out.writeIntArray(longDelayRange);
    }

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        possibilities = in.readIntArray(DEFAULT_MAX_ARRAY_SIZE);
        delayType = in.readIntArray(DEFAULT_MAX_ARRAY_SIZE);
        shortDelayRange = in.readIntArray(DEFAULT_MAX_ARRAY_SIZE);
        longDelayRange = in.readIntArray(DEFAULT_MAX_ARRAY_SIZE);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        return this.immutable;
    }
}
