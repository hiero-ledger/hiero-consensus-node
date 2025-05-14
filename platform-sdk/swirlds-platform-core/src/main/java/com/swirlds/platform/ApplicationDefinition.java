// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.Objects;
import org.hiero.consensus.model.roster.AddressBook;

/**
 * Temporary internal only class to facilitate an incremental refactor of the {@code com.swirlds.platform.Browser} class.
 * Will not be providing javadoc on class members due to ephemeral nature of this temporary class.
 */
public class ApplicationDefinition {

    private final String swirldName;
    private final String[] appParameters;
    private final String mainClassName;
    private final Path appJarPath;
    private final AddressBook configAddressBook;

    public ApplicationDefinition(
            @NonNull final String swirldName,
            @NonNull final String[] appParameters,
            @NonNull final String mainClassName,
            @NonNull final Path appJarPath,
            @NonNull final AddressBook configAddressBook) {
        this.swirldName = Objects.requireNonNull(swirldName, "swirldName must not be null");
        this.appParameters = Objects.requireNonNull(appParameters, "appParameters must not be null");
        this.mainClassName = Objects.requireNonNull(mainClassName, "mainClassName must not be null");
        this.appJarPath = Objects.requireNonNull(appJarPath, "appJarPath must not be null");
        this.configAddressBook = Objects.requireNonNull(configAddressBook, "configAddressBook must not be null");
    }

    public String getSwirldName() {
        return swirldName;
    }

    public String[] getAppParameters() {
        return appParameters;
    }

    public String getMainClassName() {
        return mainClassName;
    }

    public String getApplicationName() {
        return mainClassName.substring(0, mainClassName.length() - 4);
    }

    public Path getAppJarPath() {
        return appJarPath;
    }

    public AddressBook getConfigAddressBook() {
        return configAddressBook;
    }
}
