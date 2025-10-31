// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

public class AccountCodec implements Codec<Account> {

    public static final AccountCodec INSTANCE = new AccountCodec();

    private static final Account DEFAULT_VALUE = new Account();

    @Override
    public Account getDefaultInstance() {
        return DEFAULT_VALUE;
    }

    @NonNull
    @Override
    public Account parse(
            @NonNull ReadableSequentialData in,
            boolean strictMode,
            boolean parseUnknownFields,
            int maxDepth,
            int maxSize)
            throws ParseException {
        return new Account(in);
    }

    @NonNull
    @Override
    public Account parse(
            @NonNull ReadableSequentialData in, boolean strictMode, boolean parseUnknownFields, int maxDepth) {
        return new Account(in);
    }

    @Override
    public void write(@NonNull Account value, @NonNull WritableSequentialData out) throws IOException {
        value.writeTo(out);
    }

    @Override
    public int measure(@NonNull ReadableSequentialData in) {
        throw new UnsupportedOperationException("AccountVirtualMapValueCodec.measure() not implemented");
    }

    @Override
    public int measureRecord(Account value) {
        return value.getSizeInBytes();
    }

    @Override
    public boolean fastEquals(@NonNull Account value, @NonNull ReadableSequentialData in) throws ParseException {
        final Account other = parse(in);
        return value.equals(other);
    }
}
