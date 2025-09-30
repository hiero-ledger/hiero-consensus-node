// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.nativelibverification;

import com.hedera.node.config.data.ContractsConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.crypto.Blake2bfMessageDigest;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.crypto.SECP256R1;
import org.hyperledger.besu.evm.precompile.AbstractAltBnPrecompiledContract;
import org.hyperledger.besu.evm.precompile.AbstractBLS12PrecompiledContract;
import org.hyperledger.besu.evm.precompile.BigIntegerModularExponentiationPrecompiledContract;

/**
 * This class defines the native libraries and provides a method to verify their presence.
 */
@Singleton
public final class NativeLibVerifier {
    private static final Logger LOGGER = LogManager.getLogger(NativeLibVerifier.class);

    /**
     * Record to hold the name and the native verification method of a library.
     */
    public record Library(String name, Supplier<Boolean> isNative) {}

    private final Supplier<ContractsConfig> contractsConfigSupplier;

    public NativeLibVerifier(final Supplier<ContractsConfig> contractsConfigSupplier) {
        this.contractsConfigSupplier = contractsConfigSupplier;
    }

    /**
     * Returns a list of the default native libraries used by the Hedera smart contract service.
     * Each library is represented by a {@link Library} record containing its name and a method to check if it is native.
     *
     * @return a list of default native libraries
     */
    public static List<Library> getDefaultNativeLibs() {
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

    /**
     * Verifies that all native libraries are enabled. Logs a warning if any native library is not enabled.
     * If halt mode is enabled and a library is missing, throws an exception to halt the node.
     */
    public void verifyNativeLibs() {
        final var nodeHaltEnabled = contractsConfigSupplier.get().nativeLibVerificationHaltEnabled();
        LOGGER.info("Native library verification Halt mode is {}", nodeHaltEnabled ? "enabled" : "disabled");
        getDefaultNativeLibs().stream()
                .filter(lib -> !lib.isNative().get())
                .peek(lib -> LOGGER.warn("Native library {} is not present", lib.name()))
                .findAny()
                .ifPresent(lib -> {
                    if (nodeHaltEnabled) {
                        // if any of the native libraries is not present on the environment and
                        // `nativeLibVerificationHaltEnabled` is true we throw an exception to halt the node
                        LOGGER.error(
                                "Native library {} is not present with halt mode enabled! Shutting down node.",
                                lib.name());
                        throw new IllegalStateException("Native libraries are not present with halt mode enabled");
                    }
                });
    }
}
