// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.contract.impl.utils.RedirectBytecodeUtils;
import java.util.function.Function;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class RedirectBytecodeUtilsTest {

    private void testProxyBytecodeFor(Function<Address, Bytes> function, String signature) {
        Bytes res = function.apply(Address.ZERO);
        assertThat(res).isNotNull();
        assertThat(res.toFastHex(false))
                .startsWith(RedirectBytecodeUtils.PROXY_PRE_BYTES)
                .contains(RedirectBytecodeUtils.PROXY_MID_BYTES)
                .contains(Address.ZERO.toFastHex(false))
                .contains(Bytes.wrap(MiscCryptoUtils.keccak256DigestOf(signature.getBytes()))
                        .toFastHex(false)
                        .substring(0, 8));
    }

    @Test
    void tokenProxyBytecodeFor() {
        testProxyBytecodeFor(RedirectBytecodeUtils::tokenProxyBytecodeFor, "redirectForToken(address,bytes)");
    }

    @Test
    void accountProxyBytecodeFor() {
        testProxyBytecodeFor(RedirectBytecodeUtils::accountProxyBytecodeFor, "redirectForAccount(address,bytes)");
    }

    @Test
    void scheduleProxyBytecodeFor() {
        testProxyBytecodeFor(RedirectBytecodeUtils::scheduleProxyBytecodeFor, "redirectForScheduleTxn(address,bytes)");
    }

    private void testProxyBytecodePbj(
            Function<Address, com.hedera.pbj.runtime.io.buffer.Bytes> function, String signature) {
        com.hedera.pbj.runtime.io.buffer.Bytes res = function.apply(Address.ZERO);
        assertThat(res).isNotNull();
        assertThat(res.toHex())
                .startsWith(RedirectBytecodeUtils.PROXY_PRE_BYTES)
                .contains(RedirectBytecodeUtils.PROXY_MID_BYTES)
                .contains(Address.ZERO.toFastHex(false))
                .contains(Bytes.wrap(MiscCryptoUtils.keccak256DigestOf(signature.getBytes()))
                        .toFastHex(false)
                        .substring(0, 8));
    }

    @Test
    void tokenProxyBytecodePjb() {
        testProxyBytecodePbj(RedirectBytecodeUtils::tokenProxyBytecodePjb, "redirectForToken(address,bytes)");
    }

    @Test
    void accountProxyBytecodePjb() {
        testProxyBytecodePbj(RedirectBytecodeUtils::accountProxyBytecodePjb, "redirectForAccount(address,bytes)");
    }

    @Test
    void scheduleProxyBytecodePjb() {
        testProxyBytecodePbj(RedirectBytecodeUtils::scheduleProxyBytecodePjb, "redirectForScheduleTxn(address,bytes)");
    }
}
