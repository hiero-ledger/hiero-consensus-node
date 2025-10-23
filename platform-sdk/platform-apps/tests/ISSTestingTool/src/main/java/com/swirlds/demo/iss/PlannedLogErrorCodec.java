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
 * Codec for serializing and deserializing {@link PlannedLogError} objects.
 */
public class PlannedLogErrorCodec implements Codec<PlannedLogError> {

    public static final PlannedLogErrorCodec INSTANCE = new PlannedLogErrorCodec();

    private static final PlannedLogError DEFAULT_VALUE = new PlannedLogError(Duration.ZERO, new ArrayList<>());

    @NonNull
    @Override
    public PlannedLogError parse(
            @NonNull ReadableSequentialData in,
            boolean strictMode,
            boolean parseUnknownFields,
            int maxDepth,
            int maxSize)
            throws ParseException {
        return new PlannedLogError(in);
    }

    @NonNull
    @Override
    public PlannedLogError parse(
            @NonNull final ReadableSequentialData in, boolean strictMode, boolean parseUnknownFields, int maxDepth) {
        return new PlannedLogError(in);
    }

    @Override
    public void write(@NonNull final PlannedLogError plannedLogError, @NonNull final WritableSequentialData out) {
        plannedLogError.writeTo(out);
    }

    @Override
    public int measure(@NonNull final ReadableSequentialData in) throws ParseException {
        final var start = in.position();
        parse(in);
        final var end = in.position();
        return (int) (end - start);
    }

    @Override
    public int measureRecord(@NonNull final PlannedLogError plannedLogError) {
        return plannedLogError.getSizeInBytes();
    }

    @Override
    public boolean fastEquals(
            @NonNull final PlannedLogError plannedLogError, @NonNull final ReadableSequentialData input)
            throws ParseException {
        final PlannedLogError other = parse(input);
        return plannedLogError.equals(other);
    }

    @Override
    public PlannedLogError getDefaultInstance() {
        return DEFAULT_VALUE;
    }
}
