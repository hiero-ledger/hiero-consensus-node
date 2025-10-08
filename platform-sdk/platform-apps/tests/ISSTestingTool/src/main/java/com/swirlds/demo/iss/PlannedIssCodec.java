// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.iss;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;

public class PlannedIssCodec implements Codec<PlannedIss> {

    public static final PlannedIssCodec INSTANCE = new PlannedIssCodec();

    private static final PlannedIss DEFAULT_VALUE = new PlannedIss(Duration.ZERO, new ArrayList<>());

    @NonNull
    @Override
    public PlannedIss parse(
            @NonNull ReadableSequentialData in,
            boolean strictMode,
            boolean parseUnknownFields,
            int maxDepth,
            int maxSize) {
        return new PlannedIss(in);
    }

    @Override
    public void write(@NonNull PlannedIss item, @NonNull WritableSequentialData out) {
        item.writeTo(out);
    }

    @Override
    public int measure(@NonNull ReadableSequentialData in) throws ParseException {
        final var start = in.position();
        parse(in);
        final var end = in.position();
        return (int) (end - start);
    }

    @Override
    public int measureRecord(PlannedIss plannedIss) {
        return plannedIss.getSizeInBytes();
    }

    @Override
    public boolean fastEquals(@NonNull PlannedIss plannedIss, @NonNull ReadableSequentialData input)
            throws ParseException {
        final PlannedIss other = parse(input);
        return plannedIss.equals(other);
    }

    @Override
    public PlannedIss getDefaultInstance() {
        return DEFAULT_VALUE;
    }
}
