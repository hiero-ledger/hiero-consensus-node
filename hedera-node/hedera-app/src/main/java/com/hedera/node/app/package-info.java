// SPDX-License-Identifier: Apache-2.0
/**
 * Main package for the Hedera node application.
 *
 * <p>The Hashgraph Platform uses a "library" model, where the platform exposes
 * a {@link com.swirlds.platform.builder.PlatformBuilder}, and the application is responsible for
 * managing the lifecycle of the platform. The platform is a library that the application uses to
 * build a node. This makes testing much easier and the code easier to understand.
 *
 * <p>The main entry point for the application is {@link com.hedera.node.app.ServicesMain#main(String...)},
 * which builds and starts the platform using {@link com.swirlds.platform.builder.PlatformBuilder}.
 * The {@link com.hedera.node.app.Hedera} singleton centralizes nearly all the setup and runtime logic
 * for the application.
 *
 */
package com.hedera.node.app;
