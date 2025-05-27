// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromByteString;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.suites.utils.sysfiles.BookEntryPojo.asOctets;
import static java.lang.System.arraycopy;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
import com.hedera.node.config.converter.LongPairConverter;
import com.hedera.node.config.types.LongPair;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.props.JutilPropertySource;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.EntityNumber;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.RealmID;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.ShardID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TopicID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.hiero.base.utility.CommonUtils;

public interface HapiPropertySource {
    HapiPropertySource defaultSource = initializeDefaultSource();
    String NODE_BLOCK_STREAM_DIR = String.format("block-%d.%d.3", getSpecDefaultShard(), getSpecDefaultRealm());
    String NODE_RECORD_STREAM_DIR = String.format("record%d.%d.3", getSpecDefaultShard(), getSpecDefaultRealm());

    private static HapiPropertySource initializeDefaultSource() {
        return new JutilPropertySource("spec-default.properties");
    }

    static byte[] explicitBytesOf(@NonNull final Address address) {
        var asBytes = address.value().toByteArray();
        // Might have a leading zero byte to make it positive
        if (asBytes.length == 21) {
            asBytes = Arrays.copyOfRange(asBytes, 1, 21);
        }
        return asBytes;
    }

    String get(String property);

    boolean has(String property);

    static HapiPropertySource inPriorityOrder(HapiPropertySource... sources) {
        if (sources.length == 1) {
            return sources[0];
        } else {
            HapiPropertySource overrides = sources[0];
            HapiPropertySource defaults = inPriorityOrder(Arrays.copyOfRange(sources, 1, sources.length));

            return new HapiPropertySource() {
                @Override
                public String get(String property) {
                    return overrides.has(property) ? overrides.get(property) : defaults.get(property);
                }

                @Override
                public boolean has(String property) {
                    return overrides.has(property) || defaults.has(property);
                }
            };
        }
    }

    default HapiSpec.UTF8Mode getUTF8Mode(String property) {
        return HapiSpec.UTF8Mode.valueOf(get(property));
    }

    default FileID getFile(String property) {
        try {
            return asFile(getShard(), getRealm(), Long.parseLong(get(property)));
        } catch (Exception ignore) {
        }
        return FileID.getDefaultInstance();
    }

    default AccountID getAccount(String property) {
        final var value = get(property);

        if (value.matches("\\d+\\.\\d+\\.\\d+")) {
            try {
                var parts = value.split("\\.");
                return asAccount(parts[0], parts[1], parts[2]);
            } catch (Exception ignore) {
            }
        }

        try {
            return asAccount(getShard(), getRealm(), Long.parseLong(value));
        } catch (Exception ignore) {
        }

        return AccountID.getDefaultInstance();
    }

    /**
     * Returns an {@link StreamMode} parsed from the given property.
     * @param property the property to get the value from
     * @return the {@link StreamMode} value
     */
    default StreamMode getStreamMode(@NonNull final String property) {
        requireNonNull(property);
        return StreamMode.valueOf(get(property));
    }

    default ServiceEndpoint getServiceEndpoint(String property) {
        try {
            return asServiceEndpoint(get(property));
        } catch (Exception ignore) {
            System.out.println("Unable to parse service endpoint from property: " + property);
        }
        return ServiceEndpoint.DEFAULT;
    }

    default ContractID getContract(String property) {
        try {
            return asContract(get(property));
        } catch (Exception ignore) {
        }
        return ContractID.getDefaultInstance();
    }

    default long getRealm() {
        return Optional.ofNullable(get("hapi.spec.default.realm"))
                .map(Long::parseLong)
                .orElse(getSpecDefaultRealm());
    }

    default long getShard() {
        return Optional.ofNullable(get("hapi.spec.default.shard"))
                .map(Long::parseLong)
                .orElse(getSpecDefaultShard());
    }

    static long getConfigShard() {
        return Optional.ofNullable(System.getProperty("hapi.spec.default.shard"))
                .map(Long::parseLong)
                .orElse(getSpecDefaultShard());
    }

    static long getConfigRealm() {
        return Optional.ofNullable(System.getProperty("hapi.spec.default.realm"))
                .map(Long::parseLong)
                .orElse(getSpecDefaultRealm());
    }

    private static long getSpecDefaultShard() {
        return Integer.parseInt(
                Optional.ofNullable(defaultSource.get("default.shard")).orElse("0"));
    }

