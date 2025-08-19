// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.transformers;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Data that can be routed to a specific address.
 *
 * @param address       the address to route to (will be an enum value from ROUTER_TYPE)
 * @param data          the data
 * @param <ROUTER_TYPE> the type of the enum that defines the addresses
 */
public record RoutableData<ROUTER_TYPE extends Enum<ROUTER_TYPE>>(@NonNull ROUTER_TYPE address, @NonNull Object data) {}
//TODO consider removing this functionality
