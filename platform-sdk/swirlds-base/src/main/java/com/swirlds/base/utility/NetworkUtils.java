// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.utility;

import static com.swirlds.base.utility.Retry.DEFAULT_RETRY_DELAY;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/**
 * Provides network-related utility methods.
 */
public final class NetworkUtils {

    /**
     * The maximum amount of time to wait for the DNS hostname to become resolvable.
     */
    public static final Duration DNS_RESOLUTION_WAIT_TIME = Duration.ofSeconds(60);

    /**
     * Private constructor to prevent utility class instantiation.
     */
    private NetworkUtils() {}

    /**
     * Evaluates a given {@code name} to ensure it is resolvable to one or more valid IPv4 or IPv6 addresses.
     * Supports domain name fragments, fully qualified domain names (FQDN), and IP addresses.
     *
     * <p>
     * This method includes retry logic to handle slow DNS queries due to network conditions, slow DNS record
     * updates/propagation, and/or intermittent network connections.
     *
     * <p>
     * This overloaded method uses a default wait time of {@link #DNS_RESOLUTION_WAIT_TIME} and a default retry delay of
     * {@link Retry#DEFAULT_RETRY_DELAY}.
     *
     * @param name the domain name fragment, fully qualified domain name (FQDN), or IP address to be resolved.
     * @return true if the name resolves to one or more valid IP addresses; otherwise false.
     * @see #isNameResolvable(String, Duration, Duration)
     */
    public static boolean isNameResolvable(@NonNull final String name) {
        return isNameResolvable(name, DNS_RESOLUTION_WAIT_TIME, DEFAULT_RETRY_DELAY);
    }

    /**
     * Evaluates a given {@code name} to ensure it is resolvable to one or more valid IPv4 or IPv6 addresses.
     * Supports domain name fragments, fully qualified domain names (FQDN), and IP addresses.
     *
     * <p>
     * This method includes retry logic to handle slow DNS queries due to network conditions, slow DNS record
     * updates/propagation, and/or intermittent network connections.
     *
     * <p>
     * This overloaded method uses a default retry delay of {@link Retry#DEFAULT_RETRY_DELAY}.
     *
     * @param name     the domain name fragment, fully qualified domain name (FQDN), or IP address to be resolved.
     * @param waitTime the maximum amount of time to wait for the DNS hostname to become resolvable.
     * @return true if the name resolves to one or more valid IP addresses; otherwise false.
     * @see #isNameResolvable(String, Duration, Duration)
     */
    public static boolean isNameResolvable(@NonNull final String name, @NonNull final Duration waitTime) {
        return isNameResolvable(name, waitTime, DEFAULT_RETRY_DELAY);
    }

