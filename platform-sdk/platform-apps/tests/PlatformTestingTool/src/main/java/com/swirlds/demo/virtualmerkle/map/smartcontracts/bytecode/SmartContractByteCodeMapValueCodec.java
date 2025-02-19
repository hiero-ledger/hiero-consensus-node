/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

public class SmartContractByteCodeMapValueCodec implements Codec<SmartContractByteCodeMapValue> {

    public static final SmartContractByteCodeMapValueCodec INSTANCE = new SmartContractByteCodeMapValueCodec();

    @NonNull
    @Override
    public SmartContractByteCodeMapValue parse(@NonNull ReadableSequentialData in, boolean strictMode, int maxDepth) {
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
