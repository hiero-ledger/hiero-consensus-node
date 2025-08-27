// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.node;

import java.security.KeyPair;
import java.security.cert.X509Certificate;

/**
 * An instantiation of this class holds all the keys and CSPRNG state for one Platform object. No other class should
 * store any secret or private key/seed information.
 */
public record KeysAndCerts(KeyPair sigKeyPair, KeyPair agrKeyPair, X509Certificate sigCert, X509Certificate agrCert) {}
