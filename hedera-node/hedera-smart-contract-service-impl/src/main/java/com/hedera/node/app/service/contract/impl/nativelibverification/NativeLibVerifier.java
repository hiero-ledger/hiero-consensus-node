// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.nativelibverification;

import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A class to verify the presence of the Besu native libraries required by the Hedera smart contract service.
 */
@Singleton
public final class NativeLibVerifier {
    private static final Logger LOGGER = LogManager.getLogger(NativeLibVerifier.class);

    private final Supplier<ContractsConfig> contractsConfigSupplier;

    /**
     * Constructs a {@link NativeLibVerifier} with the given {@link Supplier}.
     *
     * @param contractsConfigSupplier the supplier to be used
     */
    public NativeLibVerifier(@NonNull final Supplier<ContractsConfig> contractsConfigSupplier) {
        this.contractsConfigSupplier = contractsConfigSupplier;
    }

    /**
     * Verifies that all native libraries are enabled.
     * Logs warning if any native library is not enabled
     */
    public void verifyNativeLibs() {
        final var nodeHaltEnabled = contractsConfigSupplier.get().nativeLibVerificationHaltEnabled();
        NativeLibrary.getDefaultNativeLibs().stream()
                .filter(lib -> !lib.isNative().get())
                .peek(lib -> LOGGER.warn("Native library {} is not present", lib.name()))
                .findAny()
                .ifPresent(lib -> {
                    if (nodeHaltEnabled) {
                        // if any of the native libraries is not present on the environment and
                        // `nativeLibVerificationHaltEnabled` is true we throw an exception to halt the node
                        throw new IllegalStateException("Native libraries are not present with halt mode enabled");
                    }
                });
    }
}
