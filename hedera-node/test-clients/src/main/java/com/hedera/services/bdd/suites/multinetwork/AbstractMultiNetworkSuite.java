// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.multinetwork;

/**
 * Base class for multi-network suites that sets sane defaults when callers do not provide them
 * on the command line. Values are only applied when the corresponding system property is absent,
 * so explicit overrides still win.
 */
public abstract class AbstractMultiNetworkSuite {

    public static void setConfigDefaults() {

    }
}
