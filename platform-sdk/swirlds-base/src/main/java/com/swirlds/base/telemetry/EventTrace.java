// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.telemetry;

/**
 * JFR event for tracing consensus events.
 */
@jdk.jfr.Name("consensus.Event")
@jdk.jfr.Label("Consensus Event")
@jdk.jfr.Category({"Hiero"})
public class EventTrace extends jdk.jfr.Event {
    public enum EventType {
        @jdk.jfr.Label("Created")
        CREATED,
        @jdk.jfr.Label("Gossiped")
        GOSSIPED,
        @jdk.jfr.Label("Received")
        RECEIVED,
        @jdk.jfr.Label("PreHandled")
        PRE_HANDLED,
    }
    public int eventHash; // EventCore.hashCode() value
    public int eventType;
}
