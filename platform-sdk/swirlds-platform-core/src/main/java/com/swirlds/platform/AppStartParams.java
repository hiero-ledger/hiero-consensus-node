// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import java.nio.file.Path;

/**
 * Record that wraps the parameters based on the "app" property in the "config.txt"
 *
 * @param appParameters
 * 		the app parameters
 * @param appJarFilename
 * 		the name of the jar file of the app
 * @param mainClassname
 * 		the main class of the app
 * @param appJarPath
 * 		Path to the jar file
 * @deprecated will be removed once we do not use the format of the config.txt anymore
 */
@Deprecated(forRemoval = true)
public record AppStartParams(String[] appParameters, String appJarFilename, String mainClassname, Path appJarPath) {}
