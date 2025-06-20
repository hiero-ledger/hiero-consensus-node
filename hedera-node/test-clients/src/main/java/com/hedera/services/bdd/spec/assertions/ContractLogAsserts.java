// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static java.util.Arrays.copyOfRange;

import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ContractLoginfo;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;

public class ContractLogAsserts extends BaseErroringAssertsProvider<ContractLoginfo> {
    public static ContractLogAsserts logWith() {
        return new ContractLogAsserts();
    }

    public ContractLogAsserts utf8data(String data) {
        registerProvider((spec, o) -> {
            String actual = new String(dataFrom(o), StandardCharsets.UTF_8);
            Assertions.assertEquals(data, actual, "Wrong UTF-8 data!");
        });
        return this;
    }

    public ContractLogAsserts accountAtBytes(String account, int start) {
        registerProvider((spec, o) -> {
            byte[] data = dataFrom(o);
            AccountID expected = spec.registry().getAccountID(account);
            AccountID actual = accountFromBytes(data, start);
            Assertions.assertEquals(expected, actual, "Bad account in log data, starting at byte " + start);
        });
        return this;
    }

    public ContractLogAsserts ecdsaAliasStartingAt(String aliasKey, int start) {
        registerProvider((spec, o) -> {
            byte[] data = dataFrom(o);
            ByteString alias = spec.registry().keyAliasIdFor(spec, aliasKey).getAlias();
            byte[] expected = recoverAddressFromPubKey(alias.substring(2).toByteArray());
            byte[] actual = Arrays.copyOfRange(data, start, start + 20);
            Assertions.assertArrayEquals(expected, actual, "Bad alias in log data, starting at byte " + start);
        });
        return this;
    }

    public ContractLogAsserts longAtBytes(long expected, int start) {
        registerProvider((spec, o) -> {
            byte[] data = dataFrom(o);
            long actual = Longs.fromByteArray(copyOfRange(data, start, start + 8));
            Assertions.assertEquals(expected, actual, "Bad long value in log data, starting at byte " + start);
        });
        return this;
    }

    public ContractLogAsserts longValue(long expected) {
        registerProvider((spec, o) -> {
            byte[] data = dataFrom(o);
            long actual = Longs.fromByteArray(copyOfRange(data, data.length - 8, data.length));
            Assertions.assertEquals(expected, actual, "Bad long value in log data: " + actual);
        });
        return this;
    }

    public ContractLogAsserts booleanValue(boolean expected) {
        registerProvider((spec, o) -> {
            byte[] data = dataFrom(o);
            boolean actual = data[data.length - 1] != 0;
            Assertions.assertEquals(expected, actual, "Bad boolean value in log data: " + actual);
        });
        return this;
    }

    public ContractLogAsserts noTopics() {
        registerProvider((spec, o) -> Assertions.assertTrue(
                ((ContractLoginfo) o).getTopicList().isEmpty(),
                "Bad topics value in Topics array. " + "No topics expected"));
        return this;
    }

    public ContractLogAsserts contract(final String contract) {
        registerIdLookupAssert(contract, ContractLoginfo::getContractID, ContractID.class, "Bad contract");
        return this;
    }

    public ContractLogAsserts noData() {
        registerProvider((spec, o) -> Assertions.assertTrue(
                ((ContractLoginfo) o).getData().isEmpty(), "Bad data value. " + "No data expected"));
        return this;
    }

    public ContractLogAsserts withTopicsInOrder(List<ByteString> expectedTopics) {
        registerProvider((spec, o) -> {
            List<ByteString> actualTopics = topicsFrom(o);
            Assertions.assertEquals(
                    expectedTopics,
                    actualTopics,
                    "Topics mismatch! Expected:" + expectedTopics.toString() + ", actual: " + actualTopics);
        });
        return this;
    }

    static AccountID accountFromBytes(byte[] data, int start) {
        long shard = Longs.fromByteArray(copyOfRange(data, start, start + 4));
        long realm = Longs.fromByteArray(copyOfRange(data, start + 4, start + 12));
        long seq = Longs.fromByteArray(copyOfRange(data, start + 12, start + 20));
        return AccountID.newBuilder()
                .setAccountNum(seq)
                .setRealmNum(realm)
                .setShardNum(shard)
                .build();
    }

    static byte[] dataFrom(Object o) {
        ContractLoginfo entry = (ContractLoginfo) o;
        return entry.getData().toByteArray();
    }

    static List<ByteString> topicsFrom(Object o) {
        return ((ContractLoginfo) o).getTopicList();
    }
}
