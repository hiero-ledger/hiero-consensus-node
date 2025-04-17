// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.test.fixtures;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.crypto.Message;

public final class MessageDigestPool {

    private ArrayList<KnownDigest> messages;
    private int poolSize;
    private int messageSize;
    private AtomicInteger readPosition;
    private Random random;

    private MessageDigest digest;

    public MessageDigestPool(final int poolSize, final int messageSize) throws NoSuchAlgorithmException {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize");
        }

        if (messageSize < 1) {
            throw new IllegalArgumentException("messageSize");
        }

        this.poolSize = poolSize;
        this.messageSize = messageSize;
        this.messages = new ArrayList<>(poolSize);
        this.readPosition = new AtomicInteger(0);
        this.random = new Random();
        this.digest = MessageDigest.getInstance("SHA-384");

        init();
    }

    public Message next() {
        int nextIdx = readPosition.getAndIncrement();

        if (nextIdx >= messages.size()) {
            nextIdx = 0;
            readPosition.set(1);
        }

        return messages.get(nextIdx).provide();
    }

    public int size() {
        return messages.size();
    }

    public boolean isValid(final Message message) {
        final int index = messages.indexOf(new KnownDigest(null, message));

        if (index < 0) {
            return false;
        }

        KnownDigest kd = messages.get(index);

        return (kd != null && kd.isValid(message.getHash().copyToByteArray()));
    }

    public boolean isValid(final Message message, final byte[] hash) {
        final int index = messages.indexOf(new KnownDigest(null, message));

        if (index < 0) {
            return false;
        }

        KnownDigest kd = messages.get(index);

        return (kd != null && kd.isValid(hash));
    }

    private void init() {
        for (int i = 0; i < poolSize; i++) {
            final byte[] payload = new byte[messageSize];
            random.nextBytes(payload);
            digest.update(payload);

            final byte[] hash = digest.digest();

            messages.add(new KnownDigest(hash, new Message(payload)));
        }
    }

    final class KnownDigest implements Comparable<KnownDigest> {

        private byte[] hash;
        private Message message;

        KnownDigest(final byte[] hash, final Message message) {
            this.hash = hash;
            this.message = message;
        }

        public byte[] getHash() {
            return hash;
        }

        public Message getMessage() {
            return message;
        }

        public boolean isValid(final byte[] hash) {
            if (hash == null || hash.length != this.hash.length) {
                return false;
            }

            return Arrays.compare(this.hash, hash) == 0;
        }

        public Message provide() {
            message.setHash(null);
            return message;
        }

        @Override
        public int hashCode() {
            return Objects.hash(message);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof KnownDigest)) {
                return false;
            }

            KnownDigest that = (KnownDigest) o;
            return message.equals(that.message);
        }

        @Override
        public int compareTo(final KnownDigest that) {
            if (this == that) {
                return 0;
            }

            if (that == null) {
                return 1;
            }

            return message.compareTo(that.message);
        }
    }
}
