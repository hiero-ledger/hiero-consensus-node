// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration.virtual;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

public class AccountVirtualMapValueCodec implements Codec<AccountVirtualMapValue> {

    public static final AccountVirtualMapValueCodec INSTANCE = new AccountVirtualMapValueCodec();

    private static final AccountVirtualMapValue DEFAULT_VALUE = new AccountVirtualMapValue(0, 0, 0, false, 0);

    @Override
    public AccountVirtualMapValue getDefaultInstance() {
        return DEFAULT_VALUE;
    }

    @NonNull
    @Override
    public AccountVirtualMapValue parse(
            @NonNull ReadableSequentialData in, boolean strictMode, boolean parseUnknownFields, int maxDepth) {
        return new AccountVirtualMapValue(in);
    }

    @Override
    public void write(@NonNull AccountVirtualMapValue value, @NonNull WritableSequentialData out) throws IOException {
        value.writeTo(out);
    }

    @Override
    public int measure(@NonNull ReadableSequentialData in) {
        throw new UnsupportedOperationException("AccountVirtualMapValueCodec.measure() not implemented");
    }

    @Override
    public int measureRecord(AccountVirtualMapValue value) {
        return value.getSizeInBytes();
    }

    @Override
    public boolean fastEquals(@NonNull AccountVirtualMapValue value, @NonNull ReadableSequentialData in)
            throws ParseException {
        final AccountVirtualMapValue other = parse(in);
        return value.equals(other);
    }
}
