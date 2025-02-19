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

package com.hedera.node.app.hapi.utils.keys;

import static com.hedera.node.app.hapi.utils.keys.KeyUtils.BC_PROVIDER;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.relocatedIfNotPresentInWorkingDir;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.interfaces.ECPrivateKey;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;

/**
 * Useful methods for interacting with SECP256K1 ECDSA keys.
 */
public class Secp256k1Utils {

    public static byte[] extractEcdsaPublicKey(final ECPrivateKey key) {
        final ECPoint pointQ =
                ECNamedCurveTable.getParameterSpec("secp256k1").getG().multiply(key.getS());
        return pointQ.getEncoded(true);
    }

    public static ECPrivateKey readECKeyFrom(final File pem, final String passphrase) {
        final var relocatedPem = relocatedIfNotPresentInWorkingDir(pem);
        try (final var in = new FileInputStream(relocatedPem)) {
            final var decryptProvider = new JceOpenSSLPKCS8DecryptorProviderBuilder()
                    .setProvider(BC_PROVIDER)
                    .build(passphrase.toCharArray());
            final var converter = new JcaPEMKeyConverter().setProvider(BC_PROVIDER);
            try (final var parser = new PEMParser(new InputStreamReader(in))) {
                final var encryptedPrivateKeyInfo = (PKCS8EncryptedPrivateKeyInfo) parser.readObject();
                final var info = encryptedPrivateKeyInfo.decryptPrivateKeyInfo(decryptProvider);
                return (ECPrivateKey) converter.getPrivateKey(info);
            }
        } catch (final IOException | OperatorCreationException | PKCSException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
