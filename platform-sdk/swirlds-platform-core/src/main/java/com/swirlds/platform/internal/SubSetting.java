// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.internal;

/**
 * A class that all complex settings in the Settings class should extend
 *
 * @deprecated will be replaced by the {@link com.swirlds.config.api.Configuration} API in near future. If you need
 * 		to use this class please try to do as less static access as possible.
 */
@Deprecated(forRemoval = true)
public abstract class SubSetting {}
