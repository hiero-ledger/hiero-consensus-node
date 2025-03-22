// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.demo.platform.fs.stresstest.proto.ControlType;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class ControlAction implements SelfSerializable {
    private static final long CLASS_ID = 0x9d36e40b51de36fdL;

    private Instant timestamp;
    private ControlType type;

    public ControlAction() {}

    public ControlAction(final Instant timestamp, final ControlType type) {
        this.timestamp = timestamp;
        this.type = type;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public ControlType getType() {
        return type;
    }

    public static ControlAction of(final ControlType type) {
        return new ControlAction(Instant.EPOCH, type);
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final ControlAction that = (ControlAction) other;
        return type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type);
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("timestamp", timestamp)
                .append("type", type)
                .toString();
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeInstant(timestamp);
        out.writeInt(type.getNumber());
    }

    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        this.timestamp = in.readInstant();
        this.type = ControlType.forNumber(in.readInt());
    }

    @Override
    public int getVersion() {
        return 1;
    }
}
