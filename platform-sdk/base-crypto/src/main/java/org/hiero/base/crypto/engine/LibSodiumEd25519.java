// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.engine;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * Panama Foreign Function &amp; Memory API binding to libsodium's Ed25519 detached signature operations.
 *
 * <p>This class replaces the LazySodium JNA wrapper with direct downcall handles to the native
 * libsodium library, eliminating JNA overhead. All methods are thread-safe — the underlying
 * libsodium functions are reentrant and each call uses a confined {@link Arena} for temporary
 * memory allocations.
 *
 * <p>The native library is loaded once during class initialization. The loader first attempts
 * {@code SymbolLookup.libraryLookup("sodium", ...)} (standard OS library search), then falls
 * back to well-known installation paths for macOS and Linux.
 */
@SuppressWarnings("restricted")
public final class LibSodiumEd25519 {

    /** Ed25519 signature size in bytes. */
    public static final int SIGNATURE_BYTES = 64;
    /** Ed25519 public key size in bytes. */
    public static final int PUBLIC_KEY_BYTES = 32;
    /** Ed25519 secret key size in bytes (seed + public key). */
    public static final int SECRET_KEY_BYTES = 64;

    /** Singleton instance. */
    public static final LibSodiumEd25519 INSTANCE = new LibSodiumEd25519();

    private static final SymbolLookup SODIUM;
    private static final MethodHandle SIGN_DETACHED;
    private static final MethodHandle VERIFY_DETACHED;
    private static final MethodHandle KEYPAIR;

    static {
        SODIUM = loadLibrary();
        final Linker linker = Linker.nativeLinker();

        // int crypto_sign_detached(unsigned char *sig, unsigned long long *siglen_p,
        //                          const unsigned char *m, unsigned long long mlen,
        //                          const unsigned char *sk)
        SIGN_DETACHED = linker.downcallHandle(
                SODIUM.find("crypto_sign_detached").orElseThrow(() -> new UnsatisfiedLinkError("crypto_sign_detached")),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS));

        // int crypto_sign_verify_detached(const unsigned char *sig,
        //                                 const unsigned char *m, unsigned long long mlen,
        //                                 const unsigned char *pk)
        VERIFY_DETACHED = linker.downcallHandle(
                SODIUM.find("crypto_sign_verify_detached")
                        .orElseThrow(() -> new UnsatisfiedLinkError("crypto_sign_verify_detached")),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS));

        // int crypto_sign_keypair(unsigned char *pk, unsigned char *sk)
        KEYPAIR = linker.downcallHandle(
                SODIUM.find("crypto_sign_keypair").orElseThrow(() -> new UnsatisfiedLinkError("crypto_sign_keypair")),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    private LibSodiumEd25519() {}

    /**
     * Create a detached Ed25519 signature.
     *
     * @param sig        output buffer for the 64-byte signature
     * @param message    the message to sign
     * @param messageLen the number of bytes in {@code message} to sign
     * @param secretKey  the 64-byte secret key (seed || public key)
     * @return {@code true} if signing succeeded
     */
    public boolean cryptoSignDetached(final byte[] sig, final byte[] message, final long messageLen, final byte[] secretKey) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment sigSeg = arena.allocate(SIGNATURE_BYTES);
            final MemorySegment msgSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, message);
            final MemorySegment skSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, secretKey);

            final int result = (int) SIGN_DETACHED.invokeExact(sigSeg, MemorySegment.NULL, msgSeg, messageLen, skSeg);

            MemorySegment.copy(sigSeg, ValueLayout.JAVA_BYTE, 0, sig, 0, SIGNATURE_BYTES);
            return result == 0;
        } catch (final Throwable t) {
            throw new RuntimeException("crypto_sign_detached failed", t);
        }
    }

    /**
     * Verify a detached Ed25519 signature.
     *
     * @param sig        the 64-byte signature
     * @param message    the message that was signed
     * @param messageLen the number of bytes in {@code message}
     * @param publicKey  the 32-byte public key
     * @return {@code true} if the signature is valid
     */
    public boolean cryptoSignVerifyDetached(
            final byte[] sig, final byte[] message, final int messageLen, final byte[] publicKey) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment sigSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, sig);
            final MemorySegment msgSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, message);
            final MemorySegment pkSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, publicKey);

            final int result = (int) VERIFY_DETACHED.invokeExact(sigSeg, msgSeg, (long) messageLen, pkSeg);
            return result == 0;
        } catch (final Throwable t) {
            throw new RuntimeException("crypto_sign_verify_detached failed", t);
        }
    }

    /**
     * Generate an Ed25519 keypair.
     *
     * @param publicKey output buffer for the 32-byte public key
     * @param secretKey output buffer for the 64-byte secret key
     * @return {@code true} if keypair generation succeeded
     */
    public boolean cryptoSignKeypair(final byte[] publicKey, final byte[] secretKey) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment pkSeg = arena.allocate(PUBLIC_KEY_BYTES);
            final MemorySegment skSeg = arena.allocate(SECRET_KEY_BYTES);

            final int result = (int) KEYPAIR.invokeExact(pkSeg, skSeg);

            MemorySegment.copy(pkSeg, ValueLayout.JAVA_BYTE, 0, publicKey, 0, PUBLIC_KEY_BYTES);
            MemorySegment.copy(skSeg, ValueLayout.JAVA_BYTE, 0, secretKey, 0, SECRET_KEY_BYTES);
            return result == 0;
        } catch (final Throwable t) {
            throw new RuntimeException("crypto_sign_keypair failed", t);
        }
    }

    /**
     * Load the native libsodium library. Tries the standard OS library search first, then
     * falls back to well-known installation paths.
     */
    private static SymbolLookup loadLibrary() {
        // Try standard OS library search (works when libsodium is on LD_LIBRARY_PATH / system dirs)
        try {
            return SymbolLookup.libraryLookup("sodium", Arena.global());
        } catch (final IllegalArgumentException ignored) {
            // Not found via standard search — try well-known paths
        }

        final String[] fallbackPaths = {
            "/opt/homebrew/lib/libsodium.dylib", // macOS ARM (Homebrew)
            "/usr/local/lib/libsodium.dylib", // macOS x86 (Homebrew)
            "/usr/lib/x86_64-linux-gnu/libsodium.so", // Debian/Ubuntu x86_64
            "/usr/lib/aarch64-linux-gnu/libsodium.so", // Debian/Ubuntu ARM64
            "/usr/lib/libsodium.so", // Generic Linux
        };

        for (final String path : fallbackPaths) {
            try {
                return SymbolLookup.libraryLookup(Path.of(path), Arena.global());
            } catch (final IllegalArgumentException ignored) {
                // Try next path
            }
        }

        throw new UnsatisfiedLinkError(
                "Could not load libsodium. Install it with: "
                        + "brew install libsodium (macOS) or apt-get install libsodium-dev (Linux)");
    }
}
