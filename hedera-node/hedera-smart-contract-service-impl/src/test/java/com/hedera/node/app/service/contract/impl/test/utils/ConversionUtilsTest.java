// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.utils;

import static com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaOperations.ZERO_ENTROPY;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.NON_CANONICAL_REFERENCE_NUMBER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ALIASED_SOMEBODY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.BESU_LOG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALL_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.INVALID_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.LONG_ZERO_ADDRESS_BYTES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.LONG_ZERO_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_LONG_ZERO_ADDRESS_BYTES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOMEBODY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOME_STORAGE_ACCESSES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.TOPIC;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.VALID_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.realm;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.shard;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.accountNumberForEvmReference;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asExactLongValueOrZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asNumberedContractId;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.contractIDToBesuAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.contractIDToNum;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZeroAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjLogFrom;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjLogsFrom;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToBesuAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractLoginfo;
import com.hedera.hapi.streams.ContractStateChange;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.hapi.streams.StorageChange;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.log.LogsBloomFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversionUtilsTest {

    @Mock
    private HederaNativeOperations nativeOperations;

    private static final Configuration configuration = HederaTestConfigBuilder.createConfig();

    @Test
    void outOfRangeBiValuesAreZero() {
        assertEquals(
                0L, asExactLongValueOrZero(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
        assertEquals(
                0L, asExactLongValueOrZero(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)));
    }

    @Test
    void besuAddressIsZeroForDefaultContractId() {
        assertEquals(Address.ZERO, contractIDToBesuAddress(entityIdFactory, ContractID.DEFAULT));
    }

    @Test
    void inRangeBiValuesAreExact() {
        assertEquals(Long.MAX_VALUE, asExactLongValueOrZero(BigInteger.valueOf(Long.MAX_VALUE)));
        assertEquals(Long.MIN_VALUE, asExactLongValueOrZero(BigInteger.valueOf(Long.MIN_VALUE)));
    }

    @Test
    void numberedIdsRequireLongZeroAddress() {
        assertThrows(IllegalArgumentException.class, () -> asNumberedContractId(entityIdFactory, EIP_1014_ADDRESS));
    }

    @Test
    void wrapsExpectedHashPrefix() {
        assertEquals(Bytes32.leftPad(Bytes.EMPTY, (byte) 0), ConversionUtils.ethHashFrom(ZERO_ENTROPY));
    }

    @Test
    void convertsNumberToLongZeroAddress() {
        final var number = 0x1234L;
        final var expected = Address.fromHexString("0x1234");
        final var actual = ConversionUtils.asLongZeroAddress(entityIdFactory, number);
        assertEquals(expected, actual);
    }

    @Test
    void justReturnsNumberFromSmallLongZeroAddress() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var smallNumber = 0x1234L;
        final var address = Address.fromHexString("0x1234");
        final var actual = ConversionUtils.maybeMissingNumberOf(address, nativeOperations);
        assertEquals(smallNumber, actual);
    }

    @Test
    void returnsMissingIfSmallLongZeroAddressIsMissing() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var address = asHeadlongAddress(Address.fromHexString("0x1234").toArray());
        final var actual = accountNumberForEvmReference(address, nativeOperations);
        assertEquals(MISSING_ENTITY_NUMBER, actual);
    }

    @Test
    void returnsNumberIfSmallLongZeroAddressIsPresent() {
        final long number = A_NEW_ACCOUNT_ID.accountNumOrThrow();
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(SOMEBODY);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var address = asHeadlongAddress(asEvmAddress(shard, realm, number));
        final var actual = accountNumberForEvmReference(address, nativeOperations);
        assertEquals(number, actual);
    }

    @Test
    void returnsNonCanonicalRefIfSmallLongZeroAddressRefersToAliasedAccount() {
        final var address = asHeadlongAddress(Address.fromHexString("0x1234").toArray());
        given(nativeOperations.getAccount(any(AccountID.class))).willReturn(ALIASED_SOMEBODY);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var actual = accountNumberForEvmReference(address, nativeOperations);
        assertEquals(NON_CANONICAL_REFERENCE_NUMBER, actual);
    }

    @Test
    void justReturnsNumberFromLargeLongZeroAddress() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var largeNumber = 0x7fffffffffffffffL;
        final var address = Address.fromHexString("0x7fffffffffffffff");
        final var actual = ConversionUtils.maybeMissingNumberOf(address, nativeOperations);
        assertEquals(largeNumber, actual);
    }

    @Test
    void returnsMissingOnAbsentAlias() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(configuration);
        final var address = Address.fromHexString("0x010000000000000000");
        given(nativeOperations.resolveAlias(anyLong(), anyLong(), any())).willReturn(MISSING_ENTITY_NUMBER);
        final var actual = ConversionUtils.maybeMissingNumberOf(address, nativeOperations);
        assertEquals(-1L, actual);
    }

    @Test
    void returnsMissingOnAbsentAliasReference() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(configuration);
        final var address =
                asHeadlongAddress(Address.fromHexString("0x010000000000000000").toArray());
        given(nativeOperations.resolveAlias(anyLong(), anyLong(), any())).willReturn(MISSING_ENTITY_NUMBER);
        final var actual = ConversionUtils.accountNumberForEvmReference(address, nativeOperations);
        assertEquals(-1L, actual);
    }

    @Test
    void returnsGivenIfPresentAlias() {
        given(nativeOperations.resolveAlias(anyLong(), anyLong(), any())).willReturn(0x1234L);
        given(nativeOperations.configuration()).willReturn(configuration);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var address = Address.fromHexString("0x010000000000000000");
        final var actual = ConversionUtils.maybeMissingNumberOf(address, nativeOperations);
        assertEquals(0x1234L, actual);
    }

    @Test
    void convertsFromBesuLogAsExpected() {
        final var expectedBloom = Bytes.wrap(bloomFor());
        final var expected = ContractLoginfo.newBuilder()
                .contractID(ContractID.newBuilder().contractNum(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .bloom(tuweniToPbjBytes(expectedBloom))
                .data(CALL_DATA)
                .topic(List.of(TOPIC))
                .build();

        final var actual = pbjLogFrom(entityIdFactory, BESU_LOG);

        assertEquals(expected, actual);
    }

    @Test
    void convertsFromBesuLogsAsExpected() {
        final var expectedBloom = Bytes.wrap(bloomFor());
        final var expected = ContractLoginfo.newBuilder()
                .contractID(ContractID.newBuilder().contractNum(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .bloom(tuweniToPbjBytes(expectedBloom))
                .data(CALL_DATA)
                .topic(List.of(TOPIC))
                .build();

        final var actual = pbjLogsFrom(entityIdFactory, List.of(BESU_LOG));

        assertEquals(List.of(expected), actual);
    }

    @Test
    void convertsFromStorageAccessesAsExpected() {
        final var expectedPbj = ContractStateChanges.newBuilder()
                .contractStateChanges(
                        ContractStateChange.newBuilder()
                                .contractId(ContractID.newBuilder().contractNum(123L))
                                .storageChanges(new StorageChange(
                                        tuweniToPbjBytes(UInt256.MIN_VALUE.trimLeadingZeros()),
                                        tuweniToPbjBytes(UInt256.MAX_VALUE.trimLeadingZeros()),
                                        null))
                                .build(),
                        ContractStateChange.newBuilder()
                                .contractId(ContractID.newBuilder().contractNum(456L))
                                .storageChanges(
                                        new StorageChange(
                                                tuweniToPbjBytes(UInt256.MAX_VALUE.trimLeadingZeros()),
                                                tuweniToPbjBytes(UInt256.MIN_VALUE.trimLeadingZeros()),
                                                null),
                                        new StorageChange(
                                                tuweniToPbjBytes(UInt256.ONE.trimLeadingZeros()),
                                                tuweniToPbjBytes(UInt256.MIN_VALUE.trimLeadingZeros()),
                                                tuweniToPbjBytes(UInt256.MAX_VALUE.trimLeadingZeros())))
                                .build())
                .build();
        final var actualPbj = ConversionUtils.asPbjStateChanges(SOME_STORAGE_ACCESSES);
        assertEquals(expectedPbj, actualPbj);
    }

    @Test
    void convertContractIdToBesuAddressTest() {
        final var actual = ConversionUtils.contractIDToBesuAddress(entityIdFactory, CALLED_CONTRACT_ID);
        assertEquals(
                actual, asLongZeroAddress(entityIdFactory, Objects.requireNonNull(CALLED_CONTRACT_ID.contractNum())));

        final var actual2 = ConversionUtils.contractIDToBesuAddress(entityIdFactory, VALID_CONTRACT_ADDRESS);
        assertEquals(actual2, pbjToBesuAddress(Objects.requireNonNull(VALID_CONTRACT_ADDRESS.evmAddress())));
    }

    @Test
    void selfManagedCustomizedCreationTest() {
        final var op = ContractCreateTransactionBody.DEFAULT;
        final long newContractNum = 1005L;
        final var actual = ConversionUtils.selfManagedCustomizedCreation(
                op, ContractID.newBuilder().contractNum(newContractNum).build());
        assertTrue(actual.adminKey().hasContractID());
        assertEquals(
                newContractNum,
                actual.adminKey().contractIDOrElse(ContractID.DEFAULT).contractNum());
    }

    @Test
    void evmAddressConversionTest() {
        final long shard = 1L;
        final long realm = 2L;
        final long num = 3L;
        final byte[] expected = new byte[20];
        System.arraycopy(Ints.toByteArray((int) shard), 0, expected, 0, 4);
        System.arraycopy(Longs.toByteArray(realm), 0, expected, 4, 8);
        System.arraycopy(Longs.toByteArray(num), 0, expected, 12, 8);

        final byte[] actual = asEvmAddress(shard, realm, num);

        assertArrayEquals(expected, actual, "EVM address is not as expected");
    }

    @Test
    void isLongZeroAddressTest() {
        assertThat(isLongZeroAddress(entityIdFactory, LONG_ZERO_ADDRESS_BYTES.toByteArray()))
                .isTrue();
    }

    @Test
    void isLongZeroAddressWrongTest() {
        assertThat(isLongZeroAddress(entityIdFactory, NON_LONG_ZERO_ADDRESS_BYTES.toByteArray()))
                .isFalse();
    }

    @Test
    void evmContractIDToNumTest() {
        assertThat(contractIDToNum(entityIdFactory, LONG_ZERO_CONTRACT_ID)).isEqualTo(291);
    }

    @Test
    void evmContractIDToNumZeroTest() {
        assertThat(contractIDToNum(entityIdFactory, INVALID_CONTRACT_ADDRESS)).isEqualTo(0);
    }

    @Test
    void evmContractIDToNumNonLongZeroTest() {
        assertThat(contractIDToNum(entityIdFactory, VALID_CONTRACT_ADDRESS)).isEqualTo(0);
    }

    @Test
    void asTokenIdWithZeros() {
        final var address = com.esaulpaugh.headlong.abi.Address.wrap("0x0000000000000000000000000000000000000000");

        var tokenId = ConversionUtils.asTokenId(address);

        assertEquals(0, tokenId.shardNum());
        assertEquals(0, tokenId.realmNum());
        assertEquals(0, tokenId.tokenNum());
    }

    @Test
    void asTokenId() {
        final var address = com.esaulpaugh.headlong.abi.Address.wrap("0x0000000500000000000000060000000000000007");

        var tokenId = ConversionUtils.asTokenId(address);

        assertEquals(5, tokenId.shardNum());
        assertEquals(6, tokenId.realmNum());
        assertEquals(7, tokenId.tokenNum());
    }

    private static Stream<Arguments> asTokenIdWithNegativeValuesProvideParameters() {
        return Stream.of(
                Arguments.of("0xFFFFffff00000000000000060000000000000007", "Shard is negative"),
                Arguments.of("0x00000005FfffffFFfffFfFFF0000000000000007", "Realm is negative"),
                Arguments.of("0x000000050000000000000006ffFFFFfFFffFFFff", "Number is negative"));
    }

    private byte[] bloomFor() {
        return LogsBloomFilter.builder().insertLog(BESU_LOG).build().toArray();
    }
}
