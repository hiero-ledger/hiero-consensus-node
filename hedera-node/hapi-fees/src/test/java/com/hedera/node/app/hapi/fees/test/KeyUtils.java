// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.test;

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.ThresholdKey;

public class KeyUtils {
    public static Key A_CONTRACT_KEY =
            Key.newBuilder().setContractID(IdUtils.asContract("1.2.3")).build();
    public static Key A_THRESHOLD_KEY = Key.newBuilder()
            .setThresholdKey(ThresholdKey.newBuilder()
                    .setThreshold(2)
                    .setKeys(KeyList.newBuilder()
                            .addKeys(Key.newBuilder()
                                    .setEd25519(ByteString.copyFrom("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes())))
                            .addKeys(Key.newBuilder()
                                    .setEd25519(ByteString.copyFrom("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".getBytes())))
                            .addKeys(Key.newBuilder()
                                    .setEd25519(ByteString.copyFrom("cccccccccccccccccccccccccccccccc".getBytes())))))
            .build();
    public static Key A_KEY_LIST = Key.newBuilder()
            .setKeyList(KeyList.newBuilder()
                    .addKeys(Key.newBuilder()
                            .setEd25519(ByteString.copyFrom("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes())))
                    .addKeys(Key.newBuilder()
                            .setEd25519(ByteString.copyFrom("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".getBytes())))
                    .addKeys(Key.newBuilder()
                            .setEd25519(ByteString.copyFrom("cccccccccccccccccccccccccccccccc".getBytes()))))
            .build();
    public static Key A_COMPLEX_KEY = Key.newBuilder()
            .setThresholdKey(ThresholdKey.newBuilder()
                    .setThreshold(2)
                    .setKeys(KeyList.newBuilder()
                            .addKeys(Key.newBuilder()
                                    .setEd25519(ByteString.copyFrom("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes())))
                            .addKeys(Key.newBuilder()
                                    .setEd25519(ByteString.copyFrom("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".getBytes())))
                            .addKeys(A_THRESHOLD_KEY)))
            .build();
    public static Key B_COMPLEX_KEY = Key.newBuilder()
            .setThresholdKey(ThresholdKey.newBuilder()
                    .setThreshold(2)
                    .setKeys(KeyList.newBuilder()
                            .addKeys(Key.newBuilder()
                                    .setEd25519(ByteString.copyFrom("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes())))
                            .addKeys(Key.newBuilder()
                                    .setEd25519(ByteString.copyFrom("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".getBytes())))
                            .addKeys(A_COMPLEX_KEY)))
            .build();
    public static Key C_COMPLEX_KEY = Key.newBuilder()
            .setThresholdKey(ThresholdKey.newBuilder()
                    .setThreshold(2)
                    .setKeys(KeyList.newBuilder()
                            .addKeys(Key.newBuilder()
                                    .setEd25519(ByteString.copyFrom("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes())))
                            .addKeys(Key.newBuilder()
                                    .setEd25519(ByteString.copyFrom("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".getBytes())))
                            .addKeys(B_COMPLEX_KEY)))
            .build();
}