    private static long getSpecDefaultRealm() {
        return Long.parseLong(
                Optional.ofNullable(defaultSource.get("default.realm")).orElse("0"));
    }

    default TimeUnit getTimeUnit(String property) {
        return TimeUnit.valueOf(get(property));
    }

    default ScaleFactor getScaleFactor(@NonNull final String property) {
        requireNonNull(property);
        return ScaleFactor.from(get(property));
    }

    default double getDouble(String property) {
        return Double.parseDouble(get(property));
    }

    default long getLong(String property) {
        return Long.parseLong(get(property));
    }

    /**
     * Returns a {@link LongPair} from the given property.
     * @param property the property to get the value from
     * @return the {@link LongPair} value
     */
    default LongPair getLongPair(@NonNull final String property) {
        return new LongPairConverter().convert(get(property));
    }

    default HapiSpecSetup.TlsConfig getTlsConfig(String property) {
        return HapiSpecSetup.TlsConfig.valueOf(get(property).toUpperCase());
    }

    default HapiSpecSetup.TxnProtoStructure getTxnConfig(String property) {
        return HapiSpecSetup.TxnProtoStructure.valueOf(get(property).toUpperCase());
    }

    default HapiSpecSetup.NodeSelection getNodeSelector(String property) {
        return HapiSpecSetup.NodeSelection.valueOf(get(property).toUpperCase());
    }

    default int getInteger(String property) {
        return Integer.parseInt(get(property));
    }

    default Duration getDurationFromSecs(String property) {
        return Duration.newBuilder().setSeconds(getInteger(property)).build();
    }

    default boolean getBoolean(String property) {
        return Boolean.parseBoolean(get(property));
    }

    default byte[] getBytes(String property) {
        return get(property).getBytes();
    }

    default KeyFactory.KeyType getKeyType(String property) {
        return KeyFactory.KeyType.valueOf(get(property));
    }

    default SigControl.KeyAlgo getKeyAlgorithm(String property) {
        return SigControl.KeyAlgo.valueOf(get(property));
    }

    default HapiSpec.SpecStatus getSpecStatus(String property) {
        return HapiSpec.SpecStatus.valueOf(get(property));
    }

    static HapiPropertySource[] asSources(Object... sources) {
        return Stream.of(sources)
                .filter(Objects::nonNull)
                .map(HapiPropertySource::toHapiPropertySource)
                .toArray(HapiPropertySource[]::new);
    }

    static TokenID asToken(String v) {
        long[] nativeParts = asDotDelimitedLongArray(v);
        return TokenID.newBuilder()
                .setShardNum(nativeParts[0])
                .setRealmNum(nativeParts[1])
                .setTokenNum(nativeParts[2])
                .build();
    }

    static String asTokenString(TokenID token) {
        return asEntityString(token.getShardNum(), token.getRealmNum(), token.getTokenNum());
    }

    static AccountID asAccount(String v) {
        long[] nativeParts = asDotDelimitedLongArray(v);
        return AccountID.newBuilder()
                .setShardNum(nativeParts[0])
                .setRealmNum(nativeParts[1])
                .setAccountNum(nativeParts[2])
                .build();
    }

    static AccountID asAccount(long shard, long realm, String num) {
        return asAccount(shard, realm, Long.parseLong(num));
    }

    static AccountID asAccount(String shard, String realm, String num) {
        return asAccount(Long.parseLong(shard), Long.parseLong(realm), Long.parseLong(num));
    }

    static AccountID asAccount(long shard, long realm, long num) {
        return AccountID.newBuilder()
                .setShardNum(shard)
                .setRealmNum(realm)
                .setAccountNum(num)
                .build();
    }

    static AccountID asAccount(final HapiSpec spec, long num) {
        return asAccount(spec.shard(), spec.realm(), num);
    }

    static AccountID asAccount(final HapiSpec spec, ByteString alias) {
        return AccountID.newBuilder()
                .setShardNum(spec.shard())
                .setRealmNum(spec.realm())
                .setAlias(alias)
                .build();
    }

    static ContractID asContract(String shard, String realm, String num) {
        return asContract(Long.parseLong(shard), Long.parseLong(realm), Long.parseLong(num));
    }

    static ContractID asContract(long shard, long realm, long num) {
        return ContractID.newBuilder()
                .setShardNum(shard)
                .setRealmNum(realm)
                .setContractNum(num)
                .build();
    }

