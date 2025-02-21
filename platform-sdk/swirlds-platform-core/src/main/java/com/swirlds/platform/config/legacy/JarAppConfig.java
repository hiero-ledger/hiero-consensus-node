// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config.legacy;

/**
 * This record defines the set of parameters that can be defined for the {@code app} property in the legacy config.txt
 * file.
 *
 * @param jarName
 * 		the jarname param
 * @param params
 * 		all params (including "app" as the first param and thejarname param as second param)
 * @deprecated will be removed once we have removed the "legacy" {@code config.txt} file.
 */
@Deprecated(forRemoval = true)
public record JarAppConfig(String jarName, String[] params) {}
