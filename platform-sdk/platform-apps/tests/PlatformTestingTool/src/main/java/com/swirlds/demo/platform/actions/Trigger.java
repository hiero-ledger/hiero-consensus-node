// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.actions;

/**
 * A trigger for a given action that makes decisions based on the given node identifier and supplied state. This is
 * called by {@link TriggeredAction#check(Object, Object)} and it's variants to determine if conditions have been met by
 * the state instance.
 *
 * @param <N>
 * 		the type of the node identifier
 * @param <S>
 * 		the type of the state being tested
 */
@FunctionalInterface
public interface Trigger<N, S> {

    /**
     * Tests the supplied {@code state} originating from the specified {@code node} to determine if conditions have been
     * met that should trigger the associated {@link Action} to be fired.
     *
     * @param node
     * 		the node identifier of the network member who created the state
     * @param state
     * 		the state to be evaluated by this trigger
     * @return true if the conditions have been met and the associated {@link Action} should be fired; otherwise if
     * 		false is returned the associated {@link Action} will not be fired
     */
    boolean test(final N node, final S state);
}
