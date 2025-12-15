// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import java.security.cert.X509Certificate;
import java.util.Map;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

public record AllKeysAndCerts(Map<NodeId, KeysAndCerts> localKeysAndCerts, Map<NodeId, X509Certificate> publicCerts) {}
