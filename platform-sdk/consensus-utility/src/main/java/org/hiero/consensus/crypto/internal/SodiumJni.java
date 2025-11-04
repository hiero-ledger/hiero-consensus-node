package org.hiero.consensus.crypto.internal;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Sign;

final class SodiumJni {
    /**
     * The JNI interface to the underlying native libSodium dynamic library. This variable is initialized when this
     * class is loaded and initialized by the {@link ClassLoader}.
     */
    static final Sign.Native SODIUM = new LazySodiumJava(new SodiumJava());
}
