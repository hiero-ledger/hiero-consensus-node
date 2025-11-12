// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Configuration for a single block node.
 *
 * @param address the address of the block node (domain name or IP address)
 * @param port the port to use when connecting to the block node (must be 1 or higher)
 * @param priority the priority of the block node (must be 0 or higher)
 * @param messageSizeSoftLimitBytes the message size soft limit (in bytes) - this size represents the normal request
 *                                  size (must be greater than 0)
 * @param messageSizeHardLimitBytes the message size hard limit (in byte) - this size is the maximum size a single
 *                                  request may "burst" to in order to accommodate large block items (must be greater
 *                                  than or equal to the soft limit)
 */
public record BlockNodeConfiguration(
        @NonNull String address,
        int port,
        int priority,
        long messageSizeSoftLimitBytes,
        long messageSizeHardLimitBytes) {

    public BlockNodeConfiguration {
        requireNonNull(address, "Address must be specified");

        if (address.isBlank()) {
            throw new IllegalArgumentException("Address must not be empty");
        }
        if (port < 1) {
            throw new IllegalArgumentException("Port must be greater than or equal to 1");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("Priority must be greater than or equal to 0");
        }
        if (messageSizeSoftLimitBytes <= 0) {
            throw new IllegalArgumentException("Message size soft limit must be greater than 0");
        }
        if (messageSizeHardLimitBytes < messageSizeSoftLimitBytes) {
            throw new IllegalArgumentException(
                    "Message size hard limit must be greater than or equal to soft limit size");
        }
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private String address;
        private int port;
        private int priority;
        private long messageSizeSoftLimitBytes;
        private long messageSizeHardLimitBytes;

        public Builder address(final String address) {
            this.address = address;
            return this;
        }

        public Builder port(final int port) {
            this.port = port;
            return this;
        }

        public Builder priority(final int priority) {
            this.priority = priority;
            return this;
        }

        public Builder messageSizeSoftLimitBytes(final long messageSizeSoftLimitBytes) {
            this.messageSizeSoftLimitBytes = messageSizeSoftLimitBytes;
            return this;
        }

        public Builder messageSizeHardLimitBytes(final long messageSizeHardLimitBytes) {
            this.messageSizeHardLimitBytes = messageSizeHardLimitBytes;
            return this;
        }

        public BlockNodeConfiguration build() {
            return new BlockNodeConfiguration(
                    address, port, priority, messageSizeSoftLimitBytes, messageSizeHardLimitBytes);
        }
    }
}
