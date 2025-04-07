// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.iss;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import java.util.Random;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class IssLeaf extends PartialMerkleLeaf implements MerkleLeaf {
    public static final long CLASS_ID = 0xb25a18b65db5a6ccL;
    public static final int CLASS_VERSION = 1;

    /** whether to write random data or not */
    private boolean writeRandom = false;

    public IssLeaf() {}

    public IssLeaf(IssLeaf source) {
        this.writeRandom = source.writeRandom;
    }

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        in.readInt();
    }

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        if (writeRandom) {
            out.writeInt(new Random().nextInt());
        } else {
            out.writeInt(0);
        }
    }

    @Override
    public IssLeaf copy() {
        return new IssLeaf(this);
    }

    public void setWriteRandom(boolean writeRandom) {
        this.writeRandom = writeRandom;
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return CLASS_VERSION;
    }
}
