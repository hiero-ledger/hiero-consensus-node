// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

/**
 * The way a message was sent over a connection. Routes of different kinds can have very different delays on
 * the same connection, so arrival times only have to be kept in order within a kind.
 */
enum MessageRoute {
    /** A message pushed to the receiver as soon as it is created. */
    BROADCAST,
    /** A message sent in response to a sync with the receiver. */
    SYNC
}
