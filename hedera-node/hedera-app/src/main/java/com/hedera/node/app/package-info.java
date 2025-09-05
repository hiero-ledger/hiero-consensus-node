// SPDX-License-Identifier: Apache-2.0
/**
 * Main package for the Hedera node application.
 *
 * <p>The Hashgraph Platform today follows a "container-managed" model, where applications extend from
 * {@link com.swirlds.platform.system.SwirldMain} to define their main entry point, much like a Java Applet would extend
 * from {@code Applet}. An application also extends from {@link com.swirlds.state.merkle.MerkleStateRoot} to define its
 * Merkle tree for holding state. The platform then dynamically looks up and creates these objects. The platform is thus
 * in charge of lifecycle management.
 *
 * <p>In the future, the Hashgraph Platform will instead use a "library" model, where the platform exposes
 * a {@code PlatformBuilder}, and the application is responsible for managing the lifecycle of the platform. The
 * platform will then be a library that the application can use to build a node. This makes testing much easier and the
 * code easier to understand.
 *
 * <p>The main entry point for the application today is {@link com.swirlds.platform.Browser}, which is
 * configured to find and load {@link com.hedera.node.app.ServicesMain} as the
 * {@link com.swirlds.platform.system.SwirldMain} of the application.
 *
 * <p><img src="startup-flow.png" alt="Startup Flow">
 */
package com.hedera.node.app;
