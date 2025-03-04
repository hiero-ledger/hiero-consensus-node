// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.test;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.pbj.runtime.io.buffer.Bytes;

public class KeyUtils {
    public static Key A_CONTRACT_KEY =
            Key.newBuilder().contractID(IdUtils.asContract("1.2.3")).build();
    public static Key A_THRESHOLD_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes()))
                                            .build(),
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".getBytes()))
                                            .build(),
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap("cccccccccccccccccccccccccccccccc".getBytes()))
                                            .build())))
            .build();
    public static Key A_KEY_LIST = Key.newBuilder()
            .keyList(KeyList.newBuilder()
                    .keys(
                            Key.newBuilder()
                                    .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes()))
                                    .build(),
                            Key.newBuilder()
                                    .ed25519(Bytes.wrap("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".getBytes()))
                                    .build(),
                            Key.newBuilder()
                                    .ed25519(Bytes.wrap("cccccccccccccccccccccccccccccccc".getBytes()))
                                    .build()))
            .build();
    public static Key A_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes()))
                                            .build(),
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".getBytes()))
                                            .build(),
                                    A_THRESHOLD_KEY)))
            .build();
    public static Key B_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes()))
                                            .build(),
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".getBytes()))
                                            .build(),
                                    A_COMPLEX_KEY)))
            .build();
    public static Key C_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes()))
                                            .build(),
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".getBytes()))
                                            .build(),
                                    B_COMPLEX_KEY)))
            .build();
}
