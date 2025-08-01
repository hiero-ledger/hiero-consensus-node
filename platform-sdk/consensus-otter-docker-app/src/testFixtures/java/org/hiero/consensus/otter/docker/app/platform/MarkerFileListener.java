// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.platform;

import java.util.List;
import java.util.function.Consumer;

/**
 * Functional interface for receiving updates about marker files.
 */
@FunctionalInterface
public interface MarkerFileListener extends Consumer<List<String>> {}
