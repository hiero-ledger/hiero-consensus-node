// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.iss;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;

/**
 * Codec for serializing and deserializing {@link PlannedIss} objects.
 */
public class PlannedIssCodec implements Codec<PlannedIss> {

    public static final PlannedIssCodec INSTANCE = new PlannedIssCodec();

    private static final PlannedIss DEFAULT_VALUE = new PlannedIss(Duration.ZERO, new ArrayList<>());

    @NonNull
    @Override
    public PlannedIss parse(
            @NonNull final ReadableSequentialData in, boolean strictMode, boolean parseUnknownFields, int maxDepth) {
        return new PlannedIss(in);
    }

    @Override
    public void write(@NonNull final PlannedIss item, @NonNull final WritableSequentialData out) {
        item.writeTo(out);
    }

    @Override
    public int measure(@NonNull final ReadableSequentialData in) throws ParseException {
        final var start = in.position();
        parse(in);
        final var end = in.position();
        return (int) (end - start);
    }

    @Override
    public int measureRecord(@NonNull final PlannedIss plannedIss) {
        return plannedIss.getSizeInBytes();
    }

    @Override
    public boolean fastEquals(@NonNull final PlannedIss plannedIss, @NonNull final ReadableSequentialData input)
            throws ParseException {
        final PlannedIss other = parse(input);
        return plannedIss.equals(other);
    }

    @Override
    public PlannedIss getDefaultInstance() {
        return DEFAULT_VALUE;
    }
}
