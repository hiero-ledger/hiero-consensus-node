// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.specs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to specify optional configuration parameters that are specific to the container environment.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ContainerSpecs {

    /**
     * Existence of proxy between the nodes in the network. This enables functionality related to controlling latency,
     * bandwidth, node isolation, etc. Enabling proxy has a considerable latency cost, even if all latencies are set to
     * zero and bandwidth is unlimited. It is enabled by default, can be disabled mostly for the performance-sensitive
     * tests.
     */
    boolean proxyEnabled() default true;
}