    static ContractID asContract(AccountID accountID) {
        return ContractID.newBuilder()
                .setShardNum(accountID.getShardNum())
                .setRealmNum(accountID.getRealmNum())
                .setContractNum(accountID.getAccountNum())
                .build();
    }

    static FileID asFile(String shard, String realm, String num) {
        return asFile(Long.parseLong(shard), Long.parseLong(realm), Long.parseLong(num));
    }

    static FileID asFile(long shard, long realm, long num) {
        return FileID.newBuilder()
                .setShardNum(shard)
                .setRealmNum(realm)
                .setFileNum(num)
                .build();
    }

    static ScheduleID asSchedule(String shard, String realm, String num) {
        return ScheduleID.newBuilder()
                .setShardNum(Long.parseLong(shard))
                .setRealmNum(Long.parseLong(realm))
                .setScheduleNum(Long.parseLong(num))
                .build();
    }

    static TokenID asToken(String shard, String realm, String num) {
        return asToken(Long.parseLong(shard), Long.parseLong(realm), Long.parseLong(num));
    }

    static TokenID asToken(long shard, long realm, long num) {
        return TokenID.newBuilder()
                .setShardNum(shard)
                .setRealmNum(realm)
                .setTokenNum(num)
                .build();
    }

    static TopicID asTopic(String shard, String realm, String num) {
        return TopicID.newBuilder()
                .setShardNum(Long.parseLong(shard))
                .setRealmNum(Long.parseLong(realm))
                .setTopicNum(Long.parseLong(num))
                .build();
    }

    static AccountID asAccount(ByteString v) {
        return AccountID.newBuilder().setAlias(v).build();
    }

    static String asAccountString(AccountID account) {
        return asEntityString(account.getShardNum(), account.getRealmNum(), account.getAccountNum());
    }

    static String asAliasableAccountString(final AccountID account) {
        if (account.getAlias().isEmpty()) {
            return asAccountString(account);
        } else {
            final var literalAlias = account.getAlias().toString();
            return asEntityString(account.getShardNum(), account.getRealmNum(), literalAlias);
        }
    }

    static TopicID asTopic(long shard, long realm, long num) {
        return TopicID.newBuilder()
                .setShardNum(shard)
                .setRealmNum(realm)
                .setTopicNum(num)
                .build();
    }

    static TopicID asTopic(String v) {
        long[] nativeParts = asDotDelimitedLongArray(v);
        return asTopic(nativeParts[0], nativeParts[1], nativeParts[2]);
    }

    static String asTopicString(TopicID topic) {
        return asEntityString(topic.getShardNum(), topic.getRealmNum(), topic.getTopicNum());
    }

    /**
     * Interprets the given string as a comma-separated list of {@code {<IP>|<DNS>}:{<PORT>}} pairs, returning a list
     * of {@link ServiceEndpoint} instances with the appropriate host references set.
     * @param v the string to interpret
     * @return the parsed list of {@link ServiceEndpoint} instances
     */
    static List<ServiceEndpoint> asCsServiceEndpoints(@NonNull final String v) {
        requireNonNull(v);
        return Stream.of(v.split(","))
                .map(HapiPropertySource::asTypedServiceEndpoint)
                .toList();
    }

    /**
     * Interprets the given string as a {@code {<IP>|<DNS>}:{<PORT>}} pair, returning an {@link ServiceEndpoint}
     * with the appropriate host reference set.
     * @param v the string to interpret
     * @return the parsed {@link ServiceEndpoint}
     */
    static ServiceEndpoint asTypedServiceEndpoint(@NonNull final String v) {
        requireNonNull(v);
        try {
            return asServiceEndpoint(v);
        } catch (Exception ignore) {
            return asDnsServiceEndpoint(v);
        }
    }

    /**
     * Converts the given {@link Bytes} instance to a readable IPv4 address string.
     * @param ipV4Addr the {@link Bytes} instance to convert
     * @return the readable IPv4 address string
     */
    static String asReadableIp(@NonNull final Bytes ipV4Addr) {
        requireNonNull(ipV4Addr);
        final var bytes = ipV4Addr.toByteArray();
        return (0xff & bytes[0]) + "." + (0xff & bytes[1]) + "." + (0xff & bytes[2]) + "." + (0xff & bytes[3]);
    }

    static ServiceEndpoint asServiceEndpoint(String v) {
        String[] parts = v.split(":");
        return ServiceEndpoint.newBuilder()
                .ipAddressV4(fromByteString(asOctets(parts[0])))
                .port(Integer.parseInt(parts[1]))
                .build();
    }

