// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.specs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to specify configuration parameters for running tests on remote machines via SSH.
 *
 * <p>SSH authentication and connection settings are expected to be configured in {@code ~/.ssh/config}.
 * Passwordless authentication (key-based) is required.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RemoteSpecs {

    /**
     * Comma-separated list of SSH host names or addresses (e.g. {@code "host1,host2"}). The hosts must be
     * reachable via SSH without a password prompt (configured in {@code ~/.ssh/config}). Nodes are distributed
     * across the given hosts in round-robin fashion.
     */
    String hosts();

    /**
     * Base working directory on the remote machines. Each node will use a subdirectory
     * ({@code <remoteWorkDir>/node-<id>/}) within this path. The directory is created automatically.
     */
    String remoteWorkDir() default "/opt/sloth";

    /**
     * Whether to clean up remote working directories after the test completes.
     */
    boolean cleanupOnDestroy() default true;

    /**
     * Path to the Java executable on the remote hosts. Defaults to {@code "java"} which assumes it is on the
     * remote {@code PATH}.
     */
    String remoteJavaPath() default "java";

    /**
     * Maximum number of nodes per host. When more nodes are requested than hosts, multiple nodes are placed on the
     * same host with unique port assignments. Defaults to {@code 1} (one node per host).
     */
    int nodesPerHost() default 1;
}
