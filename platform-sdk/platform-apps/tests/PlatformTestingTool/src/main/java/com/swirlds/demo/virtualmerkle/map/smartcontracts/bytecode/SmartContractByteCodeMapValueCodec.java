// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

public class SmartContractByteCodeMapValueCodec implements Codec<SmartContractByteCodeMapValue> {

    public static final SmartContractByteCodeMapValueCodec INSTANCE = new SmartContractByteCodeMapValueCodec();

    private static final SmartContractByteCodeMapValue DEFAULT_VALUE = new SmartContractByteCodeMapValue(new byte[0]);

    @Override
    public SmartContractByteCodeMapValue getDefaultInstance() {
        return DEFAULT_VALUE;
    }

    @NonNull
    @Override
    public SmartContractByteCodeMapValue parse(
            @NonNull ReadableSequentialData in,
            boolean strictMode,
            boolean parseUnknownFields,
            int maxDepth,
            int maxSize) {
        return new SmartContractByteCodeMapValue(in);
    }

    @Override
    public void write(@NonNull SmartContractByteCodeMapValue value, @NonNull WritableSequentialData out)
            throws IOException {
        value.writeTo(out);
    }

    @Override
    public int measure(@NonNull ReadableSequentialData in) {
        throw new UnsupportedOperationException("SmartContractByteCodeMapValueCodec.measure() not implemented");
    }

    @Override
    public int measureRecord(SmartContractByteCodeMapValue value) {
        return value.getSizeInBytes();
    }

    @Override
    public boolean fastEquals(@NonNull SmartContractByteCodeMapValue value, @NonNull ReadableSequentialData in)
            throws ParseException {
        final SmartContractByteCodeMapValue other = parse(in);
        return value.equals(other);
    }
}
