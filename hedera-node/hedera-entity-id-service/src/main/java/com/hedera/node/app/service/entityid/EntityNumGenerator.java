// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid;

/**
 * Provides a way to generate entity numbers.
 */
public interface EntityNumGenerator {

    /**
     * Consumes and returns the next entity number, for use by handlers that create entities.
     *
     * <p>If this method is called after a child transaction was dispatched, which is subsequently rolled back,
     * the counter will be rolled back, too. Consequently, the provided number must not be used anymore in this case,
     * because it will be reused.
     *
     * @return the next entity number
     */
    long newEntityNum();

    /**
     * Peeks at the next entity number, for use by handlers that create entities.
     *
     * <p>If this method is called after a child transaction was dispatched, which is subsequently rolled back,
     * the counter will be rolled back, too. Consequently, the provided number must not be used anymore in this case,
     * because it will be reused.
     *
     * @return the next entity number
     */
    long peekAtNewEntityNum();
}
