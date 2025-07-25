// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.nativelibverification;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.hyperledger.besu.crypto.Blake2bfMessageDigest;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.crypto.SECP256R1;
import org.hyperledger.besu.evm.precompile.AbstractAltBnPrecompiledContract;
import org.hyperledger.besu.evm.precompile.AbstractBLS12PrecompiledContract;
import org.hyperledger.besu.evm.precompile.BigIntegerModularExponentiationPrecompiledContract;

/**
 * This interface provides a list of besu native libraries and their native verification methods.
 */
public interface NativeLibrary {

    // We use a record to hold the name and the native verification method of the library
    record Library(String name, Supplier<Boolean> isNative) {}

    /**
     * Returns a list of default native libraries used by the Hedera smart contract service.
     * Each library is represented by a {@link Library} record containing its name and a method to check if it is native.
     *
     * @return a list of default native libraries
     */
    static List<Library> getDefaultNativeLibs() {
        final var libs = new ArrayList<Library>();

        libs.add(new Library("secp256k1", () -> new SECP256K1().isNative()));
        libs.add(new Library("secp256r1", () -> new SECP256R1().isNative()));
        libs.add(new Library("besu blake2bf", Blake2bfMessageDigest.Blake2bfDigest::isNative));
        libs.add(new Library("besu gnark", AbstractBLS12PrecompiledContract::isAvailable));
        libs.add(new Library("altbn128 gnark196", AbstractAltBnPrecompiledContract::maybeEnableNative));
        libs.add(new Library(
                "besu lib arithmetic", BigIntegerModularExponentiationPrecompiledContract::maybeEnableNative));
        return libs;
    }
}
