package org.hiero.consensus.network.simulation.fixtures;

import java.security.Provider;
import java.security.SecureRandom;
import java.security.SecureRandomSpi;
import java.security.Security;
import java.security.MessageDigest;

/**
 * An implementation of SecureRandom that produces deterministic output based on a provided seed. This is useful for
 * testing scenarios where reproducibility is important. The implementation uses SHA-256 to generate pseudorandom bytes
 * from the seed. The first call to setSeed initializes the internal state, and subsequent calls to setSeed are ignored
 * to maintain determinism.
 */
public class DeterministicSecureRandom extends SecureRandom {

    private static final String ALGORITHM = "DeterministicSHA256";
    private static final String PROVIDER = "DeterministicProvider";

    // Register the provider once
    static {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(new DeterministicProvider());
        }
    }

    // Public constructor taking a byte[] seed
    public static SecureRandom getInstance(final byte[] seed) {
        try {
            final SecureRandom r =
                    SecureRandom.getInstance(ALGORITHM, PROVIDER);
            // Pass seed via setSeed — the SPI will use it to initialize state
            r.setSeed(seed);
            return r;
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static SecureRandom getInstance(final long seed) {
        return getInstance(longToBytes(seed));
    }

    // ----------------------------------------------------------------
    // Provider
    // ----------------------------------------------------------------

    private static final class DeterministicProvider extends Provider {
        DeterministicProvider() {
            super(PROVIDER, "1.0", "Deterministic SHA-256 PRNG");
            put("SecureRandom." + ALGORITHM, DeterministicSpi.class.getName());
        }
    }

    // ----------------------------------------------------------------
    // SPI
    // ----------------------------------------------------------------

    public static final class DeterministicSpi extends SecureRandomSpi {
        private MessageDigest digest;
        private byte[] state;
        private byte[] buffer = new byte[0];
        private int bufferPos = 0;
        private boolean initialized = false;

        public DeterministicSpi() {} // required public no-arg constructor

        private void init(final byte[] seed) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
                state = digest.digest(seed);
                buffer = new byte[0];
                bufferPos = 0;
                initialized = true;
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        private void ensureInitialized() {
            if (!initialized) {
                init(new byte[8]); // default zero seed
            }
        }

        private void refillBuffer() {
            buffer = digest.digest(state);
            bufferPos = 0;
            state = digest.digest(state);
        }

        @Override
        protected void engineSetSeed(final byte[] seed) {
            // First call initializes; subsequent calls are ignored
            // to keep output fully deterministic
            if (!initialized) {
                init(seed);
            }
        }

        @Override
        protected void engineNextBytes(final byte[] out) {
            ensureInitialized();
            int offset = 0;
            while (offset < out.length) {
                if (bufferPos >= buffer.length) {
                    refillBuffer();
                }
                final int available = buffer.length - bufferPos;
                final int needed = out.length - offset;
                final int toCopy = Math.min(available, needed);
                System.arraycopy(buffer, bufferPos, out, offset, toCopy);
                bufferPos += toCopy;
                offset += toCopy;
            }
        }

        @Override
        protected byte[] engineGenerateSeed(final int numBytes) {
            final byte[] seed = new byte[numBytes];
            engineNextBytes(seed);
            return seed;
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static byte[] longToBytes(long v) {
        final byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) (v & 0xFF);
            v >>= 8;
        }
        return b;
    }
}
