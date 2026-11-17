// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A wrapper around a {@link ServiceEndpoint} that provides additional functionality and convenience methods.
 */
public class ServiceEndpointWrapper {

    @NonNull
    private final ServiceEndpoint serviceEndpoint;

    @NonNull
    private final String hostName;

    private ServiceEndpointWrapper(@NonNull final ServiceEndpoint serviceEndpoint) {
        this.serviceEndpoint = requireNonNull(serviceEndpoint);
        this.hostName = calculateHostName(serviceEndpoint);
    }

    @NonNull
    private static String calculateHostName(@NonNull final ServiceEndpoint serviceEndpoint) {
        final Bytes ipAddressV4 = serviceEndpoint.ipAddressV4();
        final long length = ipAddressV4.length();
        if (length == 0) {
            return serviceEndpoint.domainName();
        }
        if (length == 4) {
            return HapiUtils.asReadableIp(ipAddressV4);
        }
        throw new IllegalArgumentException(
                "Invalid IP address: " + ipAddressV4 + " in ServiceEndpoint: " + serviceEndpoint);
    }

    /**
     * Creates a new {@link ServiceEndpointWrapper} instance from the given {@link ServiceEndpoint}.
     *
     * @param serviceEndpoint the {@link ServiceEndpoint} to wrap
     * @return a new {@link ServiceEndpointWrapper} instance
     */
    @NonNull
    public static ServiceEndpointWrapper of(@NonNull final ServiceEndpoint serviceEndpoint) {
        return new ServiceEndpointWrapper(serviceEndpoint);
    }

    /**
     * Returns the host name of this service endpoint, which is either the domain name or the IPv4 address.
     *
     * @return the host name of this service endpoint
     */
    @NonNull
    public String hostName() {
        return hostName;
    }

    /**
     * Returns the port of this service endpoint.
     *
     * @return the port of this service endpoint
     */
    public int port() {
        return serviceEndpoint.port();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(@Nullable final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final ServiceEndpointWrapper that = (ServiceEndpointWrapper) o;
        return serviceEndpoint.equals(that.serviceEndpoint);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return serviceEndpoint.hashCode();
    }
}
