// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.contract;

import static com.hedera.services.bdd.spec.HapiPropertySourceStaticInitializer.REALM;
import static com.hedera.services.bdd.spec.HapiPropertySourceStaticInitializer.SHARD;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Function;
import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hederahashgraph.api.proto.java.Key;
import org.apache.tuweni.bytes.Bytes;

public class HapiParserUtil {

    private HapiParserUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static byte[] encodeParametersForCall(final Object[] params, final String abi) {
        return encodeParameters(params, abi);
    }

    public static byte[] encodeParametersForConstructor(final Object[] params, final String abi) {
        return stripSelector(encodeParameters(params, abi));
    }

    private static byte[] encodeParameters(final Object[] params, final String abi) {
        byte[] callData = new byte[] {};
        if (!abi.isEmpty() && !abi.contains("<empty>")) {
            final var abiFunction = Function.fromJson(abi);
            callData = abiFunction.encodeCallWithArgs(params).array();
        }

        return callData;
    }

    public static Address asHeadlongAddress(final String address) {
        final var addressBytes = Bytes.fromHexString(address.startsWith("0x") ? address : "0x" + address);
        final var addressAsInteger = addressBytes.toUnsignedBigInteger();
        return Address.wrap(Address.toChecksumAddress(addressAsInteger));
    }

    public static Address asHeadlongAddress(final byte[] address) {
        final var addressBytes = Bytes.wrap(address);
        final var addressAsInteger = addressBytes.toUnsignedBigInteger();
        return Address.wrap(Address.toChecksumAddress(addressAsInteger));
    }

    public static Address[] asHeadlongAddressArray(final byte[]... addresses) {
        Address[] headlongAddresses = new Address[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            headlongAddresses[i] = asHeadlongAddress(addresses[i]);
        }
        return headlongAddresses;
    }

    public static Address evmAddressFromSecp256k1Key(final Key key) {
        if (key.hasECDSASecp256K1()) {
            return asHeadlongAddress(EthSigsUtils.recoverAddressFromPubKey(
                    key.getECDSASecp256K1().toByteArray()));
        } else {
            throw new IllegalStateException("Cannot observe address for non-ECDSA key!");
        }
    }

    public static byte[] stripSelector(final byte[] bytesToExpand) {
        byte[] expandedArray = new byte[bytesToExpand.length - 4];

        System.arraycopy(bytesToExpand, 4, expandedArray, 0, bytesToExpand.length - 4);
        return expandedArray;
    }

    // Generate an address with the shard, realm and passed number. All the values are padded till the required length.
    public static String toAddressStringWithShardAndRealm(String number) {
        String shardHex = Integer.toHexString(SHARD);
        shardHex = "000000".substring(0, 6 - shardHex.length()) + shardHex;

        String realmHex = Long.toHexString(REALM);
        realmHex = "0000000000000000".substring(0, 16 - realmHex.length()) + realmHex;

        number = "0000000000000000".substring(0, 16 - number.length()) + number;

        return "0x00" + shardHex + realmHex + number;
    }
}
