// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Thread related config
 *
 * @param threadPrioritySync priority for threads that sync (in SyncCaller, SyncListener, SyncServer)
 */
@ConfigData("thread")
public record ThreadConfig(@ConfigProperty(defaultValue = "5") int threadPrioritySync) {}
