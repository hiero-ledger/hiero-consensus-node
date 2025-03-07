// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import java.util.Map;

/**
 * Supports testing of common scenarios. The keys and accounts used with these scenarios are harmonious with
 * those in the "Hedera Local Node". These keys are **safe** to include in the public repository because they
 * are well known public/private keys meant for testing and examples.
 *
 * <p>The following accounts are defined here:
 * <ol>
 *     <li>0.0.2</li>: The "treasury" account. This account is associated with the main private key
 *     <li>0.0.3</li>: Node 1's operator account.
 *     <li>0.0.4</li>: Node 2's operator account.
 *     <li>0.0.1000</li>: Node 3's operator account.
 *     <li>0.0.1001</li>: Node 4's operator account.
 *     <li>0.0.1002</li>: Alice's account. Alice is a user with an ECDSA account but no alias.
 *     <li>0.0.1003</li>: Bob's account. Bob is a user with an ED25519 account but no alias.
 *     <li>0.0.1004</li>: Carol's account. Carol is a user with an ECDSA account with an alias.
 *     <li>0.0.1005</li>: Dave's account. Dave has a multi-sig account with a 2/3 threshold controlled by Alice, Bob,
 *     and Carol.
 *     <li>0.0.1006</li>: Erin's account. This is a hollow account (account number with evm alias, but no keys)
 * </ol>
 *
 * <p>An additional account for "Frank" does not exist yet, but has keys associated with it, so it can be used in
 * transaction scenarios.
 */
public interface Scenarios extends TransactionFactory {
    static byte hexDecode(char c) {
        if (Character.isDigit(c)) {
            return (byte) (c - '0');
        } else if (Character.isAlphabetic(c) && c >= 'A' && c <= 'F') {
            return (byte) (10 + (c - 'A'));
        } else {
            throw new IllegalArgumentException("Char '" + c + "' is not a valid hex character");
        }
    }

