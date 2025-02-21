// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A utility class for obtaining the command line parameters when launching the platform using Browser.java.
 *
 * @deprecated this class will be removed at a future date, use the configuration engine for app config when launching
 * from the browser
 */
@Deprecated(forRemoval = true)
public final class ParameterProvider {

    private String[] parameters;
    private static final ParameterProvider INSTANCE = new ParameterProvider();

    private ParameterProvider() {}

    /**
     * Get the static instance of the ParameterProvider.
     *
     * @return the static instance of the ParameterProvider
     */
    public static ParameterProvider getInstance() {
        return INSTANCE;
    }

    /**
     * Set the command line parameters.
     *
     * @param parameters the command line parameters
     */
    public synchronized void setParameters(@Nullable final String[] parameters) {
        this.parameters = parameters;
    }

    /**
     * Get the command line parameters. Only populated of launching using the Browser.java.
     *
     * @return the command line parameters
     */
    public synchronized @Nullable String[] getParameters() {
        return parameters;
    }
}
