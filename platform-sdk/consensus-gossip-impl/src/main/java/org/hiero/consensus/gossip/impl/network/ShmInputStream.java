package org.hiero.consensus.gossip.impl.network;

import java.io.IOException;
import java.io.InputStream;
import java.nio.MappedByteBuffer;

public class ShmInputStream extends InputStream {
    private final MappedByteBuffer buffer;
    private int toRead = 0;
    private int currentOffset = 0;
    private byte[] bufferBytes = new byte[0];

    public ShmInputStream(final MappedByteBuffer otherToSelfBuffer) {
        this.buffer = otherToSelfBuffer;
        this.buffer.position(0);
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        if (read(b, 0, 1) == -1) {
            return -1;
        } else {
            return b[0] & 0xff;
        }
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        if (toRead <= 0) {
            fillBuffer();
        }
        int chunk = Math.min(toRead, len);
        System.arraycopy(bufferBytes, currentOffset, b, off, chunk);
        toRead -= chunk;
        currentOffset += chunk;
        return chunk;
    }

    private void fillBuffer() {
        int position = buffer.position();
        while (this.buffer.getInt(position) == 0) {
            Thread.yield();
        }
        toRead = this.buffer.getInt();
        if (toRead > bufferBytes.length) {
            bufferBytes = new byte[toRead];
        }
        this.buffer.get(bufferBytes, 0, toRead);
        currentOffset = 0;
    }
}
