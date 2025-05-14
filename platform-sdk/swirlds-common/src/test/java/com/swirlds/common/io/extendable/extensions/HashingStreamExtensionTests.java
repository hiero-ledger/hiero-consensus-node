// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.extendable.extensions;

import static com.swirlds.common.io.extendable.ExtendableOutputStream.extendOutputStream;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.io.extendable.ExtendableInputStream;
import com.swirlds.common.io.extendable.ExtendableOutputStream;
import com.swirlds.common.test.fixtures.io.extendable.StreamSanityChecks;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.Random;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HashingStreamExtension Tests")
class HashingStreamExtensionTests {

    @Test
    @DisplayName("Input Stream Sanity Test")
    void inputStreamSanityTest() throws IOException {
        StreamSanityChecks.inputStreamSanityCheck((final InputStream base) ->
                new ExtendableInputStream(base, new HashingStreamExtension(DigestType.SHA_384)));

        StreamSanityChecks.inputStreamSanityCheck((final InputStream base) -> {
            final HashingStreamExtension extension = new HashingStreamExtension(DigestType.SHA_384);
            extension.startHashing();
            return new ExtendableInputStream(base, extension);
        });
    }

    @Test
    @DisplayName("Output Stream Sanity Test")
    void outputStreamSanityTest() throws IOException {
        StreamSanityChecks.outputStreamSanityCheck((final OutputStream base) ->
                new ExtendableOutputStream(base, new HashingStreamExtension(DigestType.SHA_384)));

        StreamSanityChecks.outputStreamSanityCheck((final OutputStream base) -> {
            final HashingStreamExtension extension = new HashingStreamExtension(DigestType.SHA_384);
            extension.startHashing();
            return new ExtendableOutputStream(base, extension);
        });
    }

    @Test
    @DisplayName("Input Stream Test")
    void inputStreamTest() throws IOException, NoSuchAlgorithmException, NoSuchProviderException {
        final int size = 1024 * 1024;

        final Random random = getRandomPrintSeed();
        final byte[] bytes = new byte[size];
        random.nextBytes(bytes);

        final InputStream byteIn = new ByteArrayInputStream(bytes);
        final HashingStreamExtension extension = new HashingStreamExtension(DigestType.SHA_384);
        extension.startHashing();
        final InputStream in = new ExtendableInputStream(byteIn, extension);

        int remaining = size;

        while (remaining > 0) {
            if (random.nextBoolean()) {
                in.read();
                remaining--;
            }

            if (random.nextBoolean()) {
                in.read(new byte[1024], 0, 1024);
                remaining -= 1024;
            }

            if (random.nextBoolean()) {
                in.readNBytes(1024);
                remaining -= 1024;
            }

            if (random.nextBoolean()) {
                in.readNBytes(new byte[1024], 0, 1024);
                remaining -= 1024;
            }
        }

        final Hash computedHash = extension.finishHashing();

        final MessageDigest digest =
                MessageDigest.getInstance(DigestType.SHA_384.algorithmName(), DigestType.SHA_384.provider());

        digest.update(bytes);
        final Hash expectedHash = new Hash(digest.digest(), DigestType.SHA_384);

        assertEquals(expectedHash, computedHash, "hash should match");
    }

    @Test
    @DisplayName("Output Stream Test")
    void outputStreamTest() throws IOException, NoSuchAlgorithmException, NoSuchProviderException {
        final int size = 1024 * 1024;

        final Random random = getRandomPrintSeed();

        final byte[] bytes = new byte[size];
        random.nextBytes(bytes);

        final OutputStream byteOut = new ByteArrayOutputStream();
        final HashingStreamExtension extension = new HashingStreamExtension(DigestType.SHA_384);
        extension.startHashing();
        final CountingStreamExtension counter = new CountingStreamExtension();
        final OutputStream out = extendOutputStream(byteOut, extension, counter);

        int index = 0;

        while (size - index > 0) {
            if (random.nextBoolean()) {
                out.write(bytes[index]);
                index++;
            }

            if (random.nextBoolean()) {
                final int bytesToWrite = Math.min(1024, size - index);
                out.write(bytes, index, bytesToWrite);
                index += bytesToWrite;
            }
        }

        out.flush();

        assertEquals(size, counter.getCount());

        final Hash computedHash = extension.finishHashing();

        final MessageDigest digest =
                MessageDigest.getInstance(DigestType.SHA_384.algorithmName(), DigestType.SHA_384.provider());

        digest.update(bytes);
        final Hash expectedHash = new Hash(digest.digest(), DigestType.SHA_384);

        assertEquals(expectedHash, computedHash, "hash should match");
    }
}
