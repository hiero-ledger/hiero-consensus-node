/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.gossip.modular;

/**
 * Minimal interface for controlling main sync gossip intake. Used by reconnect protocol to stop gossiping while
 * resynchronization of all events are done and to resume it after everything is done.
 */
public interface GossipController {

    /**
     * Stop gossiping until {@link #resume()} is called. If called when already paused then this has no effect.
     */
    void pause();

    /**
     * Resume gossiping. Undoes the effect of {@link #pause()}. Should be called exactly once after each call to
     * {@link #pause()}.
     */
    void resume();
}
