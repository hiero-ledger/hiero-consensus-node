// SPDX-License-Identifier: Apache-2.0
/**
 * The logging API is an abstraction layer for logging. It should be used for all logging operations. The API is based
 * on a plugin structure that allows to create custom {@link com.swirlds.logging.api.extensions.handler.LogHandler} and
 * {@link com.swirlds.logging.api.extensions.provider.LogProvider} implementations.
 * <p>
 * How to create a logger:
 * <pre>
 *
 *     // Create a logger for the class "MyClass"
 *     private static final Logger LOGGER = Loggers.getLogger(MyClass.class);
 * </pre>
 */
package com.swirlds.logging.api;
