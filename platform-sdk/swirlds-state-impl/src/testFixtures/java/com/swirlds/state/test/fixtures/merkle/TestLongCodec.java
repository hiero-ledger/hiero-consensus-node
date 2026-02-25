// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * This is a test-only class used exclusively by MerkleTestBase.
 * <strong>This class should not be used elsewhere.</strong>
 * The use of Codec implementations is not ideal, and we have the protobuf ProtoLong as
 * a replacement.  When time permits this class needs to be removed and all usages replaced
 * with ProtoLong and the ProtoLong.PROTOBUF codec.
 * @deprecated Use ProtoLong and ProtoLong.PROTOBUF instead of Long and this codec.
 */
@Deprecated
@SuppressWarnings("Singleton")
public class TestLongCodec implements Codec<Long> {

    public static final TestLongCodec SINGLETON = new TestLongCodec();

    private static final Long DEFAULT_VALUE = 0L;

    private TestLongCodec() {}

    @Override
    public Long getDefaultInstance() {
        return DEFAULT_VALUE;
    }

    @NonNull
    @Override
    public Long parse(
            @NonNull ReadableSequentialData input,
            boolean strictMode,
            boolean parseUnknownFields,
            int maxDepth,
            int maxSize)
            throws ParseException {
        Objects.requireNonNull(input);
        return Long.valueOf(input.readLong());
    }

    @NonNull
    @Override
    public Long parse(@NonNull final ReadableSequentialData input, final boolean strictMode, final int maxDepth)
            throws ParseException {
        Objects.requireNonNull(input);
        return Long.valueOf(input.readLong());
    }

    @Override
    public void write(@NonNull Long value, @NonNull WritableSequentialData output) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(output);
        output.writeLong(value);
    }

    @Override
    public int measure(@Nullable ReadableSequentialData input) {
        return Long.BYTES;
    }

    @Override
    public int measureRecord(@Nullable Long aLong) {
        return Long.BYTES;
    }

    @Override
    public boolean fastEquals(@NonNull final Long value, @NonNull final ReadableSequentialData input)
            throws ParseException {
        Objects.requireNonNull(value);
        Objects.requireNonNull(input);
        return value.equals(parse(input));
    }
}