    static Bytes hexBytes(String hex) {
        hex = hex.toUpperCase();
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("Hex string must be even length");
        final var bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int index = i * 2;
            byte hi = (byte) (hexDecode(hex.charAt(index)) << 4);
            byte lo = hexDecode(hex.charAt(index + 1));
            bytes[i] = (byte) (hi | lo);
        }
        return Bytes.wrap(bytes);
    }

    static Key ed25519(String hex) {
        return Key.newBuilder().ed25519(hexBytes(hex)).build();
    }

    static Key ecdsaSecp256k1(String hex) {
        return Key.newBuilder().ecdsaSecp256k1(hexBytes(hex)).build();
    }

    /** A legitimate key (ed25519) for the "treasury" account. Same as used with `hedera-local-node` */
    TestKeyInfo FAKE_TREASURE_KEY_INFO = new TestKeyInfo(
            hexBytes(
                    "302e020100300506032b65700422042091132178e72057a1d7528025956fe39b0b847f200ab59b2fdd367017f3087137"),
            ed25519("0aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6cd03a8c395a110e92"),
            ed25519("0aa8e21064c61eab86e2a9c164565b4e7a9a4146106e0a6cd03a8c395a110e92"),
            null);

    TestKeyInfo[] FAKE_ECDSA_KEY_INFOS = new TestKeyInfo[] {
        new TestKeyInfo(
                hexBytes("7f109a9e3b0d8ecfba9cc23a3614433ce0fa7ddcc80f2a8f10b222179a5a80d6"),
                ecdsaSecp256k1("024778141fffe462dbb3817c93e4252b2d6f5f5667fa71f34b84280265c01d408f"),
                ecdsaSecp256k1(
                        "4778141fffe462dbb3817c93e4252b2d6f5f5667fa71f34b84280265c01d408f8efcad8b564004ba1b4722d9b7323fcbc4937d6364d84f289e925c54a3d5b0f6"),
                null),
        new TestKeyInfo(
                hexBytes("6ec1f2e7d126a74a1d2ff9e1c5d90b92378c725e506651ff8bb8616a5c724628"),
                ecdsaSecp256k1("03cebebb1d82456c398ebd1532c7a44425fb65af44e340d5a2a244018d3390c843"),
                ecdsaSecp256k1(
                        "cebebb1d82456c398ebd1532c7a44425fb65af44e340d5a2a244018d3390c843ccdc31fe2d2cccee2c25cc5e96076bd8ecb2b5656897e8eca8ccb6c4c3104385"),
                null),
        new TestKeyInfo(
                hexBytes("b4d7f7e82f61d81c95985771b8abf518f9328d019c36849d4214b5f995d13814"),
                ecdsaSecp256k1("02e57607b7ff7d25556f16a31c1571d75b04ef685d884076d5ff800ca046000090"),
                ecdsaSecp256k1(
                        "e57607b7ff7d25556f16a31c1571d75b04ef685d884076d5ff800ca04600009044a1c5bdc9073fac720cdab61c301c9d4ea68758a65e145cdc401f6ec0ab3776"),
                null),
        new TestKeyInfo(
                hexBytes("941536648ac10d5734973e94df413c17809d6cc5e24cd11e947e685acfbd12ae"),
                ecdsaSecp256k1("0206d73c14edf64826c330c7b3cf000f51b3ddc9e2f8413b9fc906298d70e26707"),
                ecdsaSecp256k1(
                        "06d73c14edf64826c330c7b3cf000f51b3ddc9e2f8413b9fc906298d70e26707bd48fc0ee6a986d257659224ce2ee2b2e2d1901f52e8fd0843a16dd6768d0330"),
                null),
        new TestKeyInfo(
                hexBytes("5829cf333ef66b6bdd34950f096cb24e06ef041c5f63e577b4f3362309125863"),
                ecdsaSecp256k1("035bbb1a9545201278cef507b0799279146a541d0d004bdc16b742d5381a8b9ded"),
                ecdsaSecp256k1(
                        "5bbb1a9545201278cef507b0799279146a541d0d004bdc16b742d5381a8b9ded2b93a5edb4bcdff15f57336e19238a59517df7f78c754ce7c38610217f7b6a8b"),
                null),
        new TestKeyInfo(
                hexBytes("8fc4bffe2b40b2b7db7fd937736c4575a0925511d7a0a2dfc3274e8c17b41d20"),
                ecdsaSecp256k1("03d6f7efdba064005f7f96801c2489a1f2059f95c8b936446178080181d9c3e3b6"),
                ecdsaSecp256k1(
                        "d6f7efdba064005f7f96801c2489a1f2059f95c8b936446178080181d9c3e3b6d54f6f6fd63ab6f3875a74f1c7782c6a13439120c071cd29a458029ab68e0e81"),
                null),
        new TestKeyInfo(
                hexBytes("b6c10e2baaeba1fa4a8b73644db4f28f4bf0912cceb6e8959f73bb423c33bd84"),
                ecdsaSecp256k1("02b75677cfda0ced71df7720f43bb6014275e9e99810dbc837051f9e9b712b2b3a"),
                ecdsaSecp256k1(
                        "b75677cfda0ced71df7720f43bb6014275e9e99810dbc837051f9e9b712b2b3a270111017b3b970a7d0e43cd98dd935ff097424a8d4234e50097a7960e3ab42c"),
                null),
        new TestKeyInfo(
                hexBytes("fe8875acb38f684b2025d5472445b8e4745705a9e7adc9b0485a05df790df700"),
                ecdsaSecp256k1("0325fa3ffcac03b428f39039a446fb1129834288cdd8e77aeb2471f13482355076"),
                ecdsaSecp256k1(
                        "25fa3ffcac03b428f39039a446fb1129834288cdd8e77aeb2471f13482355076bd8214710e7f0496d48f31cc295f8194f2fc738c77bd85e7f6677b7d25916419"),
                null),
        new TestKeyInfo(
                hexBytes("bdc6e0a69f2921a78e9af930111334a41d3fab44653c8de0775572c526feea2d"),
                ecdsaSecp256k1("03a43058440d49e1f8545ea40603813c7171f51020a13ee10be9532d166172a807"),
                ecdsaSecp256k1(
                        "a43058440d49e1f8545ea40603813c7171f51020a13ee10be9532d166172a8071864925f36036df4cf225e3b678fd0209fd79c1e488178a05083c31f965049a9"),
                null),
        new TestKeyInfo(
                hexBytes("3e215c3d2a59626a669ed04ec1700f36c05c9b216e592f58bbfd3d8aa6ea25f9"),
                ecdsaSecp256k1("03ccc76db535eae90bd72a09f6c40affc1af4629aab6dffc26ba163b4798b8ffd3"),
                ecdsaSecp256k1(
                        "ccc76db535eae90bd72a09f6c40affc1af4629aab6dffc26ba163b4798b8ffd33c4658c25556dabaa9299af1651438416da93a6d25c969fce1f61da0c50da35d"),
                null)
    };

    TestKeyInfo[] FAKE_ECDSA_WITH_ALIAS_KEY_INFOS = new TestKeyInfo[] {
        new TestKeyInfo(
                hexBytes("105d050185ccb907fba04dd92d8de9e32c18305e097ab41dadda21489a211524"),
                ecdsaSecp256k1("02b3c641418e89452cd5202adfd4758f459acb8e364f741fd16cd2db79835d39d2"),
                ecdsaSecp256k1(
                        "b3c641418e89452cd5202adfd4758f459acb8e364f741fd16cd2db79835d39d2cb8be79be6ac6e1fb9da8013904a077c3909110a122311f470f1dcc390a5aca6"),
                hexBytes("67D8d32E9Bf1a9968a5ff53B87d777Aa8EBBEe69")),
        new TestKeyInfo(
                hexBytes("2e1d968b041d84dd120a5860cee60cd83f9374ef527ca86996317ada3d0d03e7"),
                ecdsaSecp256k1("03c065d7a6c816e36a20460f16bcb91d33a4c0231cccfb648776c392188d561849"),
                ecdsaSecp256k1(
                        "c065d7a6c816e36a20460f16bcb91d33a4c0231cccfb648776c392188d561849d9bb07fd1a94f7a332b750caa54c39b4a589213a4053ddb592d02a8523ae4deb"),
                hexBytes("05FbA803Be258049A27B820088bab1cAD2058871")),
        new TestKeyInfo(
                hexBytes("45a5a7108a18dd5013cf2d5857a28144beadc9c70b3bdbd914e38df4e804b8d8"),
                ecdsaSecp256k1("02930a39a381a68d90afc8e8c82935bd93f89800e88ec29a18e8cc13d51947c6c8"),
                ecdsaSecp256k1(
                        "930a39a381a68d90afc8e8c82935bd93f89800e88ec29a18e8cc13d51947c6c8b99141397d39f13daffbbdb8aa4b5dc83725873996138e52ff482e52681f36a4"),
                hexBytes("927E41Ff8307835A1C081e0d7fD250625F2D4D0E")),
        new TestKeyInfo(
                hexBytes("6e9d61a325be3f6675cf8b7676c70e4a004d2308e3e182370a41f5653d52c6bd"),
                ecdsaSecp256k1("035303ada0ba5827148f254bddebb24aea59f0c035fceddedef5b47f4d2ef5d34e"),
                ecdsaSecp256k1(
                        "5303ada0ba5827148f254bddebb24aea59f0c035fceddedef5b47f4d2ef5d34e8798e58d886b9451daf4b654e9ed1cc708d59217db0e7a16a1f1b828969cc9a3"),
                hexBytes("c37f417fA09933335240FCA72DD257BFBdE9C275")),
        new TestKeyInfo(
                hexBytes("0b58b1bd44469ac9f813b5aeaf6213ddaea26720f0b2f133d08b6f234130a64f"),
                ecdsaSecp256k1("0398e17bcbd2926c4d8a31e32616b4754ac0a2fc71d7fb768e657db46202625f34"),
                ecdsaSecp256k1(
                        "98e17bcbd2926c4d8a31e32616b4754ac0a2fc71d7fb768e657db46202625f34ca1d755ea1c7a7369219bfee41bd00678eb019440ea61f274effcf911bc904d3"),
                hexBytes("D927017F5a6a7A92458b81468Dc71FCE6115B325")),
        new TestKeyInfo(
                hexBytes("95eac372e0f0df3b43740fa780e62458b2d2cc32d6a440877f1cc2a9ad0c35cc"),
                ecdsaSecp256k1("03c1b3b364f2fadb3030633ca602865d35f43eacac404620c3165a22724982876f"),
                ecdsaSecp256k1(
                        "c1b3b364f2fadb3030633ca602865d35f43eacac404620c3165a22724982876f307c9bac73351094dc4c10b173c13025d0c2a9cd6eebe5ece78ce4a5242de749"),
                hexBytes("5C41A21F14cFe9808cBEc1d91b55Ba75ed327Eb6")),
        new TestKeyInfo(
                hexBytes("6c6e6727b40c8d4b616ab0d26af357af09337299f09c66704146e14236972106"),
                ecdsaSecp256k1("029e7d3f0f26884c59e350047049afc4d2b802853be83f21ee5f7431a1670a78d6"),
                ecdsaSecp256k1(
                        "9e7d3f0f26884c59e350047049afc4d2b802853be83f21ee5f7431a1670a78d6b2d81fdbac9de6a3d92301f2badcaa913a53d7fc94ed2a6395e07b0f8795cb74"),
                hexBytes("cdaD5844f865F379beA057fb435AEfeF38361B68")),
        new TestKeyInfo(
                hexBytes("5072e7aa1b03f531b4731a32a021f6a5d20d5ddc4e55acbb71ae202fc6f3a26d"),
                ecdsaSecp256k1("0355220066227c5cfa7c362ddb97e59d9af24092dd65035f08cddb761cd1df448e"),
                ecdsaSecp256k1(
                        "55220066227c5cfa7c362ddb97e59d9af24092dd65035f08cddb761cd1df448e0bcc45c21fd023f43a8ffd02e9079696a2bfdcd67a79047a5de66ee2c678d563"),
                hexBytes("6e5D3858f53FC66727188690946631bDE0466B1A")),
        new TestKeyInfo(
                hexBytes("60fe891f13824a2c1da20fb6a14e28fa353421191069ba6b6d09dd6c29b90eff"),
                ecdsaSecp256k1("0223f4c4f1e822f75e56d21e75066e982e0823f282e1f3bf7e59ae692cf7243c5b"),
                ecdsaSecp256k1(
                        "23f4c4f1e822f75e56d21e75066e982e0823f282e1f3bf7e59ae692cf7243c5b988768144efb94e6f02f3f0ca61165eb1538ede4394bb8eada56dbebd73670e0"),
                hexBytes("29cbb51A44fd332c14180b4D471FBBc6654b1657")),
        new TestKeyInfo(
                hexBytes("eae4e00ece872dd14fb6dc7a04f390563c7d69d16326f2a703ec8e0934060cc7"),
                ecdsaSecp256k1("0312c727db165d8b1a7b1ec690ab0936cadb239f882b07c677cdeca18b69d24dbc"),
                ecdsaSecp256k1(
                        "12c727db165d8b1a7b1ec690ab0936cadb239f882b07c677cdeca18b69d24dbccf9ec6fd71cb846b5af47cb42ecce3900c971e20d2dde6e2578afd47d422daa9"),
                hexBytes("17b2B8c63Fa35402088640e426c6709A254c7fFb")),
    };

    TestKeyInfo[] FAKE_ED25519_KEY_INFOS = new TestKeyInfo[] {
        new TestKeyInfo(
                hexBytes("a608e2130a0a3cb34f86e757303c862bee353d9ab77ba4387ec084f881d420d4"),
                ed25519("0b3d4a18bb0013cf9bc80b988a3ea3043775438493534e4d04c1687e848164c8"),
                ed25519("0b3d4a18bb0013cf9bc80b988a3ea3043775438493534e4d04c1687e848164c8"),
                null),
        new TestKeyInfo(
                hexBytes("bbd0894de0b4ecfa862e963825c5448d2d17f807a16869526bff29185747acdb"),
                ed25519("d9f8acac5501ecdd23263743445a31913881fbf78077d2863f6cec07a4c29ed9"),
                ed25519("d9f8acac5501ecdd23263743445a31913881fbf78077d2863f6cec07a4c29ed9"),
                null),
        new TestKeyInfo(
                hexBytes("8fd50f886a2e7ed499e7686efd1436b50aa9b64b26e4ecc4e58ca26e6257b67d"),
                ed25519("8d61ad3321f119b2224ce9d75da1132fd843e29cb5eb87ace6a61d9526b41923"),
                ed25519("8d61ad3321f119b2224ce9d75da1132fd843e29cb5eb87ace6a61d9526b41923"),
                null),
        new TestKeyInfo(
                hexBytes("62c966ebd9dcc0fc16a553b2ef5b72d1dca05cdf5a181027e761171e9e947420"),
                ed25519("cec23729fde1b44ce629f28d51562a7c8fad19f6233d122197d31c3083e83c89"),
                ed25519("cec23729fde1b44ce629f28d51562a7c8fad19f6233d122197d31c3083e83c89"),
                null),
        new TestKeyInfo(
                hexBytes("805c9f422fd9a768fdd8c68f4fe0c3d4a93af714ed147ab6aed5f0ee8e9ee165"),
                ed25519("93ccf601eac58bbe3cdf2d7463714c0ec66079c77d91fde97c4b8852249136d3"),
                ed25519("93ccf601eac58bbe3cdf2d7463714c0ec66079c77d91fde97c4b8852249136d3"),
                null),
        new TestKeyInfo(
                hexBytes("abfdb8bf0b46c0da5da8d764316f27f185af32357689f7e19cb9ec3e0f590775"),
                ed25519("d70390ff7208352468173a3762ad1e62a2507aa6b7ab27029c09bf764ca0a79d"),
                ed25519("d70390ff7208352468173a3762ad1e62a2507aa6b7ab27029c09bf764ca0a79d"),
                null),
        new TestKeyInfo(
                hexBytes("ec299c9f17bb8bdd5f3a21f1c2bffb3ac86c22e84c325e92139813639c9c3507"),
                ed25519("744e636f78ccc9091422fcad582f61e6b0a8ad83aaa760a79d844a2924adbfda"),
                ed25519("744e636f78ccc9091422fcad582f61e6b0a8ad83aaa760a79d844a2924adbfda"),
                null),
        new TestKeyInfo(
                hexBytes("cb833706d1df537f59c418a00e36159f67ce3760ce6bf661f11f6da2b11c2c5a"),
                ed25519("99b6b48d00038a65d9342b37dea634d189de09a0ce68212401515bbc9dc93248"),
                ed25519("99b6b48d00038a65d9342b37dea634d189de09a0ce68212401515bbc9dc93248"),
                null),
        new TestKeyInfo(
                hexBytes("9b6adacefbbecff03e4359098d084a3af8039ce7f29d95ed28c7ebdb83740c83"),
                ed25519("231f98363b3478c21a0dee96a71b3cd5270e6a53ae1a1ad8fcf87b63b2a1b977"),
                ed25519("231f98363b3478c21a0dee96a71b3cd5270e6a53ae1a1ad8fcf87b63b2a1b977"),
                null),
        new TestKeyInfo(
                hexBytes("9a07bbdbb62e24686d2a4259dc88e38438e2c7a1ba167b147ad30ac540b0a3cd"),
                ed25519("529cc3c800fffb46c9c0b33bdd26293bf2e8d433557608e019f8010079c86ab8"),
                ed25519("529cc3c800fffb46c9c0b33bdd26293bf2e8d433557608e019f8010079c86ab8"),
                null)
    };

    final AccountID account3 = AccountID.newBuilder().accountNum(3L).build();
    final TestNode NODE_1 = new TestNode(
            0L,
            account3,
            Account.newBuilder()
                    .accountId(account3)
                    .key(FAKE_ECDSA_KEY_INFOS[0].publicKey())
                    .declineReward(true)
                    .build(),
            FAKE_ECDSA_KEY_INFOS[0]);

    final AccountID account4 = AccountID.newBuilder().accountNum(4L).build();
    final TestNode NODE_2 = new TestNode(
            0L,
            account4,
            Account.newBuilder()
                    .accountId(account4)
                    .key(FAKE_ED25519_KEY_INFOS[0].publicKey())
                    .declineReward(true)
                    .build(),
            FAKE_ED25519_KEY_INFOS[0]);

    final AccountID account800 = AccountID.newBuilder().accountNum(800L).build();
    final TestUser STAKING_REWARD_ACCOUNT =
            new TestUser(account800, Account.newBuilder().accountId(account800).build(), null);

    final AccountID account098 = AccountID.newBuilder().accountNum(98L).build();
    final TestUser FUNDING_ACCOUNT =
            new TestUser(account098, Account.newBuilder().accountId(account098).build(), null);

    final AccountID account1002 = AccountID.newBuilder().accountNum(1002L).build();
    final TestUser ALICE = new TestUser(
            account1002,
            Account.newBuilder()
                    .tinybarBalance(100_000_000)
                    .accountId(account1002)
                    .key(FAKE_ECDSA_KEY_INFOS[2].publicKey())
                    .build(),
            FAKE_ECDSA_KEY_INFOS[2]);

    final AccountID account1003 = AccountID.newBuilder().accountNum(1003L).build();
    final TestUser BOB = new TestUser(
            account1003,
            Account.newBuilder()
                    .accountId(account1003)
                    .key(FAKE_ED25519_KEY_INFOS[1].publicKey())
                    .build(),
            FAKE_ED25519_KEY_INFOS[1]);

    final AccountID account1004 = AccountID.newBuilder().accountNum(1004L).build();
    final TestUser CAROL = new TestUser(
            account1004,
            Account.newBuilder()
                    .accountId(account1004)
                    .key(FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0].publicKey())
                    .alias(hexBytes("67d8d32e9bf1a9968a5ff53b87d777aa8ebbee69"))
                    .build(),
            FAKE_ECDSA_WITH_ALIAS_KEY_INFOS[0]);

    final AccountID account1006 = AccountID.newBuilder().accountNum(1006L).build();
    final TestUser ERIN = new TestUser(
            account1006,
            Account.newBuilder()
                    .accountId(account1006)
                    .key(Key.newBuilder().keyList(KeyList.DEFAULT).build())
                    .alias(hexBytes("0b75f0b70076fab3f18f94700ecaf3b00fe528e7"))
                    .build(),
            FAKE_ECDSA_KEY_INFOS[3]);

    TestUser FRANK = new TestUser(AccountID.newBuilder().accountNum(2000L).build(), null, FAKE_ECDSA_KEY_INFOS[3]);

    default Map<AccountID, Account> defaultAccounts() {
        return Map.of(
                NODE_1.nodeAccountID(), NODE_1.account(),
                ALICE.accountID(), ALICE.account(),
                ERIN.accountID(), ERIN.account());
    }

    default Map<ProtoBytes, AccountID> defaultAliases() {
        return Map.of();
    }

    default MapReadableKVState<AccountID, Account> defaultAccountKVState() {
        return new MapReadableKVState<>(TokenService.NAME, "ACCOUNTS", defaultAccounts());
    }

    default MapReadableKVState<ProtoBytes, AccountID> defaultAliasesKVState() {
        return new MapReadableKVState<>(TokenService.NAME, "ALIASES", defaultAliases());
    }

    default ReadableStates defaultTokenReadableStates() {
        return MapReadableStates.builder()
                .state(defaultAccountKVState())
                .state(defaultAliasesKVState())
                .build();
    }
}
