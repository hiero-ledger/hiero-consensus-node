// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import org.apache.commons.lang3.StringUtils;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.MainnetEVMs;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.operation.OperationRegistry;

/**
 * Functional interface for registering EVM operations into an {@link OperationRegistry}.
 *
 * <p>This interface provides a clean abstraction over the reflection-based invocation of
 * private {@code registerXxxOperations} methods in Besu's {@link MainnetEVMs} class.
 * Use {@link #forVersion(EvmSpecVersion)} to obtain a registrar for a specific EVM version.
 */
@FunctionalInterface
public interface HederaOperationsRegistry {

    /**
     * Registers EVM operations into the given registry.
     *
     * @param registry the operation registry to populate
     * @param gasCalculator the gas calculator for the operations
     * @param chainId the chain ID
     * @param evmConfiguration the EVM configuration
     */
    void register(
            @NonNull OperationRegistry registry,
            @NonNull GasCalculator gasCalculator,
            @NonNull BigInteger chainId,
            @NonNull EvmConfiguration evmConfiguration);

    /**
     * Creates an {@link HederaOperationsRegistry} for the specified EVM version.
     *
     * <p>The reflection lookup for the corresponding {@code registerXxxOperations} method
     * happens once when this method is called. The returned registrar caches the method
     * reference and can be invoked multiple times efficiently.
     *
     * @param version the EVM specification version
     * @return a registrar that will populate an {@link OperationRegistry} with operations for the given version
     * @throws IllegalStateException if no registration method exists for the specified version
     */
    static HederaOperationsRegistry forVersion(@NonNull final EvmSpecVersion version) {
        final var methodName =
                "register" + StringUtils.capitalize(version.name().toLowerCase()) + "Operations";
        final var method = getMethod(version, methodName);
        return (registry, gasCalculator, chainId, evmConfiguration) -> {
            try {
                method.invoke(null, registry, gasCalculator, chainId, evmConfiguration);
            } catch (final IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Failed to register " + version.name() + " EVM operations", e);
            }
        };
    }

    @NonNull
    private static Method getMethod(@NonNull final EvmSpecVersion version, final String methodName) {
        final Method method;
        try {
            method = MainnetEVMs.class.getDeclaredMethod(
                    methodName, OperationRegistry.class, GasCalculator.class, BigInteger.class, EvmConfiguration.class);
            method.setAccessible(true);
        } catch (final NoSuchMethodException e) {
            throw new IllegalStateException(
                    "No registration method '" + methodName + "' found for EVM version " + version.name(), e);
        }
        return method;
    }
}
