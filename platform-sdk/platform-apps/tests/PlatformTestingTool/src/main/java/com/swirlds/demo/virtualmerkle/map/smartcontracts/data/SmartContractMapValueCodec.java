// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.map.smartcontracts.data;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

public class SmartContractMapValueCodec implements Codec<SmartContractMapValue> {

    public static final SmartContractMapValueCodec INSTANCE = new SmartContractMapValueCodec();

    private static final SmartContractMapValue DEFAULT_VALUE = new SmartContractMapValue(0);

    @Override
    public SmartContractMapValue getDefaultInstance() {
        return DEFAULT_VALUE;
    }

    @NonNull
    @Override
    public SmartContractMapValue parse(
            @NonNull ReadableSequentialData in, boolean strictMode, boolean parseUnknownFields, int maxDepth) {
        return new SmartContractMapValue(in);
    }

    @Override
    public void write(@NonNull SmartContractMapValue value, @NonNull WritableSequentialData out) throws IOException {
        value.writeTo(out);
    }

    @Override
    public int measure(@NonNull ReadableSequentialData in) {
        throw new UnsupportedOperationException("SmartContractMapValueCodec.measure() not implemented");
    }

    @Override
    public int measureRecord(SmartContractMapValue value) {
        return value.getSizeInBytes();
    }

    @Override
    public boolean fastEquals(@NonNull SmartContractMapValue value, @NonNull ReadableSequentialData in)
            throws ParseException {
        final SmartContractMapValue other = parse(in);
        return value.equals(other);
    }
}
