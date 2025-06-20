// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * An OutputStream which creates a hash of all the bytes that go through it
 */
public class HashingOutputStream extends OutputStream {
    OutputStream out = null;
    MessageDigest md;

    /**
     * A constructor used to create an OutputStream that only does hashing
     *
     * @param md
     * 		the MessageDigest object that does the hashing
     */
    public HashingOutputStream(MessageDigest md) {
        super();
        this.md = md;
    }

    /**
     * A constructor used to create an OutputStream that hashes all the bytes that go though it, and also
     * writes them to the next OutputStream
     *
     * @param md
     * 		the MessageDigest object that will hash all bytes of the stream
     * @param out
     * 		the OutputStream where bytes will be sent to after being added to the hash
     */
    public HashingOutputStream(MessageDigest md, OutputStream out) {
        super();
        this.out = out;
        this.md = md;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(int arg0) throws IOException {
        md.update((byte) arg0);
        if (out != null) {
            out.write(arg0);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if ((off < 0) || (off > b.length) || (len < 0) || ((off + len) > b.length) || ((off + len) < 0)) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return;
        }

        md.update(b, off, len);
        if (out != null) {
            out.write(b, off, len);
        }
    }

    /**
     * Reset the digest used by the stream
     */
    public void resetDigest() {
        md.reset();
    }

    /**
     * Calculates and returns the digest of all the bytes that have been written to this stream. It also resets the
     * digest.
     *
     * @return the digest of all bytes written to the stream
     */
    public byte[] getDigest() {
        return md.digest();
    }
}
