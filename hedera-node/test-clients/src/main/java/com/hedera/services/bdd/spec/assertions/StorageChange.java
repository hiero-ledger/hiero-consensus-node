// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import edu.umd.cs.findbugs.annotations.NonNull;

public class StorageChange {
    private ByteString slot;
    private ByteString valueRead;
    private BytesValue valueWritten;

    private StorageChange(ByteString slot, ByteString value) {
        this.slot = slot;
        this.valueRead = value;
    }

    private StorageChange(ByteString slot, ByteString prevValue, BytesValue value) {
        this.slot = slot;
        this.valueRead = prevValue;
        this.valueWritten = value;
    }

    public static StorageChange onlyRead(ByteString slot, ByteString value) {
        return new StorageChange(slot, withoutLeadingZeroes(value));
    }

    public static StorageChange readAndWritten(ByteString slot, ByteString prevValue, ByteString value) {
        return new StorageChange(slot, prevValue, BytesValue.of(value));
    }

    public ByteString getSlot() {
        return this.slot;
    }

    public ByteString getValueRead() {
        return this.valueRead;
    }

    public BytesValue getValueWritten() {
        return this.valueWritten;
    }

    private static ByteString withoutLeadingZeroes(@NonNull final ByteString value) {
        int i = 0;
        while (i < value.size() && value.byteAt(i) == 0) {
            i++;
        }
        return value.substring(i);
    }
}
