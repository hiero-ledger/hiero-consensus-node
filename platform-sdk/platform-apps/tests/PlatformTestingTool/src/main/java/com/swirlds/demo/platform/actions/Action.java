// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.actions;

@FunctionalInterface
public interface Action<N, S> {

    void execute(final N node, final S state);
}
