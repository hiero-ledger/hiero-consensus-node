// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework;

/**
 * Encapsulates an operation that is normally executed onto its own thread
 * so that it can be injected into a pre-existing thread.
 */
@FunctionalInterface
public interface ThreadSeed {

    /**
     * Inject this seed onto a thread. The seed will take over the thread and may
     * change thread settings. When the seed is finished with all of its work,
     * it will restore the original thread configuration and yield control back
     * to the caller. Until it yields control, this method will block.
     */
    void inject();
}
