package org.hiero.consensus.gossip.impl.network;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.MappedByteBuffer;

public class ShmOutputStream extends OutputStream {


    private final MappedByteBuffer buffer;

    public ShmOutputStream(final MappedByteBuffer selfToOtherBuffer) {
        this.buffer = selfToOtherBuffer;
    }

    @Override
    public void write(final int b) throws IOException {
        byte[] bArray = new byte[1];
        bArray[0] = (byte) b;
        write(bArray, 0, 1);
    }

    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
        if ( len <= 0 ) {
            return;
        }
        int position = buffer.position();
        buffer.position(position + 4);
        buffer.put(b, off, len);
        buffer.position(position);
        buffer.putInt(len);
        buffer.position(position + len + 4);
        buffer.force();

    }
}
