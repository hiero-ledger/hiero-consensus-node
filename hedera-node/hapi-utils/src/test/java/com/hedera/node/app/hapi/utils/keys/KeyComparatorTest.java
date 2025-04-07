// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.keys;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class KeyComparatorTest {

    private static final String CASE_FAIL_MESSAGE =
            """
            Case %s failed.
            Expected value is %d, not %5$d.
            compare(%s, %s) returned %d.
            """;

    @BeforeEach
    void setUp() {}

    @ParameterizedTest(name = "{0} - ({argumentsWithNames})")
    @MethodSource("generateAllCrossTypeMatches")
    void verifyCompareCrossTypeMatchesOrdinalOrder(
            final String caseName, final Integer expectedValue, final Key lValue, final Key rValue) {
        final KeyComparator subject = new KeyComparator();
        final Integer actualResult = Integer.valueOf(subject.compare(lValue, rValue));
        Assertions.assertThat(actualResult)
                .withFailMessage(CASE_FAIL_MESSAGE, caseName, expectedValue, lValue, rValue, actualResult)
                .isEqualTo(expectedValue);
        // Verify forward and reverse comparison are consistent, otherwise the comparator order is unstable.
        // requirement: compare(a,b) === -compare(b,a)
        final int inverseResult = subject.compare(rValue, lValue);
        final int inverseExpected = -(expectedValue.intValue());
        Assertions.assertThat(inverseResult)
                .withFailMessage(
                        CASE_FAIL_MESSAGE, "Inverse-" + caseName, inverseExpected, rValue, lValue, inverseResult)
                .isEqualTo(inverseExpected);
    }

    @ParameterizedTest(name = "{0} - ({argumentsWithNames})")
    @MethodSource("generateAllSameTypeMatches")
    void verifyCompareSameTypeMatchesExpectedOrder(
            final String caseName, final Integer expectedValue, final Key lValue, final Key rValue) {
        final KeyComparator subject = new KeyComparator();
        final int actualResult = subject.compare(lValue, rValue);
        final int expected = expectedValue.intValue();
        Assertions.assertThat(actualResult)
                .withFailMessage(CASE_FAIL_MESSAGE, caseName, expected, lValue, rValue, actualResult)
                .isEqualTo(expectedValue);
        // Verify forward and reverse comparison are consistent, otherwise the comparator order is unstable.
        // requirement: compare(a,b) == -compare(b,a)
        final int inverseResult = subject.compare(rValue, lValue);
        final int inverseExpected = -(expectedValue.intValue());
        Assertions.assertThat(inverseResult)
                .withFailMessage(
                        CASE_FAIL_MESSAGE, "Inverse-" + caseName, inverseExpected, rValue, lValue, inverseResult)
                .isEqualTo(inverseExpected);
    }

    @ParameterizedTest(name = "{0} - ({argumentsWithNames})")
    @MethodSource("generateAllUnsupportedTypes")
    void verifyUnsupportedTypes(final String caseName, final Key lValue, final Key rValue) {
        final KeyComparator subject = new KeyComparator();
        Assertions.assertThatThrownBy(() -> subject.compare(lValue, rValue))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                              Parameterized test method sources                             //
    ////////////////////////////////////////////////////////////////////////////////////////////////
    public static Stream<Arguments> generateAllCrossTypeMatches() {
        final Integer zero = Integer.valueOf(0);
        final Integer one = Integer.valueOf(1);
        final Integer minusOne = Integer.valueOf(-1);
        final Key unsetKey = Key.newBuilder().build(); // no type assigned, so UNSET
        final List<Arguments> result = new LinkedList<>();

        result.add(Arguments.of("Null Input", zero, null, null));
        result.add(Arguments.of("Null-UNSET", minusOne, null, unsetKey));
        result.add(Arguments.of("UNSET-Null", one, unsetKey, null));

        result.add(Arguments.of("UNSET-CONTRACT", minusOne, unsetKey, SAMPLE_CONTRACT_ID));
        result.add(Arguments.of("UNSET-ED25519", minusOne, unsetKey, SIMPLE_ED25519[1]));
        result.add(Arguments.of("UNSET-RSA3072", minusOne, unsetKey, INVALID_RSA_KEY));
        result.add(Arguments.of("UNSET-ECDSAP384", minusOne, unsetKey, INVALID_ECDSAP384_KEY));
        result.add(Arguments.of("UNSET-THRESHOLD", minusOne, unsetKey, ECDSA_THRESHOLD_KEY));
        result.add(Arguments.of("UNSET-KEYLIST", minusOne, unsetKey, MIXED_KEY_LIST_KEY));
        result.add(Arguments.of("UNSET-SECP256K1", minusOne, unsetKey, SIMPLE_ECDSA[1]));
        result.add(Arguments.of("UNSET-DELEGATE_CONTRACT", minusOne, unsetKey, SAMPLE_DELEGATE_CONTRACT_ID));

        result.add(Arguments.of("CONTRACT-UNSET", one, SAMPLE_CONTRACT_ID, unsetKey));
        result.add(Arguments.of("CONTRACT-ED25519", minusOne, SAMPLE_CONTRACT_ID, SIMPLE_ED25519[1]));
        result.add(Arguments.of("CONTRACT-RSA3072", minusOne, SAMPLE_CONTRACT_ID, INVALID_RSA_KEY));
        result.add(Arguments.of("CONTRACT-ECDSAP384", minusOne, SAMPLE_CONTRACT_ID, INVALID_ECDSAP384_KEY));
        result.add(Arguments.of("CONTRACT-THRESHOLD", minusOne, SAMPLE_CONTRACT_ID, ECDSA_THRESHOLD_KEY));
        result.add(Arguments.of("CONTRACT-KEYLIST", minusOne, SAMPLE_CONTRACT_ID, MIXED_KEY_LIST_KEY));
        result.add(Arguments.of("CONTRACT-SECP256K1", minusOne, SAMPLE_CONTRACT_ID, SIMPLE_ECDSA[1]));
        result.add(
                Arguments.of("CONTRACT-DELEGATE_CONTRACT", minusOne, SAMPLE_CONTRACT_ID, SAMPLE_DELEGATE_CONTRACT_ID));

        result.add(Arguments.of("ED25519-UNSET", one, SIMPLE_ED25519[1], unsetKey));
        result.add(Arguments.of("ED25519-CONTRACT", one, SIMPLE_ED25519[1], SAMPLE_CONTRACT_ID));
        result.add(Arguments.of("ED25519-RSA3072", minusOne, SIMPLE_ED25519[1], INVALID_RSA_KEY));
        result.add(Arguments.of("ED25519-ECDSAP384", minusOne, SIMPLE_ED25519[1], INVALID_ECDSAP384_KEY));
        result.add(Arguments.of("ED25519-THRESHOLD", minusOne, SIMPLE_ED25519[1], ECDSA_THRESHOLD_KEY));
        result.add(Arguments.of("ED25519-KEYLIST", minusOne, SIMPLE_ED25519[1], MIXED_KEY_LIST_KEY));
        result.add(Arguments.of("ED25519-SECP256K1", minusOne, SIMPLE_ED25519[1], SIMPLE_ECDSA[1]));
        result.add(Arguments.of("ED25519-DELEGATE_CONTRACT", minusOne, SIMPLE_ED25519[1], SAMPLE_DELEGATE_CONTRACT_ID));

        result.add(Arguments.of("RSA3072-UNSET", one, INVALID_RSA_KEY, unsetKey));
        result.add(Arguments.of("RSA3072-CONTRACT", one, INVALID_RSA_KEY, SAMPLE_CONTRACT_ID));
        result.add(Arguments.of("RSA3072-ED25519", one, INVALID_RSA_KEY, SIMPLE_ED25519[1]));
        result.add(Arguments.of("RSA3072-ECDSAP384", minusOne, INVALID_RSA_KEY, INVALID_ECDSAP384_KEY));
        result.add(Arguments.of("RSA3072-THRESHOLD", minusOne, INVALID_RSA_KEY, ECDSA_THRESHOLD_KEY));
        result.add(Arguments.of("RSA3072-KEYLIST", minusOne, INVALID_RSA_KEY, MIXED_KEY_LIST_KEY));
        result.add(Arguments.of("RSA3072-SECP256K1", minusOne, INVALID_RSA_KEY, SIMPLE_ECDSA[1]));
        result.add(Arguments.of("RSA3072-DELEGATE_CONTRACT", minusOne, INVALID_RSA_KEY, SAMPLE_DELEGATE_CONTRACT_ID));

        result.add(Arguments.of("ECDSAP384-UNSET", one, INVALID_ECDSAP384_KEY, unsetKey));
        result.add(Arguments.of("ECDSAP384-CONTRACT", one, INVALID_ECDSAP384_KEY, SAMPLE_CONTRACT_ID));
        result.add(Arguments.of("ECDSAP384-ED25519", one, INVALID_ECDSAP384_KEY, SIMPLE_ED25519[1]));
        result.add(Arguments.of("ECDSAP384-RSA3072", one, INVALID_ECDSAP384_KEY, INVALID_RSA_KEY));
        result.add(Arguments.of("ECDSAP384-THRESHOLD", minusOne, INVALID_ECDSAP384_KEY, ECDSA_THRESHOLD_KEY));
        result.add(Arguments.of("ECDSAP384-KEYLIST", minusOne, INVALID_ECDSAP384_KEY, MIXED_KEY_LIST_KEY));
        result.add(Arguments.of("ECDSAP384-SECP256K1", minusOne, INVALID_ECDSAP384_KEY, SIMPLE_ECDSA[1]));
        result.add(Arguments.of(
                "ECDSAP384-DELEGATE_CONTRACT", minusOne, INVALID_ECDSAP384_KEY, SAMPLE_DELEGATE_CONTRACT_ID));

        result.add(Arguments.of("THRESHOLD-UNSET", one, MIXED_THRESHOLD_KEY, unsetKey));
        result.add(Arguments.of("THRESHOLD-CONTRACT", one, MIXED_THRESHOLD_KEY, SAMPLE_CONTRACT_ID));
        result.add(Arguments.of("THRESHOLD-ED25519", one, MIXED_THRESHOLD_KEY, SIMPLE_ED25519[1]));
        result.add(Arguments.of("THRESHOLD-RSA3072", one, MIXED_THRESHOLD_KEY, INVALID_RSA_KEY));
        result.add(Arguments.of("THRESHOLD-ECDSAP384", one, MIXED_THRESHOLD_KEY, INVALID_ECDSAP384_KEY));
        result.add(Arguments.of("THRESHOLD-KEYLIST", minusOne, MIXED_THRESHOLD_KEY, MIXED_KEY_LIST_KEY));
        result.add(Arguments.of("THRESHOLD-SECP256K1", minusOne, MIXED_THRESHOLD_KEY, SIMPLE_ECDSA[1]));
        result.add(Arguments.of(
                "THRESHOLD-DELEGATE_CONTRACT", minusOne, MIXED_THRESHOLD_KEY, SAMPLE_DELEGATE_CONTRACT_ID));

        result.add(Arguments.of("KEYLIST-UNSET", one, MIXED_KEY_LIST_KEY, unsetKey));
        result.add(Arguments.of("KEYLIST-CONTRACT", one, MIXED_KEY_LIST_KEY, SAMPLE_CONTRACT_ID));
        result.add(Arguments.of("KEYLIST-ED25519", one, MIXED_KEY_LIST_KEY, SIMPLE_ED25519[1]));
        result.add(Arguments.of("KEYLIST-RSA3072", one, MIXED_KEY_LIST_KEY, INVALID_RSA_KEY));
        result.add(Arguments.of("KEYLIST-ECDSAP384", one, MIXED_KEY_LIST_KEY, INVALID_ECDSAP384_KEY));
        result.add(Arguments.of("KEYLIST-THRESHOLD", one, MIXED_KEY_LIST_KEY, ECDSA_THRESHOLD_KEY));
        result.add(Arguments.of("KEYLIST-SECP256K1", minusOne, MIXED_KEY_LIST_KEY, SIMPLE_ECDSA[1]));
        result.add(
                Arguments.of("KEYLIST-DELEGATE_CONTRACT", minusOne, MIXED_KEY_LIST_KEY, SAMPLE_DELEGATE_CONTRACT_ID));

        result.add(Arguments.of("SECP256K1-UNSET", one, SIMPLE_ECDSA[1], unsetKey));
        result.add(Arguments.of("SECP256K1-CONTRACT", one, SIMPLE_ECDSA[1], SAMPLE_CONTRACT_ID));
        result.add(Arguments.of("SECP256K1-ED25519", one, SIMPLE_ECDSA[1], SIMPLE_ED25519[1]));
        result.add(Arguments.of("SECP256K1-RSA3072", one, SIMPLE_ECDSA[1], INVALID_RSA_KEY));
        result.add(Arguments.of("SECP256K1-ECDSAP384", one, SIMPLE_ECDSA[1], INVALID_ECDSAP384_KEY));
        result.add(Arguments.of("SECP256K1-THRESHOLD", one, SIMPLE_ECDSA[1], ECDSA_THRESHOLD_KEY));
        result.add(Arguments.of("SECP256K1-KEYLIST", one, SIMPLE_ECDSA[1], MIXED_KEY_LIST_KEY));
        result.add(Arguments.of("SECP256K1-DELEGATE_CONTRACT", minusOne, SIMPLE_ECDSA[1], SAMPLE_DELEGATE_CONTRACT_ID));

        result.add(Arguments.of("DELEGATE_CONTRACT-UNSET", one, SAMPLE_DELEGATE_CONTRACT_ID, unsetKey));
        result.add(Arguments.of("DELEGATE_CONTRACT-CONTRACT", one, SAMPLE_DELEGATE_CONTRACT_ID, SAMPLE_CONTRACT_ID));
        result.add(Arguments.of("DELEGATE_CONTRACT-ED25519", one, SAMPLE_DELEGATE_CONTRACT_ID, SIMPLE_ED25519[1]));
        result.add(Arguments.of("DELEGATE_CONTRACT-RSA3072", one, SAMPLE_DELEGATE_CONTRACT_ID, INVALID_RSA_KEY));
        result.add(
                Arguments.of("DELEGATE_CONTRACT-ECDSAP384", one, SAMPLE_DELEGATE_CONTRACT_ID, INVALID_ECDSAP384_KEY));
        result.add(Arguments.of("DELEGATE_CONTRACT-THRESHOLD", one, SAMPLE_DELEGATE_CONTRACT_ID, ECDSA_THRESHOLD_KEY));
        result.add(Arguments.of("DELEGATE_CONTRACT-KEYLIST", one, SAMPLE_DELEGATE_CONTRACT_ID, MIXED_KEY_LIST_KEY));
        result.add(Arguments.of("DELEGATE_CONTRACT-SECP256K1", one, SAMPLE_DELEGATE_CONTRACT_ID, SIMPLE_ECDSA[1]));

        return result.stream();
    }

    private static Stream<Arguments> generateAllUnsupportedTypes() {
        return Stream.of(
                Arguments.of("ECDSAP384", INVALID_ECDSAP384_KEY, OTHER_ECDSAP384_KEY),
                Arguments.of("RSA3072", INVALID_RSA_KEY, OTHER_RSA_KEY));
    }

    public static Stream<Arguments> generateAllSameTypeMatches() {
        final Integer zero = Integer.valueOf(0);
        final Key unsetKey = Key.newBuilder().build(); // no type assigned, so UNSET
        final List<Arguments> result = new LinkedList<>();
        // null == null and unset == unset.
        result.add(Arguments.of("Null Input", zero, null, null));
        result.add(Arguments.of("UNSET-UNSET", zero, unsetKey, unsetKey));
        // Note: the test itself verifies both forward and inverse for all inputs,
        //     so we do not need to include the inverse of each case.
        ed25519Cases(result);
        SecP256K1Cases(result);
        contractIdCases(result);
        delegateIdCases(result);
        keyListCases(result);
        thresholdCases(result);
        return result.stream();
    }

    private static void thresholdCases(final List<Arguments> result) {
        final String testNameFormat = "Threshold-%s-%s";
        final Integer zero = Integer.valueOf(0);
        final Integer one = Integer.valueOf(1);
        final Integer minusOne = Integer.valueOf(-1);
        // Create 10 each contract and delegate-contract keys
        // contract num are even and evm address are odd
        final Key[] contractKeys = generateContractKeysForLists();
        final Key[] delegateKeys = generateDelegateKeysForLists();
        // Generate some simple key lists using the above items and other primitive keys
        final Key[] baseLists = generateBaseKeyLists(contractKeys, delegateKeys);
        final List<Key> thresholdKeys = new LinkedList<>();
        thresholdKeys.add(thresholdOf(2, baseLists[0].keyList()));
        thresholdKeys.add(thresholdOf(2, baseLists[0].keyList()));
        thresholdKeys.add(thresholdOf(3, baseLists[0].keyList()));
        thresholdKeys.add(thresholdOf(1, baseLists[0].keyList()));
        thresholdKeys.add(thresholdOf(2, baseLists[1].keyList()));
        thresholdKeys.add(thresholdOf(3, baseLists[1].keyList()));
        thresholdKeys.add(thresholdOf(1, baseLists[1].keyList()));
        result.add(Arguments.of("Threshold-same-values-equal", zero, thresholdKeys.get(0), thresholdKeys.get(1)));
        result.add(Arguments.of("Threshold-higher-threshold", minusOne, thresholdKeys.get(1), thresholdKeys.get(2)));
        result.add(Arguments.of("Threshold-lower-threshold", one, thresholdKeys.get(2), thresholdKeys.get(3)));
        result.add(Arguments.of("Threshold-simple-lists-differ", minusOne, thresholdKeys.get(3), thresholdKeys.get(6)));
        result.add(Arguments.of("Threshold-simple-all-differ", minusOne, thresholdKeys.get(1), thresholdKeys.get(5)));
        // two lists, not identical contents, but *equal* contents
        Key leftList = keyListOf(thresholdKeys.get(2), thresholdKeys.get(0), SIMPLE_ED25519[2]);
        Key rightList = keyListOf(thresholdKeys.get(2), thresholdKeys.get(1), SIMPLE_ED25519[2]);
        result.add(Arguments.of("Threshold-in-list-same", zero, leftList, rightList));
        // two lists, not identical, and *not equal* contents
        leftList = keyListOf(thresholdKeys.get(2), thresholdKeys.get(0), SIMPLE_ED25519[2]);
        rightList = keyListOf(thresholdKeys.get(3), thresholdKeys.get(1), SIMPLE_ED25519[2]);
        result.add(Arguments.of("Threshold-in-list-lower-threshold", one, leftList, rightList));
        leftList = keyListOf(thresholdKeys.get(3), thresholdKeys.get(3), SIMPLE_ED25519[2]);
        rightList = keyListOf(thresholdKeys.get(3), thresholdKeys.get(1), SIMPLE_ED25519[2]);
        result.add(Arguments.of("Threshold-in-list-higher-threshold", minusOne, leftList, rightList));
        leftList = keyListOf(thresholdKeys.get(3), thresholdKeys.get(2), SIMPLE_ED25519[2]);
        rightList = keyListOf(thresholdKeys.get(6), thresholdKeys.get(3), SIMPLE_ED25519[2]);
        result.add(Arguments.of("Threshold-in-list-first-difference-counts", minusOne, leftList, rightList));
        leftList = keyListOf(thresholdKeys.get(3), thresholdKeys.get(0), SIMPLE_ED25519[2]);
        rightList = keyListOf(thresholdKeys.get(3), thresholdKeys.get(1), SIMPLE_ED25519[3]);
        result.add(Arguments.of("Threshold-in-list-still-compares-list", minusOne, leftList, rightList));
        leftList = keyListOf(thresholdKeys.get(3), thresholdKeys.get(0), SIMPLE_ED25519[1]);
        rightList = keyListOf(thresholdKeys.get(3), thresholdKeys.get(1), SIMPLE_ED25519[4]);
        result.add(Arguments.of("Threshold-in-list-still-compares-last", one, leftList, rightList));
    }

    private static Key thresholdOf(final int threshold, final KeyList... baseList) {
        final ThresholdKey.Builder builder = ThresholdKey.newBuilder();
        builder.threshold(threshold);
        final List<Key> combinedList = new LinkedList<>();
        for (final KeyList next : baseList) {
            for (final Key singleKey : next.keys()) {
                combinedList.add(singleKey);
            }
        }
        builder.keys(KeyList.newBuilder().keys(combinedList));
        return Key.newBuilder().thresholdKey(builder).build();
    }

    private static void keyListCases(final List<Arguments> result) {
        final String testNameFormat = "KeyList-%s-%s";
        final Integer zero = Integer.valueOf(0);
        final Integer one = Integer.valueOf(1);
        final Integer minusOne = Integer.valueOf(-1);
        final List<NamedKeyList> keyLists = generateKeyLists();
        NamedKeyList nextLeft = keyLists.get(0);
        NamedKeyList nextRight = keyLists.get(1);
        result.add(Arguments.of(nextLeft.name + "-" + nextRight.name, minusOne, nextLeft.key(), nextRight.key()));
        nextLeft = keyLists.get(1);
        nextRight = keyLists.get(2);
        result.add(Arguments.of(nextLeft.name + "-" + nextRight.name, one, nextLeft.key(), nextRight.key()));
        nextLeft = keyLists.get(2);
        nextRight = keyLists.get(3);
        result.add(Arguments.of(nextLeft.name + "-" + nextRight.name, minusOne, nextLeft.key(), nextRight.key()));
        nextLeft = keyLists.get(3);
        nextRight = keyLists.get(4);
        result.add(Arguments.of(nextLeft.name + "-" + nextRight.name, one, nextLeft.key(), nextRight.key()));
        nextLeft = keyLists.get(4);
        nextRight = keyLists.get(5);
        result.add(Arguments.of(nextLeft.name + "-" + nextRight.name, minusOne, nextLeft.key(), nextRight.key()));
        nextLeft = keyLists.get(5);
        nextRight = keyLists.get(6);
        result.add(Arguments.of(nextLeft.name + "-" + nextRight.name, one, nextLeft.key(), nextRight.key()));
        nextLeft = keyLists.get(6);
        nextRight = keyLists.get(7);
        result.add(Arguments.of(nextLeft.name + "-" + nextRight.name, one, nextLeft.key(), nextRight.key()));
        nextLeft = keyLists.get(7);
        nextRight = keyLists.get(8);
        result.add(Arguments.of(nextLeft.name + "-" + nextRight.name, one, nextLeft.key(), nextRight.key()));
    }

    private static List<NamedKeyList> generateKeyLists() {
        final List<NamedKeyList> keys = new LinkedList<>();
        // Create 10 each contract and delegate-contract keys
        // contract num are even and evm address are odd
        final Key[] contractKeys = generateContractKeysForLists();
        final Key[] delegateKeys = generateDelegateKeysForLists();
        final Key[] baseLists = generateBaseKeyLists(contractKeys, delegateKeys);
        final Key[] deepLists = new Key[] {
            keyListOf(baseLists[1], keyListOf(contractKeys[2], baseLists[3], keyListOf(baseLists[6], delegateKeys[8]))),
            keyListOf(SIMPLE_ED25519[3], baseLists[0], baseLists[1])
        };
        keys.add(new NamedKeyList("Flat-List1", baseLists[0]));
        keys.add(new NamedKeyList("Flat-List2", baseLists[1]));
        keys.add(new NamedKeyList("Flat-List3", baseLists[2]));
        keys.add(new NamedKeyList("Flat-List4", baseLists[3]));
        keys.add(new NamedKeyList("Flat-List5", baseLists[4]));
        keys.add(new NamedKeyList("Flat-List6", baseLists[5]));
        keys.add(new NamedKeyList("Flat-List7", baseLists[6]));
        keys.add(new NamedKeyList("TwoLevels-DeepList2", deepLists[1]));
        keys.add(new NamedKeyList("FourLevels-DeepList1", deepLists[0]));
        return keys;
    }

    @NonNull
    private static Key[] generateBaseKeyLists(final Key[] contractKeys, final Key[] delegateKeys) {
        final Key[] baseLists = new Key[] {
            keyListOf(SIMPLE_ECDSA[0], SIMPLE_ED25519[0], contractKeys[0], delegateKeys[0]),
            keyListOf(SIMPLE_ECDSA[2], SIMPLE_ED25519[2], contractKeys[2], delegateKeys[2]),
            keyListOf(SIMPLE_ECDSA[3], SIMPLE_ED25519[3], contractKeys[4], delegateKeys[4]),
            keyListOf(SIMPLE_ECDSA[4], SIMPLE_ED25519[4], contractKeys[6], delegateKeys[6]),
            keyListOf(SIMPLE_ECDSA[0], SIMPLE_ED25519[1], contractKeys[1], delegateKeys[7]),
            keyListOf(SIMPLE_ECDSA[2], SIMPLE_ED25519[4], contractKeys[3], delegateKeys[9]),
            keyListOf(SIMPLE_ECDSA[3], SIMPLE_ED25519[0], contractKeys[5], delegateKeys[4])
        };
        return baseLists;
    }

    @NonNull
    private static Key[] generateDelegateKeysForLists() {
        final Key[] delegateKeys = new Key[SIMPLE_CONTRACT_ID.length * 2];
        for (int i = 0; i < SIMPLE_CONTRACT_ID.length; i++) {
            final int evenIndex = 2 * i;
            final int oddIndex = evenIndex + 1;
            delegateKeys[evenIndex] = modifyId(i, 0, 0, -1, true);
            delegateKeys[oddIndex] = modifyId(i, 0, 0, oddIndex, true);
        }
        return delegateKeys;
    }

    @NonNull
    private static Key[] generateContractKeysForLists() {
        final Key[] contractKeys = new Key[SIMPLE_CONTRACT_ID.length * 2];
        for (int i = 0; i < SIMPLE_CONTRACT_ID.length; i++) {
            final int evenIndex = 2 * i;
            final int oddIndex = evenIndex + 1;
            contractKeys[evenIndex] = modifyId(i, 0, 0, -1, false);
            contractKeys[oddIndex] = modifyId(i, 0, 0, oddIndex, false);
        }
        return contractKeys;
    }

    private static Key keyListOf(final Key... keys) {
        return Key.newBuilder().keyList(KeyList.newBuilder().keys(keys)).build();
    }

    private static void ed25519Cases(final List<Arguments> result) {
        final String testNameFormat = "%s-Key-%d-%d";
        final Integer zero = Integer.valueOf(0);
        final Integer one = Integer.valueOf(1);
        final Integer minusOne = Integer.valueOf(-1);
        // ED25519 sort in byte order
        result.add(Arguments.of(testNameFormat.formatted("ED25519", 0, 0), zero, SIMPLE_ED25519[0], SIMPLE_ED25519[0]));
        result.add(Arguments.of(
                testNameFormat.formatted("ED25519", 0, 1), minusOne, SIMPLE_ED25519[0], SIMPLE_ED25519[1]));
        result.add(Arguments.of(testNameFormat.formatted("ED25519", 0, 2), one, SIMPLE_ED25519[0], SIMPLE_ED25519[2]));
        result.add(Arguments.of(testNameFormat.formatted("ED25519", 0, 3), one, SIMPLE_ED25519[0], SIMPLE_ED25519[3]));
        result.add(Arguments.of(testNameFormat.formatted("ED25519", 0, 4), one, SIMPLE_ED25519[0], SIMPLE_ED25519[4]));
        result.add(Arguments.of(testNameFormat.formatted("ED25519", 1, 1), zero, SIMPLE_ED25519[1], SIMPLE_ED25519[1]));
        result.add(Arguments.of(testNameFormat.formatted("ED25519", 1, 2), one, SIMPLE_ED25519[1], SIMPLE_ED25519[2]));
        result.add(Arguments.of(testNameFormat.formatted("ED25519", 1, 3), one, SIMPLE_ED25519[1], SIMPLE_ED25519[3]));
        result.add(Arguments.of(testNameFormat.formatted("ED25519", 1, 4), one, SIMPLE_ED25519[1], SIMPLE_ED25519[4]));
        result.add(Arguments.of(testNameFormat.formatted("ED25519", 2, 2), zero, SIMPLE_ED25519[2], SIMPLE_ED25519[2]));
        result.add(Arguments.of(
                testNameFormat.formatted("ED25519", 2, 3), minusOne, SIMPLE_ED25519[2], SIMPLE_ED25519[3]));
        result.add(Arguments.of(
                testNameFormat.formatted("ED25519", 2, 4), minusOne, SIMPLE_ED25519[2], SIMPLE_ED25519[4]));
        result.add(Arguments.of(testNameFormat.formatted("ED25519", 3, 3), zero, SIMPLE_ED25519[3], SIMPLE_ED25519[3]));
        result.add(Arguments.of(
                testNameFormat.formatted("ED25519", 3, 4), minusOne, SIMPLE_ED25519[3], SIMPLE_ED25519[4]));
        result.add(Arguments.of(testNameFormat.formatted("ED25519", 4, 4), zero, SIMPLE_ED25519[4], SIMPLE_ED25519[4]));
    }

    private static void SecP256K1Cases(final List<Arguments> result) {
        final String testNameFormat = "%s-Key-%d-%d";
        final Integer zero = Integer.valueOf(0);
        final Integer one = Integer.valueOf(1);
        final Integer minusOne = Integer.valueOf(-1);
        // SecP256K1 sort in byte order
        result.add(Arguments.of(testNameFormat.formatted("SecP256K1", 0, 0), zero, SIMPLE_ECDSA[0], SIMPLE_ECDSA[0]));
        result.add(
                Arguments.of(testNameFormat.formatted("SecP256K1", 0, 1), minusOne, SIMPLE_ECDSA[0], SIMPLE_ECDSA[1]));
        result.add(
                Arguments.of(testNameFormat.formatted("SecP256K1", 0, 2), minusOne, SIMPLE_ECDSA[0], SIMPLE_ECDSA[2]));
        result.add(Arguments.of(testNameFormat.formatted("SecP256K1", 0, 3), one, SIMPLE_ECDSA[0], SIMPLE_ECDSA[3]));
        result.add(
                Arguments.of(testNameFormat.formatted("SecP256K1", 0, 4), minusOne, SIMPLE_ECDSA[0], SIMPLE_ECDSA[4]));
        result.add(Arguments.of(testNameFormat.formatted("SecP256K1", 1, 1), zero, SIMPLE_ECDSA[1], SIMPLE_ECDSA[1]));
        result.add(
                Arguments.of(testNameFormat.formatted("SecP256K1", 1, 2), minusOne, SIMPLE_ECDSA[1], SIMPLE_ECDSA[2]));
        result.add(Arguments.of(testNameFormat.formatted("SecP256K1", 1, 3), one, SIMPLE_ECDSA[1], SIMPLE_ECDSA[3]));
        result.add(
                Arguments.of(testNameFormat.formatted("SecP256K1", 1, 4), minusOne, SIMPLE_ECDSA[1], SIMPLE_ECDSA[4]));
        result.add(Arguments.of(testNameFormat.formatted("SecP256K1", 2, 2), zero, SIMPLE_ECDSA[2], SIMPLE_ECDSA[2]));
        result.add(Arguments.of(testNameFormat.formatted("SecP256K1", 2, 3), one, SIMPLE_ECDSA[2], SIMPLE_ECDSA[3]));
        result.add(
                Arguments.of(testNameFormat.formatted("SecP256K1", 2, 4), minusOne, SIMPLE_ECDSA[2], SIMPLE_ECDSA[4]));
        result.add(Arguments.of(testNameFormat.formatted("SecP256K1", 3, 3), zero, SIMPLE_ECDSA[3], SIMPLE_ECDSA[3]));
        result.add(
                Arguments.of(testNameFormat.formatted("SecP256K1", 3, 4), minusOne, SIMPLE_ECDSA[3], SIMPLE_ECDSA[4]));
        result.add(Arguments.of(testNameFormat.formatted("SecP256K1", 4, 4), zero, SIMPLE_ECDSA[4], SIMPLE_ECDSA[4]));
    }

    private static void contractIdCases(final List<Arguments> result) {
        final Integer zero = Integer.valueOf(0);
        final Integer one = Integer.valueOf(1);
        final Integer minusOne = Integer.valueOf(-1);
        // evm addresses sort in byte order
        addContractId(result, zero, new IdVal("one", 0, 0, 0, -1), new IdVal("one", 0, 0, 0, -1));
        addContractId(result, minusOne, new IdVal("one", 0, 0, 0, -1), new IdVal("two", 1, 0, 0, -1));
        addContractId(result, minusOne, new IdVal("one", 0, 0, 0, -1), new IdVal("three", 2, 0, 0, -1));
        addContractId(result, one, new IdVal("one", 0, 0, 0, -1), new IdVal("four", 3, 0, 0, -1));
        addContractId(result, minusOne, new IdVal("one", 0, 0, 0, -1), new IdVal("five", 4, 0, 0, -1));
        // contract numbers sort numerically
        addContractId(result, minusOne, new IdVal("one[1]", 0, 0, 0, 1), new IdVal("one[2]", 0, 0, 0, 2));
        addContractId(result, one, new IdVal("one[2]", 0, 0, 0, 2), new IdVal("one[1]", 0, 0, 0, 1));
        addContractId(result, one, new IdVal("one[3]", 0, 0, 0, 3), new IdVal("two[1]", 1, 0, 0, 1));
        addContractId(result, minusOne, new IdVal("one[1]", 0, 0, 0, 1), new IdVal("four[3]", 3, 0, 0, 3));
        // Contract number sorts "before" evm address
        addContractId(result, one, new IdVal("one[0]", 0, 0, 0, 0), new IdVal("four", 3, 0, 0, -1));
        addContractId(result, one, new IdVal("one[3]", 0, 0, 0, 3), new IdVal("three", 2, 0, 0, -1));
        addContractId(result, one, new IdVal("one[0]", 0, 0, 0, 0), new IdVal("two", 1, 0, 0, -1));
        addContractId(result, one, new IdVal("two[5]", 0, 0, 0, 5), new IdVal("four", 3, 0, 0, -1));
        addContractId(result, one, new IdVal("three[0]", 0, 0, 0, 0), new IdVal("three", 2, 0, 0, -1));
        addContractId(result, one, new IdVal("four[0]", 0, 0, 0, 0), new IdVal("two", 1, 0, 0, -1));
        // realm compare numeric order
        addContractId(result, one, new IdVal("1.one", 0, 1, 0, -1), new IdVal("0.one", 0, 0, 0, -1));
        addContractId(result, one, new IdVal("1.one", 0, 1, 0, -1), new IdVal("0.two", 1, 0, 0, -1));
        addContractId(result, one, new IdVal("1.one", 0, 1, 0, -1), new IdVal("0.three", 2, 0, 0, -1));
        addContractId(result, one, new IdVal("1.one", 0, 1, 0, -1), new IdVal("0.four", 3, 0, 0, -1));
        addContractId(result, one, new IdVal("1.one", 0, 1, 0, -1), new IdVal("0.five", 4, 0, 0, -1));
        addContractId(result, minusOne, new IdVal("1.one", 0, 1, 0, -1), new IdVal("2.one", 0, 2, 0, -1));
        addContractId(result, minusOne, new IdVal("1.one", 0, 1, 0, -1), new IdVal("2.two", 1, 2, 0, -1));
        addContractId(result, minusOne, new IdVal("1.one", 0, 1, 0, -1), new IdVal("2.three", 2, 2, 0, -1));
        addContractId(result, minusOne, new IdVal("1.one", 0, 1, 0, -1), new IdVal("2.four", 3, 2, 0, -1));
        addContractId(result, minusOne, new IdVal("1.one", 0, 1, 0, -1), new IdVal("2.five", 4, 2, 0, -1));
        // shard compare numeric order
        addContractId(result, one, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.0.one", 0, 0, 0, -1));
        addContractId(result, one, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.0.two", 1, 0, 0, -1));
        addContractId(result, one, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.0.three", 2, 0, 0, -1));
        addContractId(result, one, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.0.four", 3, 0, 0, -1));
        addContractId(result, one, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.0.five", 4, 0, 0, -1));
        addContractId(result, minusOne, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.2.one", 0, 0, 2, -1));
        addContractId(result, minusOne, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.2.two", 1, 0, 2, -1));
        addContractId(result, minusOne, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.2.three", 2, 0, 2, -1));
        addContractId(result, minusOne, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.2.four", 3, 0, 2, -1));
        addContractId(result, minusOne, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.2.five", 4, 0, 2, -1));
    }

    private static void delegateIdCases(final List<Arguments> result) {
        final Integer zero = Integer.valueOf(0);
        final Integer one = Integer.valueOf(1);
        final Integer minusOne = Integer.valueOf(-1);
        // Delegated contract IDs sort like contract IDs
        // evm addresses sort in byte order
        addDelegateId(result, zero, new IdVal("one", 0, 0, 0, -1), new IdVal("one", 0, 0, 0, -1));
        addDelegateId(result, minusOne, new IdVal("one", 0, 0, 0, -1), new IdVal("two", 1, 0, 0, -1));
        addDelegateId(result, minusOne, new IdVal("one", 0, 0, 0, -1), new IdVal("three", 2, 0, 0, -1));
        addDelegateId(result, one, new IdVal("one", 0, 0, 0, -1), new IdVal("four", 3, 0, 0, -1));
        addDelegateId(result, minusOne, new IdVal("one", 0, 0, 0, -1), new IdVal("five", 4, 0, 0, -1));
        // contract numbers sort numerically
        addDelegateId(result, minusOne, new IdVal("one[1]", 0, 0, 0, 1), new IdVal("one[2]", 0, 0, 0, 2));
        addDelegateId(result, one, new IdVal("one[2]", 0, 0, 0, 2), new IdVal("one[1]", 0, 0, 0, 1));
        addDelegateId(result, one, new IdVal("one[3]", 0, 0, 0, 3), new IdVal("two[1]", 1, 0, 0, 1));
        addDelegateId(result, minusOne, new IdVal("one[1]", 0, 0, 0, 1), new IdVal("four[3]", 3, 0, 0, 3));
        // Contract number sorts "before" evm address
        addDelegateId(result, one, new IdVal("one[0]", 0, 0, 0, 0), new IdVal("four", 3, 0, 0, -1));
        addDelegateId(result, one, new IdVal("one[3]", 0, 0, 0, 3), new IdVal("three", 2, 0, 0, -1));
        addDelegateId(result, one, new IdVal("one[0]", 0, 0, 0, 0), new IdVal("two", 1, 0, 0, -1));
        addDelegateId(result, one, new IdVal("two[5]", 0, 0, 0, 5), new IdVal("four", 3, 0, 0, -1));
        addDelegateId(result, one, new IdVal("three[0]", 0, 0, 0, 0), new IdVal("three", 2, 0, 0, -1));
        addDelegateId(result, one, new IdVal("four[0]", 0, 0, 0, 0), new IdVal("two", 1, 0, 0, -1));
        // realm compare numeric order
        addDelegateId(result, one, new IdVal("1.one", 0, 1, 0, -1), new IdVal("0.one", 0, 0, 0, -1));
        addDelegateId(result, one, new IdVal("1.one", 0, 1, 0, -1), new IdVal("0.two", 1, 0, 0, -1));
        addDelegateId(result, one, new IdVal("1.one", 0, 1, 0, -1), new IdVal("0.three", 2, 0, 0, -1));
        addDelegateId(result, one, new IdVal("1.one", 0, 1, 0, -1), new IdVal("0.four", 3, 0, 0, -1));
        addDelegateId(result, one, new IdVal("1.one", 0, 1, 0, -1), new IdVal("0.five", 4, 0, 0, -1));
        addDelegateId(result, minusOne, new IdVal("1.one", 0, 1, 0, -1), new IdVal("2.one", 0, 2, 0, -1));
        addDelegateId(result, minusOne, new IdVal("1.one", 0, 1, 0, -1), new IdVal("2.two", 1, 2, 0, -1));
        addDelegateId(result, minusOne, new IdVal("1.one", 0, 1, 0, -1), new IdVal("2.three", 2, 2, 0, -1));
        addDelegateId(result, minusOne, new IdVal("1.one", 0, 1, 0, -1), new IdVal("2.four", 3, 2, 0, -1));
        addDelegateId(result, minusOne, new IdVal("1.one", 0, 1, 0, -1), new IdVal("2.five", 4, 2, 0, -1));
        // shard compare numeric order
        addDelegateId(result, one, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.0.one", 0, 0, 0, -1));
        addDelegateId(result, one, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.0.two", 1, 0, 0, -1));
        addDelegateId(result, one, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.0.three", 2, 0, 0, -1));
        addDelegateId(result, one, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.0.four", 3, 0, 0, -1));
        addDelegateId(result, one, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.0.five", 4, 0, 0, -1));
        addDelegateId(result, minusOne, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.2.one", 0, 0, 2, -1));
        addDelegateId(result, minusOne, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.2.two", 1, 0, 2, -1));
        addDelegateId(result, minusOne, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.2.three", 2, 0, 2, -1));
        addDelegateId(result, minusOne, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.2.four", 3, 0, 2, -1));
        addDelegateId(result, minusOne, new IdVal("0.1.one", 0, 0, 1, -1), new IdVal("0.2.five", 4, 0, 2, -1));
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                             Supporting methods and record types                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////
    private static record IdVal(String name, int index, int realm, int shard, int num) {}

    private static record NamedKeyList(String name, Key key) {}

    private static void addContractId(
            final List<Arguments> result, final Integer expected, final IdVal left, final IdVal right) {
        final String name = "ContractId %s-%s".formatted(left.name, right.name);
        Key leftKey = modifyId(left.index, left.realm, left.shard, left.num, false);
        Key rightKey = modifyId(right.index, right.realm, right.shard, right.num, false);
        result.add(Arguments.of(name, expected, leftKey, rightKey));
    }

    private static void addDelegateId(
            final List<Arguments> result, final Integer expected, final IdVal left, final IdVal right) {
        final String name = "DelegateContractId %s-%s".formatted(left.name, right.name);
        Key leftKey = modifyId(left.index, left.realm, left.shard, left.num, true);
        Key rightKey = modifyId(right.index, right.realm, right.shard, right.num, true);
        result.add(Arguments.of(name, expected, leftKey, rightKey));
    }

    private static Key modifyId(
            final int index, final long realm, final long shard, final long number, final boolean isDelegate) {
        final ContractID.Builder builder = SIMPLE_CONTRACT_ID[index].copyBuilder();
        builder.realmNum(realm).shardNum(shard);
        if (number >= 0) builder.contractNum(number);
        if (isDelegate) return Key.newBuilder().delegatableContractId(builder).build();
        else return Key.newBuilder().contractID(builder).build();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                   Static data fragments                                    //
    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Test ECDSA SECP256 K1 key hex values
    private static final Bytes[] ECDSA_BYTES = {
        Bytes.fromHex("00badcadfaddad2bedfedbeef959feedbeadcafecadecedebeed4acedecada5ada"),
        Bytes.fromHex("00feedbeadcafe8675309bafedfacecaeddeedcedebede4adaacecab2badcadfad"),
        Bytes.fromHex("550000000000191561942608236107294793378084303638130997321548169216"),
        Bytes.fromHex("009834701927540926570495640961948794713207439248567184729049081327"),
        Bytes.fromHex("ee983470192754092657adacbeef61948794713207439248567184729049081327")
    };
    private static final Key[] SIMPLE_ECDSA = {
        Key.newBuilder().ecdsaSecp256k1(ECDSA_BYTES[0]).build(),
        Key.newBuilder().ecdsaSecp256k1(ECDSA_BYTES[1]).build(),
        Key.newBuilder().ecdsaSecp256k1(ECDSA_BYTES[2]).build(),
        Key.newBuilder().ecdsaSecp256k1(ECDSA_BYTES[3]).build(),
        Key.newBuilder().ecdsaSecp256k1(ECDSA_BYTES[4]).build()
    };

    // Test ED25519 key hex values
    private static final Bytes[] ED25519_BYTES = {
        Bytes.fromHex("badcadfaddad2bedfedbeef959feedbeadcafecadecedebeed4acedecada5ada"),
        Bytes.fromHex("feedbeadcafe8675309bafedfacecaeddeedcedebede4adaacecab2badcadfad"),
        Bytes.fromHex("0000000000191561942608236107294793378084303638130997321548169216"),
        Bytes.fromHex("9834701927540926570495640961948794713207439248567184729049081327"),
        Bytes.fromHex("983470192754092657adacbeef61948794713207439248567184729049081327")
    };
    private static final Key[] SIMPLE_ED25519 = {
        Key.newBuilder().ed25519(ED25519_BYTES[0]).build(),
        Key.newBuilder().ed25519(ED25519_BYTES[1]).build(),
        Key.newBuilder().ed25519(ED25519_BYTES[2]).build(),
        Key.newBuilder().ed25519(ED25519_BYTES[3]).build(),
        Key.newBuilder().ed25519(ED25519_BYTES[4]).build()
    };

    // Contract IDs have evm addresses (like an ECDSA key), but also have shard, realm, and contract number.
    // Here we create the base ID, and construct the permutations in the parameter method.
    private static final ContractID[] SIMPLE_CONTRACT_ID = {
        ContractID.newBuilder().evmAddress(ECDSA_BYTES[0]).build(),
        ContractID.newBuilder().evmAddress(ECDSA_BYTES[1]).build(),
        ContractID.newBuilder().evmAddress(ECDSA_BYTES[2]).build(),
        ContractID.newBuilder().evmAddress(ECDSA_BYTES[3]).build(),
        ContractID.newBuilder().evmAddress(ECDSA_BYTES[4]).build()
    };

    private static final Bytes NON_VALID_BYTES_DATA = Bytes.wrap("Not Valid Data, Test Only.");
    private static final Bytes OTHER_BYTES_DATA = Bytes.wrap("More Invalid Data, Test Only.");
    private static final ThresholdKey.Builder THRESHOLD_KEY_BUILDER =
            ThresholdKey.newBuilder().threshold(2);
    private static final KeyList.Builder KEYLIST_BUILDER = KeyList.newBuilder();
    private static final ContractID.Builder CID_BUILDER =
            ContractID.newBuilder().realmNum(0).shardNum(0);
    private static final ContractID NON_VALID_CID =
            CID_BUILDER.contractNum(1024).evmAddress(NON_VALID_BYTES_DATA).build();

    private static final Key INVALID_RSA_KEY =
            Key.newBuilder().rsa3072(NON_VALID_BYTES_DATA).build();
    private static final Key INVALID_ECDSAP384_KEY =
            Key.newBuilder().ecdsa384(NON_VALID_BYTES_DATA).build();
    private static final Key OTHER_RSA_KEY =
            Key.newBuilder().rsa3072(OTHER_BYTES_DATA).build();
    private static final Key OTHER_ECDSAP384_KEY =
            Key.newBuilder().ecdsa384(OTHER_BYTES_DATA).build();
    private static final Key SAMPLE_CONTRACT_ID =
            Key.newBuilder().contractID(NON_VALID_CID).build();
    private static final Key SAMPLE_DELEGATE_CONTRACT_ID =
            Key.newBuilder().delegatableContractId(NON_VALID_CID).build();

    private static final KeyList ECDSA_KEYLIST = KEYLIST_BUILDER
            .keys(SIMPLE_ECDSA[1], SIMPLE_ECDSA[3], SIMPLE_ECDSA[4])
            .build();
    private static final KeyList ED25519_KEYLIST = KEYLIST_BUILDER
            .keys(SIMPLE_ED25519[1], SIMPLE_ED25519[3], SIMPLE_ED25519[4])
            .build();
    private static final KeyList MIXED_KEYLIST = KEYLIST_BUILDER
            .keys(SIMPLE_ECDSA[2], SIMPLE_ED25519[2], SAMPLE_CONTRACT_ID, INVALID_RSA_KEY)
            .build();
    private static final ThresholdKey ECDSA_THRESHOLD =
            THRESHOLD_KEY_BUILDER.keys(ECDSA_KEYLIST).build();
    private static final ThresholdKey ED25519_THRESHOLD =
            THRESHOLD_KEY_BUILDER.keys(ED25519_KEYLIST).build();
    private static final ThresholdKey MIXED_THRESHOLD =
            THRESHOLD_KEY_BUILDER.keys(MIXED_KEYLIST).build();

    private static final Key ECDSA_THRESHOLD_KEY =
            Key.newBuilder().thresholdKey(ECDSA_THRESHOLD).build();
    private static final Key ED25519_THRESHOLD_KEY =
            Key.newBuilder().thresholdKey(ED25519_THRESHOLD).build();
    private static final Key MIXED_THRESHOLD_KEY =
            Key.newBuilder().thresholdKey(MIXED_THRESHOLD).build();
    private static final Key MIXED_KEY_LIST_KEY =
            Key.newBuilder().keyList(MIXED_KEYLIST).build();
    private static final KeyList COMPLEX_KEYLIST = KEYLIST_BUILDER
            .keys(SAMPLE_CONTRACT_ID, ED25519_THRESHOLD_KEY, MIXED_KEY_LIST_KEY, SIMPLE_ED25519[1])
            .build();
    private static final Key COMPLEX_KEY =
            Key.newBuilder().keyList(COMPLEX_KEYLIST).build();
}
