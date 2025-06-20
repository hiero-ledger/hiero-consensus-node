// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.wires;

import com.swirlds.component.framework.wires.input.InputWire;

/**
 * The type of solder connection between an output wire and an input wire.
 */
public enum SolderType {
    /**
     * When data is passed to the input wire, call {@link InputWire#put(Object)}. May block if the input wire has
     * backpressure enabled and the input wire is full.
     */
    PUT,
    /**
     * When data is passed to the input wire, call {@link InputWire#inject(Object)}. Ignores back pressure.
     */
    INJECT,
    /**
     * When data is passed to the input wire, call {@link InputWire#offer(Object)}. If the input wire has backpressure
     * enabled and the input wire is full, then the data will be dropped.
     */
    OFFER
}
