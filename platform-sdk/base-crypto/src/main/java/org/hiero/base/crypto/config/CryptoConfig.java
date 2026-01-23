// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration of the crypto system.
 *
 * @param keystorePassword       the password used to protect the PKCS12 key stores containing the nodes RSA keys. The
 *                               password used to protect the PKCS12 key stores containing the node RSA public/private
 *                               key pairs. There is intentionally no usable default; components that require this
 *                               password will fail fast if it is not configured.
 */
@ConfigData("crypto")
public record CryptoConfig(
        @ConfigProperty(defaultValue = "password") String keystorePassword) {}
