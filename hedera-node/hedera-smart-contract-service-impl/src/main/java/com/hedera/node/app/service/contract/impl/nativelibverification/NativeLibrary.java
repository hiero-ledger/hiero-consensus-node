// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.nativelibverification;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.hyperledger.besu.crypto.Blake2bfMessageDigest;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.crypto.SECP256R1;
import org.hyperledger.besu.evm.precompile.AbstractBLS12PrecompiledContract;
import org.hyperledger.besu.evm.precompile.BigIntegerModularExponentiationPrecompiledContract;

/**
 * This interface provides a list of besu native libraries and their native verification methods.
 */
public interface NativeLibrary {

    // We use a record to hold the name and the native verification method of the library
    record Library(String name, Supplier<Boolean> isNative) {}

    static List<Library> getDefaultNativeLibs() {
        return new ArrayList<>() {
            {
                add(new Library("secp256k1", () -> new SECP256K1().isNative()));
                add(new Library("secp256r1", () -> new SECP256R1().isNative()));
                add(new Library("besu blake2bf", Blake2bfMessageDigest.Blake2bfDigest::isNative));
                add(new Library("besu gnark", AbstractBLS12PrecompiledContract::isAvailable));
                add(new Library(
                        "besu lib arithmetic", BigIntegerModularExponentiationPrecompiledContract::maybeEnableNative));
            }
        };
    }
}
