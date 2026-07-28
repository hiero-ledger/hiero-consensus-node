// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.framework.config;

import com.swirlds.base.state.Mutable;
import org.hiero.base.Copyable;

/**
 * Utility class for naming threads managed by thread configuration
 *
 * @param <T> class extending this thread, java generics trick for self-class reference
 */
public interface ThreadNamingConfiguration<T extends ThreadNamingConfiguration<T>> extends Copyable, Mutable {

    @SuppressWarnings("unchecked")
    T copy();

    /**
     * Generate name for a new thread
     * @return new thread name
     */
    String generateNextThreadName();

    /**
     * Enabling numbering threads, in case more than one should be created
     */
    void enableThreadNumbering();

    /**
     * Make this instance immutable
     */
    void becomeImmutable();
}
