// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import java.util.function.Supplier;

/**
 * An interface that defines EVM frame state factory instances
 */
@FunctionalInterface
public interface EvmFrameStateFactory extends Supplier<EvmFrameState> {}
