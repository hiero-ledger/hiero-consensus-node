// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import java.io.IOException;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Used during the synchronization protocol to send data needed to reconstruct a single virtual node.
 *
 * <p>The teacher sends one response for every {@link PullVirtualTreeRequest} received from the
 * learner. Every response includes a path followed by an integer flag that indicates if the node
 * is clear (value 0, node hash on the teacher is the same as sent by the learner), or not (non-zero
 * value). If the path corresponds to a leaf node, and the node is not clear, a {@link
 * com.swirlds.virtualmap.datasource.VirtualLeafBytes} for the node is included in the end of the
 * response.
 */
public class PullVirtualTreeResponse implements SelfSerializable {

    private static final long CLASS_ID = 0xecfbef49a90334e3L;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    // Only used on the teacher side
    private final TeacherPullVirtualTreeView teacherView;

    // Only used on the learner side
    private final LearnerPullVirtualTreeView learnerView;

    // Virtual node path
    private long path;

    private boolean isClean;

    private long firstLeafPath = -1;
    private long lastLeafPath = -1;

    // If the response is not clean (learner hash != teacher hash), then leafData contains
    // the leaf data on the teacher side
    private VirtualLeafBytes<?> leafData;

    /**
     * Zero-arg constructor for constructable registry.
     */
    public PullVirtualTreeResponse() {
        teacherView = null;
        learnerView = null;
    }

    /**
     * This constructor is used by the teacher to create new responses.
     */
    public PullVirtualTreeResponse(
            final TeacherPullVirtualTreeView teacherView,
            final long path,
            final boolean isClean,
            final long firstLeafPath,
            final long lastLeafPath,
            final VirtualLeafBytes<?> leafData) {
        this.teacherView = teacherView;
        this.learnerView = null;
        this.path = path;
        this.isClean = isClean;
        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;
        this.leafData = leafData;
    }

    /**
     * This constructor is used by the learner when deserializing responses.
     *
     * @param learnerTreeView
     * 		the learner's view
     */
    public PullVirtualTreeResponse(final LearnerPullVirtualTreeView learnerTreeView) {
        this.teacherView = null;
        this.learnerView = learnerTreeView;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        assert teacherView != null;
        out.writeLong(path);
        out.write(isClean ? 0 : 1);
        if (path == Path.ROOT_PATH) {
            out.writeLong(firstLeafPath);
            out.writeLong(lastLeafPath);
        }
        if (leafData != null) {
            assert !isClean;
            final Bytes keyBytes = leafData.keyBytes();
            out.writeInt(Math.toIntExact(keyBytes.length()));
            keyBytes.writeTo(out);
            final Bytes valueBytes = leafData.valueBytes();
            if (valueBytes != null) {
                out.writeInt(Math.toIntExact(valueBytes.length()));
                valueBytes.writeTo(out);
            } else {
                out.writeInt(-1);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        assert learnerView != null;
        path = in.readLong();
        isClean = in.read() == 0;
        if (path == Path.ROOT_PATH) {
            firstLeafPath = in.readLong();
            lastLeafPath = in.readLong();
            return;
        }
        final boolean isLeaf = learnerView.isLeaf(path);
        if (isLeaf && !isClean) {
            final int keyBytesLen = in.readInt();
            final byte[] keyBytes = new byte[keyBytesLen];
            in.readFully(keyBytes);
            final int valueBytesLen = in.readInt();
            final byte[] valueBytes;
            if (valueBytesLen >= 0) {
                valueBytes = new byte[valueBytesLen];
                in.readFully(valueBytes);
            } else {
                valueBytes = null;
            }
            leafData = new VirtualLeafBytes<>(
                    path, Bytes.wrap(keyBytes), valueBytes != null ? Bytes.wrap(valueBytes) : null);
        }
        if (isLeaf) {
            learnerView.getMapStats().incrementLeafHashes(1, isClean ? 1 : 0);
        } else {
            learnerView.getMapStats().incrementInternalHashes(1, isClean ? 1 : 0);
        }
    }

    public long getPath() {
        return path;
    }

    public long getFirstLeafPath() {
        return firstLeafPath;
    }

    public long getLastLeafPath() {
        return lastLeafPath;
    }

    public boolean isClean() {
        return isClean;
    }

    public VirtualLeafBytes<?> getLeafData() {
        return leafData;
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