    static ServiceEndpoint invalidServiceEndpoint() {
        return ServiceEndpoint.newBuilder()
                .ipAddressV4(Bytes.wrap(new byte[3]))
                .port(33)
                .build();
    }

    static ServiceEndpoint asDnsServiceEndpoint(String v) {
        String[] parts = v.split(":");
        return ServiceEndpoint.newBuilder()
                .domainName(parts[0])
                .port(Integer.parseInt(parts[1]))
                .build();
    }

    static ContractID asContract(String v) {
        long[] nativeParts = asDotDelimitedLongArray(v);
        return ContractID.newBuilder()
                .setShardNum(nativeParts[0])
                .setRealmNum(nativeParts[1])
                .setContractNum(nativeParts[2])
                .build();
    }

    static ContractID asContractIdWithEvmAddress(ByteString address) {
        return ContractID.newBuilder()
                .setShardNum(getConfigShard())
                .setRealmNum(getConfigRealm())
                .setEvmAddress(address)
                .build();
    }

    static String asContractString(ContractID contract) {
        return asEntityString(contract.getShardNum(), contract.getRealmNum(), contract.getContractNum());
    }

    static ScheduleID asSchedule(String v) {
        long[] nativeParts = asDotDelimitedLongArray(v);
        return ScheduleID.newBuilder()
                .setShardNum(nativeParts[0])
                .setRealmNum(nativeParts[1])
                .setScheduleNum(nativeParts[2])
                .build();
    }

    static String asScheduleString(ScheduleID schedule) {
        return asEntityString(schedule.getShardNum(), schedule.getRealmNum(), schedule.getScheduleNum());
    }

    static FileID asFile(String v) {
        long[] nativeParts = asDotDelimitedLongArray(v);
        return asFile(nativeParts[0], nativeParts[1], nativeParts[2]);
    }

    static EntityNumber asEntityNumber(String v) {
        return EntityNumber.newBuilder().setNumber(Long.parseLong(v)).build();
    }

    static String asFileString(FileID file) {
        return asEntityString(file.getShardNum(), file.getRealmNum(), file.getFileNum());
    }

    static long[] asDotDelimitedLongArray(String s) {
        String[] parts = s.split("[.]");
        return Stream.of(parts).mapToLong(Long::valueOf).toArray();
    }

    static ShardID asShard(long v) {
        return ShardID.newBuilder().setShardNum(v).build();
    }

    static RealmID asRealm(long v) {
        return RealmID.newBuilder().setRealmNum(v).build();
    }

    static byte[] asSolidityAddress(final AccountID accountId) {
        return asSolidityAddress((int) accountId.getShardNum(), accountId.getRealmNum(), accountId.getAccountNum());
    }

    static Address numAsHeadlongAddress(HapiSpec spec, final long num) {
        return idAsHeadlongAddress(AccountID.newBuilder()
                .setShardNum(spec.shard())
                .setRealmNum(spec.realm())
                .setAccountNum(num)
                .build());
    }

    static Address idAsHeadlongAddress(final AccountID accountId) {
        return asHeadlongAddress(
                asSolidityAddress((int) accountId.getShardNum(), accountId.getRealmNum(), accountId.getAccountNum()));
    }

    static Address idAsHeadlongAddress(final TokenID tokenId) {
        return asHeadlongAddress(
                asSolidityAddress((int) tokenId.getShardNum(), tokenId.getRealmNum(), tokenId.getTokenNum()));
    }

    static String asHexedSolidityAddress(final HapiSpec spec, final long num) {
        return CommonUtils.hex(asSolidityAddress(spec, num));
    }

    static String asHexedSolidityAddress(final AccountID accountId) {
        return CommonUtils.hex(asSolidityAddress(accountId));
    }

    static String asHexedSolidityAddress(final ContractID contractId) {
        return CommonUtils.hex(asSolidityAddress(contractId));
    }

    static String asHexedSolidityAddress(final TokenID tokenId) {
        return CommonUtils.hex(asSolidityAddress(tokenId));
    }

    static byte[] asSolidityAddress(final ContractID contractId) {
        return asSolidityAddress((int) contractId.getShardNum(), contractId.getRealmNum(), contractId.getContractNum());
    }

