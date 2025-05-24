// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.nativelibverification;

import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
     *
     * @throws IllegalStateException if any native library is not enabled and nativeLibVerificationHaltEnabled is true
     */
    public void verifyNativeLibs() throws IllegalStateException {
        final var nodeHaltEnabled = contractsConfigSupplier.get().nativeLibVerificationHaltEnabled();
        NativeLibrary.getDefaultNativeLibs().stream()
                .filter(lib -> !lib.isNative().get())
                .peek(lib -> LOGGER.warn("Native library {} is not present", lib.name()))
                .findAny()
                .ifPresent(lib -> {
                    if (nodeHaltEnabled) {
                        throw new IllegalStateException("Native libraries are not present with halt mode enabled");
                    }
                });
    }
}
