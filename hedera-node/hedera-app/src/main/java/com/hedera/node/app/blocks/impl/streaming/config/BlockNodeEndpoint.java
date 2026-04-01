// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

/**
 * Represents a block node endpoint address.
 *
 * @param host the host of the endpoint
 * @param port the port of the endpoint
 */
public record BlockNodeEndpoint(String host, int port) {}