    static byte[] asSolidityAddress(final TokenID tokenId) {
        return asSolidityAddress((int) tokenId.getShardNum(), tokenId.getRealmNum(), tokenId.getTokenNum());
    }

    static byte[] asSolidityAddress(final int shard, final long realm, final long num) {
        final byte[] solidityAddress = new byte[20];

        arraycopy(Ints.toByteArray(shard), 0, solidityAddress, 0, 4);
        arraycopy(Longs.toByteArray(realm), 0, solidityAddress, 4, 8);
        arraycopy(Longs.toByteArray(num), 0, solidityAddress, 12, 8);

        return solidityAddress;
    }

    static byte[] asSolidityAddress(HapiSpec spec, final long num) {
        return HapiPropertySource.asSolidityAddress((int) spec.shard(), spec.realm(), num);
    }

    static String asHexedSolidityAddress(final int shard, final long realm, final long num) {
        return CommonUtils.hex(asSolidityAddress(shard, realm, num));
    }

    static ContractID contractIdFromHexedMirrorAddress(final String hexedEvm) {
        byte[] unhex = CommonUtils.unhex(hexedEvm);
        return ContractID.newBuilder()
                .setShardNum(Ints.fromByteArray(Arrays.copyOfRange(requireNonNull(unhex), 0, 4)))
                .setRealmNum(Longs.fromByteArray(Arrays.copyOfRange(unhex, 4, 12)))
                .setContractNum(Longs.fromByteArray(Arrays.copyOfRange(unhex, 12, 20)))
                .build();
    }

    static AccountID accountIdFromHexedMirrorAddress(final String hexedEvm) {
        byte[] unhex = CommonUtils.unhex(hexedEvm);
        return AccountID.newBuilder()
                .setShardNum(Ints.fromByteArray(Arrays.copyOfRange(requireNonNull(unhex), 0, 4)))
                .setRealmNum(Longs.fromByteArray(Arrays.copyOfRange(unhex, 4, 12)))
                .setAccountNum(Longs.fromByteArray(Arrays.copyOfRange(unhex, 12, 20)))
                .build();
    }

    static String literalIdFromHexedMirrorAddress(final String hexedEvm) {
        byte[] unhex = CommonUtils.unhex(hexedEvm);
        return HapiPropertySource.asContractString(ContractID.newBuilder()
                .setShardNum(Ints.fromByteArray(Arrays.copyOfRange(requireNonNull(unhex), 0, 4)))
                .setRealmNum(Longs.fromByteArray(Arrays.copyOfRange(unhex, 4, 12)))
                .setContractNum(Longs.fromByteArray(Arrays.copyOfRange(unhex, 12, 20)))
                .build());
    }

    static String asEntityString(final long shard, final long realm, final long num) {
        return String.format("%d.%d.%d", shard, realm, num);
    }

    static String asEntityString(final long shard, final long realm, final String num) {
        return String.format("%d.%d.%s", shard, realm, num);
    }

    static String asEntityString(final String shard, final String realm, final String num) {
        return String.format("%s.%s.%s", shard, realm, num);
    }

    static String asEntityString(final AccountID id) {
        return asEntityString(id.getShardNum(), id.getRealmNum(), id.getAccountNum());
    }

    static long numberOfLongZero(@NonNull final byte[] explicit) {
        return longFrom(
                explicit[12],
                explicit[13],
                explicit[14],
                explicit[15],
                explicit[16],
                explicit[17],
                explicit[18],
                explicit[19]);
    }

    private static HapiPropertySource toHapiPropertySource(Object s) {
        if (s instanceof HapiPropertySource hps) {
            return hps;
        }
        if (s instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, String> typedMap = (Map<String, String>) map;
            return new MapPropertySource(typedMap);
        }
        if (s instanceof String str) {
            return new JutilPropertySource(str);
        }
        throw new IllegalArgumentException("Unsupported source type: " + s.getClass());
    }

    private static long longFrom(
            final byte b1,
            final byte b2,
            final byte b3,
            final byte b4,
            final byte b5,
            final byte b6,
            final byte b7,
            final byte b8) {
        return (b1 & 0xFFL) << 56
                | (b2 & 0xFFL) << 48
                | (b3 & 0xFFL) << 40
                | (b4 & 0xFFL) << 32
                | (b5 & 0xFFL) << 24
                | (b6 & 0xFFL) << 16
                | (b7 & 0xFFL) << 8
                | (b8 & 0xFFL);
    }
}
