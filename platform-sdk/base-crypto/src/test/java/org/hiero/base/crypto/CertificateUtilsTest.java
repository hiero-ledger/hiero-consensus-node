// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.cryptography.security.der.model.DN;
import org.junit.jupiter.api.Test;

public class CertificateUtilsTest {

    @Test
    public void testDistinguishedNamePattern() {
        // Verify that punctuation characters are accepted (except for '=').
        final String name = "test 321 !\"#$%&'()*+,-./:;<>?@[\\]^_`{|}~";
        final DN dn = CertificateUtils.buildDN("CN=" + name);
        assertEquals(name, dn.rdnList().getFirst().avaList().getFirst().value().str());
    }
}
