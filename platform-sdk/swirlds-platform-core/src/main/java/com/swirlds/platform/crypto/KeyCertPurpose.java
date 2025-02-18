/*
 * Copyright (C) 2016-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.crypto;

import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.roster.RosterUtils;

/**
 * Denotes which of the three purposes a key or certificate serves
 */
public enum KeyCertPurpose {
    SIGNING("s"),
    AGREEMENT("a");

    /** the prefix used for certificate names */
    private final String prefix;

    KeyCertPurpose(final String prefix) {
        this.prefix = prefix;
    }

    /**
     * @param nodeId the node identifier
     * @return the name of the key or certificate used in a KeyStore for this member and key type
     */
    public String storeName(final NodeId nodeId) {
        return prefix + "-" + RosterUtils.formatNodeName(nodeId);
    }

    /**
     * Returns the prefix associated with a key or certificate purpose.
     *
     * @return the prefix.
     */
    public String prefix() {
        return prefix;
    }
}
