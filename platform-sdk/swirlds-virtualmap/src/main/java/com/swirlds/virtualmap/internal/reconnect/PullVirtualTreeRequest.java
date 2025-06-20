// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import com.swirlds.virtualmap.internal.Path;
import java.io.IOException;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Used during the synchronization protocol to send data needed to reconstruct a single virtual node.
 *
 * <p>On the learner side, a request is created with a path and a hash in the old learner
 * tree (if exists), then sent to the teacher. On the teacher side, requests are deserialized
 * from the stream, and for every request a response is sent back to the learner.
 */
public class PullVirtualTreeRequest implements SelfSerializable {

    private static final long CLASS_ID = 0xecfbef49a90334ffL;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    // Virtual node path. If the path is Path.INVALID_PATH, it indicates that the learner will
    // not send any more node requests to the teacher
    private long path;

    // Virtual node hash. If a node with the given path does not exist on the learner (path is
    // outside of range), NULL_HASH is used. If the path is Path.INVALID_PATH, the hash is null
    private Hash hash;

    /**
     * This constructor is used by the teacher to deserialize the request from the stream.
     */
    public PullVirtualTreeRequest() {}

    /**
     * This constructor is used by the learner to send requests to the teacher.
     */
    public PullVirtualTreeRequest(final long path, final Hash hash) {
        // Null hash for the terminating requests, non-null otherwise
        assert path == Path.INVALID_PATH || (path >= 0 && hash != null);
        this.path = path;
        this.hash = hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeLong(path);
        if (hash != null) {
            hash.getBytes().writeTo(out);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        path = in.readLong();
        if (path >= 0) {
            final byte[] hashBytes = new byte[DigestType.SHA_384.digestLength()];
            if (VirtualReconnectUtils.completelyRead(in, hashBytes) != DigestType.SHA_384.digestLength()) {
                throw new IOException("Failed to read node hash from the learner");
            }
            hash = new Hash(hashBytes, DigestType.SHA_384);
        }
    }

    public long getPath() {
        return path;
    }

    public Hash getHash() {
        return hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }
}