    /**
     * Evaluates a given {@code name} to ensure it is resolvable to one or more valid IPv4 or IPv6 addresses.
     * Supports domain name fragments, fully qualified domain names (FQDN), and IP addresses.
     *
     * <p>
     * This method includes retry logic to handle slow DNS queries due to network conditions, slow DNS record
     * updates/propagation, and/or intermittent network connections.
     *
     * @param name       the domain name fragment, fully qualified domain name (FQDN), or IP address to be resolved.
     * @param waitTime   the maximum amount of time to wait for the DNS hostname to become resolvable.
     * @param retryDelay the delay between retry attempts.
     * @return true if the name resolves to one or more valid IP addresses; otherwise false.
     */
    public static boolean isNameResolvable(
            @NonNull final String name, @NonNull final Duration waitTime, @NonNull final Duration retryDelay) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(waitTime, "waitTime must not be null");
        Objects.requireNonNull(retryDelay, "retryDelay must not be null");
        try {
            return Retry.check(NetworkUtils::isNameResolvableInternal, name, waitTime, retryDelay);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Resolves a given {@code name} to a  valid IPv4 or IPv6 addresses.
     * Supports domain name fragments, fully qualified domain names (FQDN), and IP addresses.
     *
     * <p>
     * This method includes retry logic to handle slow DNS queries due to network conditions, slow DNS record
     * updates/propagation, and/or intermittent network connections.
     *
     * <p>
     * This overloaded method uses a default wait time of {@link #DNS_RESOLUTION_WAIT_TIME} and a default retry delay of
     * {@link Retry#DEFAULT_RETRY_DELAY}.
     *
     * @param name       the domain name fragment, fully qualified domain name (FQDN), or IP address to be resolved.
     * @return the resolved {@link InetAddress}; otherwise, {@code null} is returned if the operation was interrupted.
     * @throws UnknownHostException if the name cannot be resolved to a valid IP address.
     */
    public static InetAddress resolveName(@NonNull final String name) throws UnknownHostException {
        return resolveName(name, DNS_RESOLUTION_WAIT_TIME, DEFAULT_RETRY_DELAY);
    }

    /**
     * Resolves a given {@code name} to a  valid IPv4 or IPv6 addresses.
     * Supports domain name fragments, fully qualified domain names (FQDN), and IP addresses.
     *
     * <p>
     * This method includes retry logic to handle slow DNS queries due to network conditions, slow DNS record
     * updates/propagation, and/or intermittent network connections.
     *
     * <p>
     * This overloaded method uses a default retry delay of {@link Retry#DEFAULT_RETRY_DELAY}.
     *
     * @param name       the domain name fragment, fully qualified domain name (FQDN), or IP address to be resolved.
     * @param waitTime   the maximum amount of time to wait for the DNS hostname to become resolvable.
     * @return the resolved {@link InetAddress}; otherwise, {@code null} is returned if the operation was interrupted.
     * @throws UnknownHostException if the name cannot be resolved to a valid IP address.
     */
    public static InetAddress resolveName(@NonNull final String name, @NonNull final Duration waitTime)
            throws UnknownHostException {
        return resolveName(name, waitTime, DEFAULT_RETRY_DELAY);
    }

    /**
     * Resolves a given {@code name} to a  valid IPv4 or IPv6 addresses.
     * Supports domain name fragments, fully qualified domain names (FQDN), and IP addresses.
     *
     * <p>
     * This method includes retry logic to handle slow DNS queries due to network conditions, slow DNS record
     * updates/propagation, and/or intermittent network connections.
     *
     * @param name       the domain name fragment, fully qualified domain name (FQDN), or IP address to be resolved.
     * @param waitTime   the maximum amount of time to wait for the DNS hostname to become resolvable.
     * @param retryDelay the delay between retry attempts.
     * @return the resolved {@link InetAddress}; otherwise, {@code null} is returned if the operation was interrupted.
     * @throws UnknownHostException if the name cannot be resolved to a valid IP address.
     */
    public static InetAddress resolveName(
            @NonNull final String name, @NonNull final Duration waitTime, @NonNull final Duration retryDelay)
            throws UnknownHostException {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(waitTime, "waitTime must not be null");
        Objects.requireNonNull(retryDelay, "retryDelay must not be null");
        try {
            return Retry.resolve(InetAddress::getByName, name, waitTime, retryDelay);
        } catch (final ExecutionException ex) {
            throw new UnknownHostException(name + ": Name or service not known");
        } catch (final InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Internal implementation used by the {@link #isNameResolvable(String, Duration, Duration)} method.
     *
     * @param name the domain name fragment, fully qualified domain name (FQDN), or IP address to be resolved.
     * @return true if the name resolves to one or more valid IP addresses; otherwise false.
     */
    private static boolean isNameResolvableInternal(@NonNull final String name) {
        Objects.requireNonNull(name, "name must not be null");
        try {
            final InetAddress[] addresses = InetAddress.getAllByName(name);
            // If addresses is not null and has a length greater than zero (0), then we should consider this
            // address resolvable.
            if (addresses != null && addresses.length > 0) {
                return true;
            }
        } catch (final UnknownHostException ignored) {
            // Intentionally suppressed
        }

        return false;
    }
}
