// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.ethereum;

import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.DETERMINISTIC_DEPLOYER_TRANSACTION;
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.WEIBARS_IN_A_TINYBAR;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.rlp.RLPDecoder;
import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.esaulpaugh.headlong.rlp.RLPList;
import com.esaulpaugh.headlong.util.Integers;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.util.BigIntegers;
import org.bouncycastle.util.encoders.Hex;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EthTxDataTest {
    private static final BigInteger N = SECNamedCurves.getByName("secp256k1").getN();

    static final String SIGNATURE_ADDRESS = "a94f5374fce5edbc8e2a8697c15331677e6ebf0b";
    static final String SIGNATURE_PUBKEY = "033a514176466fa815ed481ffad09110a2d344f6c9b78c1d14afc351c3a51be33d";
    static final String RAW_TX_TYPE_0 =
            "f864012f83018000947e3a9eaf9bcc39e2ffa38eb30bf7a93feacbc18180827653820277a0f9fbff985d374be4a55f296915002eec11ac96f1ce2df183adf992baa9390b2fa00c1e867cc960d9c74ec2e6a662b7908ec4c8cc9f3091e886bcefbeb2290fb792";
    static final String RAW_TX_TYPE_0_WITH_CHAIN_ID_11155111 =
            "f86b048503ff9aca0782520f94e64fac7f3df5ab44333ad3d3eb3fb68be43f2e8c830fffff808401546d71a026cf0758fda122862a4de71a82a3210ef7c172ee13eae42997f5d32b747ec78ca03587c5c2eee373b1e45693544edcde8dde883d2be3e211b3f0f3c840d6389c8a";
    static final String RAW_TX_TYPE_0_TRIMMED_LAST_BYTES =
            "f864012f83018000947e3a9eaf9bcc39e2ffa38eb30bf7a93feacbc18180827653820277a0f9fbff985d374be4a55f296915002eec11ac96f1ce2df183adf992baa9390b2fa00c1e867cc960d9c74ec2e6a662b7908ec4c8cc9f3091e886bcefbeb2290000";
    // {
    //  type: 1, to: '0x000000000000000000000000000000000000052D', data: '0x123456', nonce: 5644, gasLimit: '3000000',
    //  gasPrice: '710000000000', maxPriorityFeePerGas: null, maxFeePerGas: null, value: '10000000000', chainId: '298',
    //  r: '0xabb9e9c510716df2988cf626734ee50dcd9f41d30d638220712b5fe33fe4c816',
    //  s: '0x249a72e1479b61e00d4f20308577bb63167d71b26138ee5229ca1cb3c49a2e53',
    //  v: 28, accessList: []
    // }
    static final String RAW_TX_TYPE_1 = "01" // type
                    + "f873" // total length
                    + "82012a" // chain id => 82 - 80 = 2 (hex) = 2 (dec) bytes length
                    + "82160c" // nonce  => same length
                    + "85a54f4c3c00" // gas price => 5 bytes
                    + "832dc6c0" // gas limit => 3 bytes
                    + "94000000000000000000000000000000000000052d" // to => 94 - 80 = 14 (hex) = 20 (dec) bytes
                    + "8502540be400" // value => 5 bytes
                    + "83123456" // calldata => 3 bytes
                    + "c0" // empty access list => by the RLP definitions, an empty list is encoded with c0
                    + "01" // v
                    + "a0abb9e9c510716df2988cf626734ee50dcd9f41d30d638220712b5fe33fe4c816" // r => a0 - 80 = 80 (hex) =
                    // 128 (dec) bytes
                    + "a0249a72e1479b61e00d4f20308577bb63167d71b26138ee5229ca1cb3c49a2e53" // same
            ;
    static final String RAW_TX_TYPE_2 =
            "02f87082012a022f2f83018000947e3a9eaf9bcc39e2ffa38eb30bf7a93feacbc181880de0b6b3a764000083123456c001a0df48f2efd10421811de2bfb125ab75b2d3c44139c4642837fb1fccce911fd479a01aaf7ae92bee896651dfc9d99ae422a296bf5d9f1ca49b2d96d82b79eb112d66";

    // https://etherscan.io/tx/0x2ea19986a6866b6efd2ac292fa8132b0bbf1fcc478560525ce43d6c300323652
    static final String RAW_TX_TYPE_3 =
            "03f9049f01830837e4843b9aca0085213f9eed7283036a2b941c479675ad559dc151f6ec7ed3fbf8cee79582b680b8a43e5aa082000000000000000000000000000000000000000000000000000000000008fff2000000000000000000000000000000000000000000000000000000000016a443000000000000000000000000e64a54e2533fd126c2e452c5fab544d80e2e4eb5000000000000000000000000000000000000000000000000000000000aafdc87000000000000000000000000000000000000000000000000000000000aafde27f902c0f8dd941c479675ad559dc151f6ec7ed3fbf8cee79582b6f8c6a00000000000000000000000000000000000000000000000000000000000000000a00000000000000000000000000000000000000000000000000000000000000001a0000000000000000000000000000000000000000000000000000000000000000aa0b53127684a568b3173ae13b9f8a6016e243e63b6e8ee1178d6a717850b5d6103a0360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbca0a10aa54071443520884ed767b0684edf43acec528b7da83ab38ce60126562660f90141948315177ab297ba92a06054ce80a67ed4dbd7ed3af90129a00000000000000000000000000000000000000000000000000000000000000006a00000000000000000000000000000000000000000000000000000000000000007a00000000000000000000000000000000000000000000000000000000000000009a0000000000000000000000000000000000000000000000000000000000000000aa0b53127684a568b3173ae13b9f8a6016e243e63b6e8ee1178d6a717850b5d6103a0360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbca0a66cc928b5edb82af9bd49922954155ab7b0942694bea4ce44661d9a873fc679a0a66cc928b5edb82af9bd49922954155ab7b0942694bea4ce44661d9a873fc67aa0f652222313e28459528d920b65115c16c04f3efc82aaedc97be59f3f3792b181f89b94e64a54e2533fd126c2e452c5fab544d80e2e4eb5f884a00000000000000000000000000000000000000000000000000000000000000004a00000000000000000000000000000000000000000000000000000000000000005a0e85fd79f89ff278fc57d40aecb7947873df9f0beac531c8f71a98f630e1eab62a07686888b19bb7b75e46bb1aa328b65150743f4899443d722f0adf8e252ccda410af8c6a0014527d555d949b3afcfa246e16eb0e0aef9e9da60b7a0266f1da43b3fd8e8cfa0016d80efa350ab1fc156b505ab619bee3f6245b8f7d4d60bf11c9d8b0105b02fa00176b14180ebfaa132142ff163eb2aaf2985af7da011d195e39fe8b0faf1e960a00134da09304a6a66b691bc48d351b976203cd419778d142f19e68e904f07a5aea00181b4581a9fc316eadc58e4d6d362e316e259643913339a3e46b7c9d742ac30a00112fa6c9dfaceaff1868ef19d01c4a1da99e6e02162fe7dacf94ec441da697701a04dfd139f20fdefc834fbdce2e120ca8ed1a4688d8843df8fc2de1df8c6d0a0f3a06ec282ea1c2c55e467425c380c17f0f8ef664d8e2de8a39b18d6c83b8d6a9afa";
    static final String EIP_155_DEMO_ADDRESS = "9d8a62f656a8d1615c1294fd71e9cfb3e4855a4f";
    static final String EIP_155_DEMO_PUBKEY = "024bc2a31265153f07e70e0bab08724e6b85e217f8cd628ceb62974247bb493382";
    static final String EIP155_DEMO =
            "f86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a067cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83";
    // v = 27
    static final String EIP155_UNPROTECTED =
            "f8a58085174876e800830186a08080b853604580600e600039806000f350fe7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe03601600081602082378035828234f58015156039578182fd5b8082525050506014600cf31ba02222222222222222222222222222222222222222222222222222222222222222a02222222222222222222222222222222222222222222222222222222222222222";

    private static final long TINYBAR_GAS_PRICE = 100L;

    private static final int DETERMINISTIC_DEPLOYER_GAS_PRICE_MULTIPLIER = 100;

    @Test
    void detectsMissingCallData() {
        final var subject = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_0));
        assertTrue(subject.hasCallData());
        final var subjectWithEmptyData = subject.replaceCallData(new byte[0]);
        assertFalse(subjectWithEmptyData.hasCallData());
        final var subjectWithNullData = subject.replaceCallData(null);
        assertFalse(subjectWithNullData.hasCallData());
    }

    @Test
    void detectsMissingToAddress() {
        final var subject = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_0));
        assertTrue(subject.hasToAddress());
        final var subjectWithEmptyTo = subject.replaceTo(new byte[0]);
        assertFalse(subjectWithEmptyTo.hasToAddress());
        final var subjectWithNullTo = subject.replaceTo(null);
        assertFalse(subjectWithNullTo.hasToAddress());
    }

    @Test
    void effectiveValueIsNominalWhenReasonable() {
        final var subject = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_0));
        final var nominal = subject.value().divide(WEIBARS_IN_A_TINYBAR).longValueExact();
        assertEquals(nominal, subject.effectiveTinybarValue());
    }

    @Test
    void effectiveOfferedGasPriceIsNominalWhenReasonable() {
        final var subject = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_0));
        final var nominal = subject.getMaxGasAsBigInteger(TINYBAR_GAS_PRICE)
                .divide(WEIBARS_IN_A_TINYBAR)
                .longValueExact();
        assertEquals(nominal, subject.effectiveOfferedGasPriceInTinybars(TINYBAR_GAS_PRICE));
    }

    @Test
    void effectiveOfferedGasPriceAvoidsOverflow() {
        final var subject = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_0))
                .replaceValue(
                        BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).multiply(WEIBARS_IN_A_TINYBAR));
        final var expected = Long.MAX_VALUE;
        assertEquals(expected, subject.effectiveTinybarValue());
    }

    @Test
    void extractFrontierSignature() {
        final var frontierTx = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_0));
        assertNotNull(frontierTx);
        assertEquals(RAW_TX_TYPE_0, Hex.toHexString(frontierTx.rawTx()));
        assertEquals(EthTxData.EthTransactionType.LEGACY_ETHEREUM, frontierTx.type());
        assertEquals("012a", Hex.toHexString(frontierTx.chainId()));
        assertTrue(frontierTx.matchesChainId(CommonUtils.unhex("012a")));
        assertFalse(frontierTx.matchesChainId(CommonUtils.unhex("a210")));
        assertEquals(1, frontierTx.nonce());
        assertEquals("2f", Hex.toHexString(frontierTx.gasPrice()));
        assertNull(frontierTx.maxPriorityGas());
        assertNull(frontierTx.maxGas());
        assertEquals(98_304L, frontierTx.gasLimit());
        assertEquals("7e3a9eaf9bcc39e2ffa38eb30bf7a93feacbc181", Hex.toHexString(frontierTx.to()));
        assertEquals(BigInteger.ZERO, frontierTx.value());
        assertEquals("7653", Hex.toHexString(frontierTx.callData()));
        assertNull(frontierTx.accessList());
        assertEquals(0, frontierTx.recId());
        assertEquals("0277", Hex.toHexString(frontierTx.v()));
        assertEquals(
                "f9fbff985d374be4a55f296915002eec11ac96f1ce2df183adf992baa9390b2f", Hex.toHexString(frontierTx.r()));
        assertEquals(
                "0c1e867cc960d9c74ec2e6a662b7908ec4c8cc9f3091e886bcefbeb2290fb792", Hex.toHexString(frontierTx.s()));
        assertEquals(
                "9ffbd69c44cf643ed8d1e756b505e545e3b5dd3a6b5ef9da1d8eca6679706594",
                Hex.toHexString(frontierTx.getEthereumHash()));

        final var frontierSigs = EthTxSigs.extractSignatures(frontierTx);
        assertNotNull(frontierSigs);

        assertEquals(SIGNATURE_ADDRESS, Hex.toHexString(frontierSigs.address()));
        assertEquals(SIGNATURE_PUBKEY, Hex.toHexString(frontierSigs.publicKey()));
    }

    @Test
    void extractEIP155Signature() {
        final var eip155Tx = EthTxData.populateEthTxData(Hex.decode(EIP155_DEMO));
        assertNotNull(eip155Tx);
        assertEquals(EIP155_DEMO, Hex.toHexString(eip155Tx.rawTx()));
        assertEquals(EthTxData.EthTransactionType.LEGACY_ETHEREUM, eip155Tx.type());
        assertEquals("01", Hex.toHexString(eip155Tx.chainId()));
        assertEquals(9, eip155Tx.nonce());
        assertEquals("04a817c800", Hex.toHexString(eip155Tx.gasPrice()));
        assertNull(eip155Tx.maxPriorityGas());
        assertNull(eip155Tx.maxGas());
        assertEquals(21_000L, eip155Tx.gasLimit());
        assertEquals("3535353535353535353535353535353535353535", Hex.toHexString(eip155Tx.to()));
        assertEquals(new BigInteger("0de0b6b3a7640000", 16), eip155Tx.value());
        assertEquals(0, eip155Tx.callData().length);
        assertNull(eip155Tx.accessList());
        assertEquals(0, eip155Tx.recId());
        assertEquals("25", Hex.toHexString(eip155Tx.v()));
        assertEquals("28ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276", Hex.toHexString(eip155Tx.r()));
        assertEquals("67cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83", Hex.toHexString(eip155Tx.s()));

        final var eip155Sigs = EthTxSigs.extractSignatures(eip155Tx);
        assertNotNull(eip155Sigs);
        assertEquals(EIP_155_DEMO_ADDRESS, Hex.toHexString(eip155Sigs.address()));
        assertEquals(EIP_155_DEMO_PUBKEY, Hex.toHexString(eip155Sigs.publicKey()));
    }

    @Test
    void extractEIP155SignatureWithNegativeRecoveryIdThrowsIAE() {
        final var invalidRecoveryEip155Tx = requireNonNull(EthTxData.populateEthTxData(Hex.decode(EIP155_DEMO)))
                .replaceRecId(-1);

        final var sigs = EthTxSigs.extractSignatures(invalidRecoveryEip155Tx);

        // We changed the bytes signed in the test vector
        assertNotEquals(EIP_155_DEMO_ADDRESS, Hex.toHexString(sigs.address()));
        assertNotEquals(EIP_155_DEMO_PUBKEY, Hex.toHexString(sigs.publicKey()));
    }

    @Test
    void extractSignatureThrowsWithInvalidR() {
        final var rCurvePointAtNEip155Tx = requireNonNull(EthTxData.populateEthTxData(Hex.decode(EIP155_DEMO)))
                .replaceR(N.toByteArray());
        assertThrows(IllegalArgumentException.class, () -> EthTxSigs.extractSignatures(rCurvePointAtNEip155Tx));
        final var rCurvePointAt0Eip155Tx = requireNonNull(EthTxData.populateEthTxData(Hex.decode(EIP155_DEMO)))
                .replaceR(BigInteger.ZERO.toByteArray());
        assertThrows(IllegalArgumentException.class, () -> EthTxSigs.extractSignatures(rCurvePointAt0Eip155Tx));
    }

    @Test
    void extractSignatureThrowsWithInvalidS() {
        final var sCurvePointAtNEip155Tx = requireNonNull(EthTxData.populateEthTxData(Hex.decode(EIP155_DEMO)))
                .replaceS(N.toByteArray());
        assertThrows(IllegalArgumentException.class, () -> EthTxSigs.extractSignatures(sCurvePointAtNEip155Tx));
        final var sCurvePointAt0Eip155Tx = requireNonNull(EthTxData.populateEthTxData(Hex.decode(EIP155_DEMO)))
                .replaceS(BigInteger.ZERO.toByteArray());
        assertThrows(IllegalArgumentException.class, () -> EthTxSigs.extractSignatures(sCurvePointAt0Eip155Tx));
    }

    @Test
    void extractBerlinSignature() {
        final var berlinTx = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_1));
        assertNotNull(berlinTx);
        assertEquals(RAW_TX_TYPE_1, Hex.toHexString(berlinTx.rawTx()));
        assertEquals(EthTxData.EthTransactionType.EIP2930, berlinTx.type());
        assertEquals("012a", Hex.toHexString(berlinTx.chainId()));
        assertEquals(5644, berlinTx.nonce());
        assertEquals("a54f4c3c00", Hex.toHexString(berlinTx.gasPrice()));
        assertNull(berlinTx.maxPriorityGas());
        assertNull(berlinTx.maxGas());
        assertEquals(3_000_000L, berlinTx.gasLimit());
        assertEquals("000000000000000000000000000000000000052d", Hex.toHexString(berlinTx.to()));
        assertEquals(new BigInteger("2540be400", 16), berlinTx.value());
        assertEquals("123456", Hex.toHexString(berlinTx.callData()));
        assertEquals("", Hex.toHexString(berlinTx.accessList()));
        assertEquals(1, berlinTx.recId());
        assertNull(berlinTx.v());
        assertEquals("abb9e9c510716df2988cf626734ee50dcd9f41d30d638220712b5fe33fe4c816", Hex.toHexString(berlinTx.r()));
        assertEquals("249a72e1479b61e00d4f20308577bb63167d71b26138ee5229ca1cb3c49a2e53", Hex.toHexString(berlinTx.s()));

        final var berlinSigs = EthTxSigs.extractSignatures(berlinTx);
        assertNotNull(berlinSigs);
        assertEquals(SIGNATURE_ADDRESS, Hex.toHexString(berlinSigs.address()));
        assertEquals(SIGNATURE_PUBKEY, Hex.toHexString(berlinSigs.publicKey()));
    }

    @Test
    void extractLondonSignature() {
        final var londonTx = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_2));
        assertNotNull(londonTx);
        assertEquals(RAW_TX_TYPE_2, Hex.toHexString(londonTx.rawTx()));
        assertEquals(EthTxData.EthTransactionType.EIP1559, londonTx.type());
        assertEquals("012a", Hex.toHexString(londonTx.chainId()));
        assertEquals(2, londonTx.nonce());
        assertNull(londonTx.gasPrice());
        assertEquals("2f", Hex.toHexString(londonTx.maxPriorityGas()));
        assertEquals("2f", Hex.toHexString(londonTx.maxGas()));
        assertEquals(98_304L, londonTx.gasLimit());
        assertEquals("7e3a9eaf9bcc39e2ffa38eb30bf7a93feacbc181", Hex.toHexString(londonTx.to()));
        assertEquals(new BigInteger("0de0b6b3a7640000", 16), londonTx.value());
        assertEquals("123456", Hex.toHexString(londonTx.callData()));
        assertEquals("", Hex.toHexString(londonTx.accessList()));
        assertEquals(1, londonTx.recId());
        assertNull(londonTx.v());
        assertEquals("df48f2efd10421811de2bfb125ab75b2d3c44139c4642837fb1fccce911fd479", Hex.toHexString(londonTx.r()));
        assertEquals("1aaf7ae92bee896651dfc9d99ae422a296bf5d9f1ca49b2d96d82b79eb112d66", Hex.toHexString(londonTx.s()));

        final var londonSigs = EthTxSigs.extractSignatures(londonTx);
        assertNotNull(londonSigs);
        assertEquals(SIGNATURE_ADDRESS, Hex.toHexString(londonSigs.address()));
        assertEquals(SIGNATURE_PUBKEY, Hex.toHexString(londonSigs.publicKey()));
    }

    @Test
    void roundTripFrontier() {
        final var expected = Hex.decode(RAW_TX_TYPE_0);
        final var frontierTx = EthTxData.populateEthTxData(expected);

        assertNotNull(frontierTx);
        assertArrayEquals(expected, frontierTx.encodeTx());
    }

    @Test
    void roundTrip155() {
        final var expected = Hex.decode(EIP155_DEMO);
        final var tx155 = EthTxData.populateEthTxData(expected);

        assertNotNull(tx155);
        assertArrayEquals(expected, tx155.encodeTx());
    }

    @Test
    // EIP-155 adds chainId in order to prevent replay attacks. This test checks if the encoding works without the
    // chainId
    void roundTrip155UnprotectedTx() {
        final var expected = Hex.decode(EIP155_UNPROTECTED);
        final var tx155 = EthTxData.populateEthTxData(expected);

        assertNotNull(tx155);
        assertArrayEquals(expected, tx155.encodeTx());
    }

    @Test
    void roundTrip2930() {
        final var expected = Hex.decode(RAW_TX_TYPE_1);
        final var tx2930 = EthTxData.populateEthTxData(expected);

        assertNotNull(tx2930);
        assertArrayEquals(expected, tx2930.encodeTx());
    }

    @Test
    void roundTrip1559() {
        final var expected = Hex.decode(RAW_TX_TYPE_2);
        final var tx1559 = EthTxData.populateEthTxData(expected);

        assertNotNull(tx1559);
        assertArrayEquals(expected, tx1559.encodeTx());
    }

    @Test
    void whiteBoxDecodingErrors() {
        final var oneByte = new byte[] {1};
        final var sequentiallyEncodeOneByte = RLPEncoder.sequence(oneByte);
        final var size_13 = List.of(
                oneByte, oneByte, oneByte, oneByte, oneByte, oneByte, oneByte, oneByte, oneByte, oneByte, oneByte,
                oneByte, oneByte);
        final var size_1 = List.of(oneByte);

        // legacy TX with too many RLP entries
        assertNull(EthTxData.populateEthTxData(RLPEncoder.list(size_13)));
        // legacy TX with too few RLP entries
        assertNull(EthTxData.populateEthTxData(RLPEncoder.list(size_1)));
        // type 1 TX with too few RLP entries
        assertNull(EthTxData.populateEthTxData(RLPEncoder.sequence(new byte[] {1}, size_1)));
        // type 1 TX with too many RLP entries
        assertNull(EthTxData.populateEthTxData(RLPEncoder.sequence(new byte[] {1}, size_13)));
        // type 1 TX with not <List> Type RLP Item
        assertNull(EthTxData.populateEthTxData(RLPEncoder.sequence(new byte[] {1}, sequentiallyEncodeOneByte)));
        // type 2 TX with too many RLP entries
        assertNull(EthTxData.populateEthTxData(RLPEncoder.sequence(new byte[] {2}, size_13)));
        // type 2 TX with too few RLP entries
        assertNull(EthTxData.populateEthTxData(RLPEncoder.sequence(new byte[] {2}, size_1)));
        // type 2 TX with not <List> Type RLP Item
        assertNull(EthTxData.populateEthTxData(RLPEncoder.sequence(new byte[] {2}, sequentiallyEncodeOneByte)));
        // type 3 TX (blobs) are rejected (just one test case suffices)
        assertNull(EthTxData.populateEthTxData(RLPEncoder.sequence(new byte[] {3}, size_13)));
        {
            final var rawTx3 = Hex.decode(RAW_TX_TYPE_3);
            rawTx3[1] += 1; // now total length is wrong, thus invalid RLP encoding
            assertNull(EthTxData.populateEthTxData(rawTx3));
        }
        // Unsupported Transaction Type
        assertNull(EthTxData.populateEthTxData(RLPEncoder.sequence(new byte[] {127}, size_13)));
        // Trimmed End Bytes
        assertNull(EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_0_TRIMMED_LAST_BYTES)));

        // poorly wrapped typed transaction
        assertNull(EthTxData.populateEthTxData(RLPEncoder.sequence(new byte[] {2}, oneByte, oneByte)));
    }

    byte[][] normalRlpData() {
        final var oneByte = new byte[] {1};
        final byte[][] rlpArray = new byte[][] {
            oneByte, oneByte, oneByte, oneByte,
            oneByte, oneByte, oneByte, oneByte,
            oneByte, oneByte, oneByte, oneByte
        };
        return rlpArray;
    }

    @Test
    void parsingErrors() {
        final var wrongData = Hex.encode(ByteString.copyFromUtf8("wrong").toByteArray());
        final var negativeInteger = Integers.toBytes(Long.MIN_VALUE);

        // invalid nonce
        var normalData = normalRlpData();
        normalData[1] = wrongData;
        final var invalidNonceData = Arrays.asList(normalData);

        assertNull(EthTxData.populateEthTxData(RLPEncoder.sequence(new byte[] {2}, invalidNonceData)));

        // invalid gasLimit: too large
        normalData = normalRlpData();
        normalData[4] = wrongData;
        final var invalidGasLimitData = Arrays.asList(normalData);

        assertNull(EthTxData.populateEthTxData(RLPEncoder.sequence(new byte[] {2}, invalidGasLimitData)));

        // invalid gaslimit: negative
        normalData = normalRlpData();
        normalData[4] = negativeInteger;
        final var invalidGasDataNegative = Arrays.asList(normalData);

        assertNull(EthTxData.populateEthTxData(RLPEncoder.sequence(new byte[] {2}, invalidGasDataNegative)));

        // invalid recId
        normalData = normalRlpData();
        normalData[9] = wrongData;
        final var invalidRecIdData = Arrays.asList(normalData);

        assertNull(EthTxData.populateEthTxData(RLPEncoder.sequence(new byte[] {2}, invalidRecIdData)));

        // zero length data
        assertNull(EthTxData.populateEthTxData(new byte[0]));
    }

    @Test
    void whiteBoxEncodingErrors() {
        final var oneByte = new byte[] {1};

        final EthTxData ethTxDataWithAccessList = new EthTxData(
                oneByte,
                EthTxData.EthTransactionType.EIP1559,
                oneByte,
                1,
                oneByte,
                oneByte,
                oneByte,
                1,
                oneByte,
                BigInteger.ONE,
                oneByte,
                oneByte,
                null,
                1,
                oneByte,
                oneByte,
                oneByte);
        assertThrows(IllegalStateException.class, ethTxDataWithAccessList::encodeTx);

        // Type 1
        final EthTxData ethTsDataEIP2930 = new EthTxData(
                oneByte,
                EthTxData.EthTransactionType.EIP2930,
                oneByte,
                1,
                oneByte,
                oneByte,
                oneByte,
                1,
                oneByte,
                BigInteger.ONE,
                oneByte,
                oneByte,
                null,
                1,
                oneByte,
                oneByte,
                oneByte);
        assertThrows(IllegalStateException.class, ethTsDataEIP2930::encodeTx);
    }

    @Test
    void roundTripTests() {
        EthTxData parsed = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_0));
        assertNotNull(parsed);
        assertArrayEquals(Hex.decode(RAW_TX_TYPE_0), parsed.encodeTx());

        parsed = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_1));
        assertNotNull(parsed);
        assertArrayEquals(Hex.decode(RAW_TX_TYPE_1), parsed.encodeTx());

        parsed = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_2));
        assertNotNull(parsed);
        assertArrayEquals(Hex.decode(RAW_TX_TYPE_2), parsed.encodeTx());
    }

    @Test
    void replaceEip2390CallData() {
        final var parsed = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_1));
        assertNotNull(parsed);
        final var noCallData = parsed.replaceCallData(new byte[0]);
        assertArrayEquals(
                Hex.decode(
                        RAW_TX_TYPE_1
                                .replace("f873", "f870") // tx is shorter
                                .replace("83123456", "80") // calldata changed
                        ),
                noCallData.encodeTx());
    }

    @Test
    void replaceCallData() {
        final var parsed = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_2));
        assertNotNull(parsed);
        final var noCallData = parsed.replaceCallData(new byte[0]);
        assertArrayEquals(
                Hex.decode(RAW_TX_TYPE_2
                        .replace("f870", "f86d") // tx is shorter
                        .replace("83123456", "80")), // calldata changed
                noCallData.encodeTx());
    }

    @Test
    void amountInTinyBars() {
        final var oneByte = new byte[] {1};
        final EthTxData tinybarTx = new EthTxData(
                oneByte,
                EthTxData.EthTransactionType.EIP2930,
                oneByte,
                1,
                oneByte,
                oneByte,
                oneByte,
                1,
                oneByte,
                WEIBARS_IN_A_TINYBAR,
                oneByte,
                null,
                null,
                1,
                oneByte,
                oneByte,
                oneByte);
        assertEquals(1L, tinybarTx.getAmount());
    }

    @Test
    void toStringHashAndEquals() {
        final var parsed = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_2));
        assertNotNull(parsed);
        final var parsedAgain = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_2));
        assertNotNull(parsedAgain);
        final var parsed0 = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_0));
        assertNotNull(parsed0);
        assertDoesNotThrow(parsed::toString);
        assertDoesNotThrow(parsed0::toString);
        assertDoesNotThrow(parsed::hashCode);
        assertDoesNotThrow(parsed0::hashCode);

        assertEquals(parsed, parsedAgain);
        assertNotEquals(parsed, parsed0);
    }

    @Test
    void toStringHashAndEqualsType1() {
        final var parsed = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_1));
        assertNotNull(parsed);
        final var parsedAgain = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_1));
        assertNotNull(parsedAgain);
        final var parsed0 = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_0));
        assertNotNull(parsed0);
        assertDoesNotThrow(parsed::toString);
        assertDoesNotThrow(parsed0::toString);
        assertDoesNotThrow(parsed::hashCode);
        assertDoesNotThrow(parsed0::hashCode);

        assertEquals(parsed, parsedAgain);
        assertNotEquals(parsed, parsed0);
    }

    @Test
    void populatesAccessListAsRlpCorrectly() {
        final byte[] encodedAccessList = RLPEncoder.list(
                RLPEncoder.list(RLPEncoder.sequence("key1".getBytes(), "value1".getBytes())),
                RLPEncoder.list(RLPEncoder.sequence("key2".getBytes(), "value2".getBytes())));
        final RLPList rlpList = RLPDecoder.RLP_STRICT.wrapList(encodedAccessList);
        final Object[] accessListAsRlp = rlpList.elements().stream()
                .map(e -> e.isList() ? e.asRLPList().elements().toArray() : e.data())
                .toArray();

        final var ethTxData = new EthTxData(
                new byte[] {1},
                EthTxData.EthTransactionType.EIP1559,
                new byte[] {1},
                1L,
                new byte[] {1},
                new byte[] {1},
                new byte[] {1},
                100L,
                new byte[] {1},
                BigInteger.TEN,
                new byte[] {1},
                new byte[] {1},
                accessListAsRlp,
                0,
                new byte[] {1},
                new byte[] {1},
                new byte[] {1});
        assertArrayEquals(accessListAsRlp, ethTxData.accessListAsRlp());
    }

    @Test
    void equalsConsidersAccessListAsRlp() {
        final byte[] encodedAccessList = RLPEncoder.list(new Object[] {
            RLPEncoder.list(new Object[] {RLPEncoder.sequence("key1".getBytes(), "value1".getBytes())})
        });
        final byte[] differentEncodedAccessList = RLPEncoder.list(new Object[] {
            RLPEncoder.list(new Object[] {RLPEncoder.sequence("key2".getBytes(), "value2".getBytes())})
        });

        final RLPList rlpList = RLPDecoder.RLP_STRICT.wrapList(encodedAccessList);
        final RLPList differentRlpList = RLPDecoder.RLP_STRICT.wrapList(differentEncodedAccessList);

        final Object[] accessListAsRlp = rlpList.elements().stream()
                .map(e -> e.isList() ? e.asRLPList().elements().toArray() : e.data())
                .toArray();

        final Object[] differentAccessListAsRlp = differentRlpList.elements().stream()
                .map(e -> e.isList() ? e.asRLPList().elements().toArray() : e.data())
                .toArray();

        final var ethTxData = new EthTxData(
                new byte[] {1},
                EthTxData.EthTransactionType.EIP1559,
                new byte[] {1},
                1L,
                new byte[] {1},
                new byte[] {1},
                new byte[] {1},
                100L,
                new byte[] {1},
                BigInteger.TEN,
                new byte[] {1},
                new byte[] {1},
                accessListAsRlp,
                0,
                new byte[] {1},
                new byte[] {1},
                new byte[] {1});

        final var ethTxDataWithDifferentAccessList = new EthTxData(
                new byte[] {1},
                EthTxData.EthTransactionType.EIP1559,
                new byte[] {1},
                1L,
                new byte[] {1},
                new byte[] {1},
                new byte[] {1},
                100L,
                new byte[] {1},
                BigInteger.TEN,
                new byte[] {1},
                new byte[] {1},
                differentAccessListAsRlp,
                0,
                new byte[] {1},
                new byte[] {1},
                new byte[] {1});
        assertNotEquals(ethTxData, ethTxDataWithDifferentAccessList);

        final var theSameEthTxData = new EthTxData(
                new byte[] {1},
                EthTxData.EthTransactionType.EIP1559,
                new byte[] {1},
                1L,
                new byte[] {1},
                new byte[] {1},
                new byte[] {1},
                100L,
                new byte[] {1},
                BigInteger.TEN,
                new byte[] {1},
                new byte[] {1},
                accessListAsRlp,
                0,
                new byte[] {1},
                new byte[] {1},
                new byte[] {1});
        assertEquals(ethTxData, theSameEthTxData);
    }

    @Test
    void maxGasIsPositive() {
        final var oneByte = new byte[] {1};
        // high bit of most significant byte is zero
        // 45 tinybar as weibar
        final var smallGasPrice = Hex.decode("68c6171400");
        // high bit of most significant byte is one
        // 71 tinybar as weibar
        final var largeGasPrice = Hex.decode("a54f4c3c00");

        for (final var type : EthTxData.EthTransactionType.values()) {
            for (final var gasPrice : List.of(smallGasPrice, largeGasPrice)) {
                final EthTxData testTransaction = new EthTxData(
                        oneByte,
                        type,
                        oneByte,
                        1,
                        gasPrice,
                        gasPrice,
                        gasPrice,
                        1,
                        oneByte,
                        BigInteger.ONE,
                        oneByte,
                        oneByte,
                        null,
                        1,
                        oneByte,
                        oneByte,
                        oneByte);
                assertTrue(
                        testTransaction.getMaxGasAsBigInteger(TINYBAR_GAS_PRICE).compareTo(BigInteger.ZERO) > 0);
            }
        }
    }

    @Test
    void maxGasForDeterministicDeployerIsAsExpected() {
        final var oneByte = new byte[] {1};
        // 45 tinybar as weibar
        final var smallGasPrice = Hex.decode("68c6171400");
        final var type = EthTransactionType.LEGACY_ETHEREUM;
        final EthTxData testTransaction = new EthTxData(
                DETERMINISTIC_DEPLOYER_TRANSACTION,
                type,
                oneByte,
                1,
                smallGasPrice,
                smallGasPrice,
                smallGasPrice,
                1,
                oneByte,
                BigInteger.ONE,
                oneByte,
                oneByte,
                null,
                1,
                oneByte,
                oneByte,
                oneByte);
        assertTrue(testTransaction
                        .getMaxGasAsBigInteger(TINYBAR_GAS_PRICE)
                        .compareTo(BigInteger.valueOf(45)
                                .multiply(BigInteger.valueOf(DETERMINISTIC_DEPLOYER_GAS_PRICE_MULTIPLIER))
                                .multiply(WEIBARS_IN_A_TINYBAR))
                == 0);
    }

    @ParameterizedTest
    @EnumSource(EthTransactionType.class)
    void bigPositiveValueWithDifferentTypes(final EthTransactionType type) {
        final var bigValue = BigInteger.valueOf(Long.MAX_VALUE);

        final var oneByte = new byte[] {1};
        final EthTxData ethTxData = new EthTxData(
                oneByte, type, oneByte, 1, oneByte, oneByte, oneByte, 1, oneByte, bigValue, oneByte, null, null, 1,
                oneByte, oneByte, oneByte);
        final var encoded = ethTxData.encodeTx();

        final var populateEthTxData = EthTxData.populateEthTxData(encoded);

        assertEquals(bigValue, populateEthTxData.value());
    }

    @Test
    void populateEthTxDataComparedToUnsignedByteArrayNoExtraByteAdded() {
        final var subject = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_0_WITH_CHAIN_ID_11155111));
        final byte[] passingChainId = BigIntegers.asUnsignedByteArray(BigInteger.valueOf(11155111L));
        assertEquals(Hex.toHexString(subject.chainId()), Hex.toHexString(passingChainId));
    }

    @Test
    // In this scenario we are adding unexpected byte at the beginning of the bytes array.
    // Issue is better described here: https://github.com/hashgraph/hedera-services/issues/15953
    void populateEthTxDataComparedToSignedByteArrayExtraByteAdded() {
        final var subject = EthTxData.populateEthTxData(Hex.decode(RAW_TX_TYPE_0_WITH_CHAIN_ID_11155111));
        final byte[] failingChainId = BigInteger.valueOf(11155111L).toByteArray();
        assertNotEquals(Hex.toHexString(subject.chainId()), Hex.toHexString(failingChainId));
    }
}
