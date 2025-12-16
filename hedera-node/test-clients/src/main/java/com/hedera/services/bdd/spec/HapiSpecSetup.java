// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec;

import static com.hedera.node.app.hapi.utils.keys.KeyUtils.readUnknownTypeKeyFrom;
import static com.hedera.node.app.hapi.utils.keys.Secp256k1Utils.readECKeyFrom;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiPropertySource.asSources;
import static com.hedera.services.bdd.spec.HapiPropertySource.inPriorityOrder;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType;
import static com.hedera.services.bdd.spec.keys.deterministic.Bip0032.mnemonicToEd25519Key;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.bytecodePath;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.node.app.hapi.utils.keys.Secp256k1Utils;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.keys.deterministic.Bip0032;
import com.hedera.services.bdd.spec.props.JutilPropertySource;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.props.NodeConnectInfo;
import com.hedera.services.bdd.spec.remote.RemoteNetworkSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.utilops.inventory.AccessoryUtils;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ServiceEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import org.apache.commons.lang3.StringUtils;
import org.hiero.base.utility.CommonUtils;

/**
 * Aggregates the properties to be used in setting up a {@link HapiSpec}.
 */
public class HapiSpecSetup {
    private final SplittableRandom r = new SplittableRandom(1_234_567L);

    private Set<ResponseCodeEnum> streamlinedIngestChecks = null;
    private HapiPropertySource ciPropertiesMap = null;
    private static HapiPropertySource DEFAULT_PROPERTY_SOURCE = null;
    private static final HapiPropertySource BASE_DEFAULT_PROPERTY_SOURCE = JutilPropertySource.getDefaultInstance();

    public static HapiPropertySource getDefaultPropertySource() {
        if (DEFAULT_PROPERTY_SOURCE == null) {
            String globals = System.getProperty("global.property.overrides");
            globals = (globals == null) ? "" : globals;
            String[] sources = !globals.isEmpty() ? globals.split(",") : new String[0];
            DEFAULT_PROPERTY_SOURCE =
                    inPriorityOrder(asSources(Stream.of(Stream.of(sources), Stream.of(BASE_DEFAULT_PROPERTY_SOURCE))
                            .flatMap(Function.identity())
                            .toArray(Object[]::new)));
        }
        return DEFAULT_PROPERTY_SOURCE;
    }

    private static final HapiSpecSetup DEFAULT_INSTANCE;

    static {
        DEFAULT_INSTANCE = new HapiSpecSetup(getDefaultPropertySource());
    }

    public static HapiSpecSetup getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    private HapiPropertySource props;

    public static HapiSpecSetup setupFrom(Object... objs) {
        return new HapiSpecSetup(inPriorityOrder(asSources(objs)));
    }

    public enum NodeSelection {
        FIXED,
        RANDOM
    }

    public enum TlsConfig {
        ON,
        OFF,
        ALTERNATE
    }

    public enum TxnProtoStructure {
        NEW,
        OLD,
        ALTERNATE
    }

    public HapiSpecSetup(HapiPropertySource props) {
        this.props = props;
    }

    public PrivateKey payerKey() {
        try {
            return payerKeyAsEd25519();
        } catch (Exception e) {
            return payerKeyAsEcdsa();
        }
    }

    /**
     * Tries to load a private key from a file, which may either be a PEM file or a BIP-0032 mnemonic file; or, as
     * a last resort, a hexed ECDSA key.
     * @param f the file to load the key from
     * @param passphraseEnvVar if available the environment variable that may contain the passphrase for a PEM file
     * @return the loaded private key
     */
    public static PrivateKey loadKeyOrThrow(@NonNull final File f, @Nullable final String passphraseEnvVar) {
        final var path = f.getAbsolutePath();
        if (path.endsWith("pem")) {
            final var passphrase = getPassphrase(f, passphraseEnvVar).orElseThrow();
            return readUnknownTypeKeyFrom(f, passphrase);
        } else if (path.endsWith("words")) {
            final var mnemonic = Bip0032.mnemonicFromFile(path);
            return mnemonicToEd25519Key(mnemonic);
        } else {
            try {
                final var hexed = Files.readString(f.toPath()).trim();
                readECKeyFrom(CommonUtils.unhex(hexed));
            } catch (Exception ignore) {
            }
            throw new IllegalArgumentException("Unable to load a key from " + path);
        }
    }

    /**
     * If there is a passphrase environment variable, and it is set, return that.  Otherwise, if there is a
     * a passphrase file next to the given PEM file, return that. Otherwise, return empty.
     * @param pemFile the PEM file to look next to for a passphrase file
     * @param passphraseEnvVar if available the environment variable that may contain the passphrase for a PEM file
     * @return the passphrase, if available
     */
    private static Optional<String> getPassphrase(
            @NonNull final File pemFile, @Nullable final String passphraseEnvVar) {
        String fromEnv;
        if (passphraseEnvVar != null && (fromEnv = System.getenv(passphraseEnvVar)) != null) {
            return Optional.of(fromEnv);
        }
        final var optPassFile = AccessoryUtils.passFileFor(pemFile);
        if (optPassFile.isPresent()) {
            try {
                return Optional.of(Files.readString(optPassFile.get().toPath()).trim());
            } catch (IOException ignore) {
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the Ed25519 private key for the default payer in this spec setup.  This method will only return an Ed25519 key if the default payer key does point to an Ed25519 key
     * @return the Ed25519 private key for the default payer in this spec setup
     */
    private EdDSAPrivateKey payerKeyAsEd25519() {
        if (StringUtils.isNotEmpty(defaultPayerKey())) {
            return Ed25519Utils.keyFrom(CommonUtils.unhex(defaultPayerKey()));
        } else if (StringUtils.isNotEmpty(defaultPayerMnemonic())) {
            return mnemonicToEd25519Key(defaultPayerMnemonic());
        } else if (StringUtils.isNotEmpty(defaultPayerMnemonicFile())) {
            final var mnemonic = Bip0032.mnemonicFromFile(defaultPayerMnemonicFile());
            return mnemonicToEd25519Key(mnemonic);
        } else if (StringUtils.isNotEmpty(defaultPayerPemKeyResource())) {
            return defaultPayerKeyFromResource(in -> Ed25519Utils.readKeyFrom(in, defaultPayerPemKeyPassphrase()));
        } else {
            return Ed25519Utils.readKeyFrom(defaultPayerPemKeyLoc(), defaultPayerPemKeyPassphrase());
        }
    }

    /**
     * Returns the ECDSA private key for the default payer in this spec setup. This method will only return an ECDSA key if the default payer key does point to an ECDSA key.
     *
     * @return the ECDSA private key for the default payer in this spec setup
     */
    private ECPrivateKey payerKeyAsEcdsa() {
        if (StringUtils.isNotEmpty(defaultPayerKey())) {
            return Secp256k1Utils.readECKeyFrom(CommonUtils.unhex(defaultPayerKey()));
        } else if (StringUtils.isNotEmpty(defaultPayerPemKeyResource())) {
            return defaultPayerKeyFromResource(in -> Secp256k1Utils.readECKeyFrom(in, defaultPayerPemKeyPassphrase()));
        } else {
            return Secp256k1Utils.readECKeyFrom(new File(defaultPayerPemKeyLoc()), defaultPayerPemKeyPassphrase());
        }
    }

    private <T extends PrivateKey> T defaultPayerKeyFromResource(@NonNull final Function<InputStream, T> reader) {
        try (var in =
                Thread.currentThread().getContextClassLoader().getResourceAsStream(defaultPayerPemKeyResource())) {
            if (in == null) {
                throw new IllegalArgumentException(
                        "No resource found for default payer key " + defaultPayerPemKeyResource());
            }
            return reader.apply(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Add new properties that would merge with existing ones, if a property already exist then
     * override it with new value
     *
     * @param props A map of new properties
     */
    public void addOverrides(@NonNull final Map<String, String> props) {
        this.props = HapiPropertySource.inPriorityOrder(new MapPropertySource(props), this.props);
    }

    /**
     * Returns whether the default transaction memo should be the name of the {@link HapiSpec}
     * submitting the transaction.
     */
    public boolean useSpecName() {
        return props.getBoolean("memo.useSpecName");
    }

    public FileID addressBookId() {
        return props.getFile("address.book.id");
    }

    public String addressBookName() {
        return props.get("address.book.name");
    }

    public AccountID addressBookControl() {
        return props.getAccount("address.book.controlAccount.id");
    }

    public String addressBookControlName() {
        return props.get("address.book.controlAccount.name");
    }

    public FileID apiPermissionsId() {
        return props.getFile("api.permissions.id");
    }

    public String apiPermissionsFile() {
        return props.get("api.permissions.name");
    }

    public FileID appPropertiesId() {
        return props.getFile("app.properties.id");
    }

    public String appPropertiesFile() {
        return props.get("app.properties.name");
    }

    public HapiPropertySource ciPropertiesMap() {
        if (null == ciPropertiesMap) {
            ciPropertiesMap = MapPropertySource.parsedFromCommaDelimited(props.get("ci.properties.map"));
        }
        return ciPropertiesMap;
    }

    public Duration defaultAutoRenewPeriod() {
        return props.getDurationFromSecs("default.autorenew.secs");
    }

    public long defaultBalance() {
        return props.getLong("default.balance.tinyBars");
    }

    public long defaultCallGas() {
        return props.getLong("default.call.gas");
    }

    public String defaultConsensusMessage() {
        return props.get("default.consensus.message");
    }

    public long defaultContractBalance() {
        return props.getLong("default.contract.balance.tinyBars");
    }

    public String defaultContractPath() {
        return bytecodePath(props.get("default.contract.bytecode"));
    }

    public long defaultCreateGas() {
        return props.getLong("default.create.gas");
    }

    public long defaultExpirationSecs() {
        return props.getLong("default.expiration.secs");
    }

    public long defaultFee() {
        return props.getLong("default.fee");
    }

    public byte[] defaultFileContents() {
        return props.getBytes("default.file.contents");
    }

    public SigControl.KeyAlgo defaultKeyAlgo() {
        return props.getKeyAlgorithm("default.keyAlgorithm");
    }

    public KeyType defaultKeyType() {
        return props.getKeyType("default.keyType");
    }

    public int defaultListN() {
        return props.getInteger("default.listKey.N");
    }

    public long defaultMaxLocalCallRetBytes() {
        return props.getLong("default.max.localCall.retBytes");
    }

    public String defaultMemo() {
        return props.get("default.memo");
    }

    public HapiSpec.UTF8Mode isMemoUTF8() {
        return props.getUTF8Mode("default.useMemoUTF8");
    }

    public String defaultUTF8memo() {
        return props.get("default.memoUtf8Charset");
    }

    public AccountID defaultNode() {
        return props.getAccount("default.node");
    }

    public String defaultNodeName() {
        return props.get("default.node.name");
    }

    public long defaultNodePaymentTinyBars() {
        return props.getLong("default.nodePayment.tinyBars");
    }

    public String defaultPayerMnemonic() {
        return props.get("default.payer.mnemonic");
    }

    public String defaultPayerMnemonicFile() {
        return props.get("default.payer.mnemonicFile");
    }

    public String defaultPayerPemKeyResource() {
        return props.get("default.payer.pemKeyResource");
    }

    public String defaultPayerPemKeyLoc() {
        return props.get("default.payer.pemKeyLoc");
    }

    public String defaultPayerPemKeyPassphrase() {
        return props.get("default.payer.pemKeyPassphrase");
    }

    public AccountID defaultPayer() {
        return props.getAccount("default.payer");
    }

    public ServiceEndpoint defaultGossipEndpointInternal() {
        return CommonPbjConverters.fromPbj(props.getServiceEndpoint("default.gossipEndpoint.internal"));
    }

    public ServiceEndpoint defaultGossipEndpointExternal() {
        return CommonPbjConverters.fromPbj(props.getServiceEndpoint("default.gossipEndpoint.external"));
    }

    public ServiceEndpoint defaultServiceEndpoint() {
        return CommonPbjConverters.fromPbj(props.getServiceEndpoint("default.serviceEndpoint"));
    }

    public byte[] defaultGossipCaCertificate() {
        return props.getBytes("default.gossipCaCertificate");
    }

    public String defaultPayerKey() {
        return props.get("default.payer.key");
    }

    public String defaultPayerName() {
        return props.get("default.payer.name");
    }

    /**
     * Returns whether a {@link HapiSpec} should automatically take and fuzzy-match snapshots of the record stream.
     *
     * @return whether a {@link HapiSpec} should automatically take and fuzzy-match snapshots of the record stream
     */
    public boolean autoSnapshotManagement() {
        return props.getBoolean("recordStream.autoSnapshotManagement");
    }

    /**
     * Returns whether a {@link HapiSpec} doing automatic snapshot management should
     * override an existing snapshot.
     *
     * @return whether an auto-snapshot managing {@link HapiSpec} should override an existing snapshot
     */
    public boolean overrideExistingSnapshot() {
        return props.getBoolean("recordStream.overrideExistingSnapshot");
    }

    public boolean defaultReceiverSigRequired() {
        return props.getBoolean("default.receiverSigRequired");
    }

    public int defaultThresholdM() {
        return props.getInteger("default.thresholdKey.M");
    }

    public int defaultThresholdN() {
        return props.getInteger("default.thresholdKey.N");
    }

    public long defaultTokenInitialSupply() {
        return props.getLong("default.token.initialSupply");
    }

    public int defaultTokenDecimals() {
        return props.getInteger("default.token.decimals");
    }

    public int defaultTopicRunningHashVersion() {
        return props.getInteger("default.topic.runningHash.version");
    }

    public AccountID defaultTransfer() {
        return props.getAccount("default.transfer");
    }

    public String defaultTransferName() {
        return props.get("default.transfer.name");
    }

    public Duration defaultValidDuration() {
        return props.getDurationFromSecs("default.validDuration.secs");
    }

    public FileID exchangeRatesId() {
        return props.getFile("exchange.rates.id");
    }

    public String exchangeRatesName() {
        return props.get("exchange.rates.name");
    }

    public AccountID exchangeRatesControl() {
        return props.getAccount("exchange.rates.controlAccount.id");
    }

    public String exchangeRatesControlName() {
        return props.get("exchange.rates.controlAccount.name");
    }

    final HapiSpec.SpecStatus expectedFinalStatus() {
        return props.getSpecStatus("expected.final.status");
    }

    public AccountID feeScheduleControl() {
        return props.getAccount("fee.schedule.controlAccount.id");
    }

    public String feeScheduleControlName() {
        return props.get("fee.schedule.controlAccount.name");
    }

    public long feeScheduleFetchFee() {
        return props.getLong("fee.schedule.fetch.fee");
    }

    public FileID feeScheduleId() {
        return props.getFile("fee.schedule.id");
    }

    public String feeScheduleName() {
        return props.get("fee.schedule.name");
    }

    public AccountID simpleFeesScheduleControl() {
        return props.getAccount("simplefees.schedule.controlAccount.id");
    }

    public String simpleFeesScheduleControlName() {
        return props.get("simplefees.schedule.controlAccount.name");
    }

    public FileID simpleFeesScheduleId() {
        return props.getFile("simplefees.schedule.id");
    }

    public String simpleFeesScheduleName() {
        return props.get("simplefees.schedule.name");
    }

    public int feesTokenTransferUsageMultiplier() {
        return props.getInteger("fees.tokenTransferUsageMultiplier");
    }

    public boolean useFixedFee() {
        return props.getBoolean("fees.useFixedOffer");
    }

    public long fixedFee() {
        return props.getLong("fees.fixedOffer");
    }

    public String softwareUpdateAdminName() {
        return props.get("softwareUpdate.admin.name");
    }

    public AccountID softwareUpdateAdminId() {
        return props.getAccount("softwareUpdate.admin.id");
    }

    public String freezeAdminName() {
        return props.get("freeze.admin.name");
    }

    public AccountID freezeAdminId() {
        return props.getAccount("freeze.admin.id");
    }

    public FileID updateFeatureId() {
        return props.getFile("update.feature.id");
    }

    public String updateFeatureName() {
        return props.get("update.feature.name");
    }

    public AccountID fundingAccount() {
        return props.getAccount("funding.account");
    }

    public String fundingAccountName() {
        return props.get("funding.account.name");
    }

    public AccountID genesisAccount() {
        return props.getAccount("genesis.account");
    }

    public String genesisAccountName() {
        return props.get("genesis.account.name");
    }

    public ContractID invalidContract() {
        return props.getContract("invalid.contract");
    }

    public String invalidContractName() {
        return props.get("invalid.contract.name");
    }

    public Address missingAddress() {
        return Utils.mirrorAddrWith(props.getAccount("missing.address"));
    }

    public Boolean suppressUnrecoverableNetworkFailures() {
        return props.getBoolean("warnings.suppressUnrecoverableNetworkFailures");
    }

    public FileID nodeDetailsId() {
        return props.getFile("node.details.id");
    }

    public String nodeDetailsName() {
        return props.get("node.details.name");
    }

    public List<NodeConnectInfo> nodes(long shard, long realm) {
        NodeConnectInfo.NEXT_DEFAULT_ACCOUNT_NUM = 3;
        return Stream.of(props.get("nodes").split(","))
                .map(inString -> new NodeConnectInfo(inString, shard, realm))
                .toList();
    }

    public NodeSelection nodeSelector() {
        return props.getNodeSelector("node.selector");
    }

    public Integer numOpFinisherThreads() {
        return props.getInteger("num.opFinisher.threads");
    }

    public Integer port() {
        return props.getInteger("port");
    }

    public boolean statusDeferredResolvesDoAsync() {
        return props.getBoolean("status.deferredResolves.doAsync");
    }

    public long statusPreResolvePauseMs() {
        return props.getLong("status.preResolve.pause.ms");
    }

    public long statusWaitSleepMs() {
        return props.getLong("status.wait.sleep.ms");
    }

    public long statusWaitTimeoutMs() {
        return props.getLong("status.wait.timeout.ms");
    }

    public long shard() {
        return props.getShard();
    }

    public long realm() {
        return props.getRealm();
    }

    public AccountID nodeRewardAccount() {
        return asAccount(shard(), realm(), 801L);
    }

    public AccountID stakingRewardAccount() {
        return asAccount(shard(), realm(), 800);
    }

    public AccountID feeCollectorAccount() {
        return asAccount(shard(), realm(), 802);
    }

    public String nodeRewardAccountName() {
        return "NODE_REWARD";
    }

    public String stakingRewardAccountName() {
        return "STAKING_REWARD";
    }

    public String feeCollectorAccountName() {
        return "FEE_COLLECTOR";
    }

    public FileID throttleDefinitionsId() {
        return props.getFile("throttle.definitions.id");
    }

    public String throttleDefinitionsName() {
        return props.get("throttle.definitions.name");
    }

    public TlsConfig tls() {
        return props.getTlsConfig("tls");
    }

    public boolean getConfigTLS() {
        return switch (this.tls()) {
            case ON -> Boolean.TRUE;
            case OFF -> Boolean.FALSE;
            case ALTERNATE -> r.nextBoolean();
        };
    }

    TxnProtoStructure txnProtoStructure() {
        var protoStructure = props.getTxnConfig("txn.proto.structure");
        if (TxnProtoStructure.ALTERNATE == protoStructure) {
            if (r.nextBoolean()) {
                return TxnProtoStructure.NEW;
            } else {
                return TxnProtoStructure.OLD;
            }
        }
        return protoStructure;
    }

    public long txnStartOffsetSecs() {
        return props.getLong("txn.start.offset.secs");
    }

    public AccountID strongControlAccount() {
        return props.getAccount("strong.control.account");
    }

    public String strongControlName() {
        return props.get("strong.control.name");
    }

    public AccountID systemDeleteAdmin() {
        return props.getAccount("systemDeleteAdmin.account");
    }

    public String systemDeleteAdminName() {
        return props.get("systemDeleteAdmin.name");
    }

    public AccountID systemUndeleteAdmin() {
        return props.getAccount("systemUndeleteAdmin.account");
    }

    public String systemUndeleteAdminName() {
        return props.get("systemUndeleteAdmin.name");
    }

    /**
     * Returns the location of a YAML file that should be mappable to {@link RemoteNetworkSpec}.
     */
    public String remoteNodesYmlLoc() {
        return props.get("nodes.remoteYml");
    }

    /**
     * Returns the set of response codes that should be always be enforced on ingest. When
     * {@link HapiTxnOp#hasPrecheck(ResponseCodeEnum)} is given a response code <i>not</i> in
     * this set, it will automatically accept {@code OK} in its place, but switch the expected
     * consensus status to that response code.
     *
     * <p>That is, for a non-streamlined status like {@link ResponseCodeEnum#INVALID_ACCOUNT_AMOUNTS},
     * {@code hasPrecheck(INVALID_ACCOUNT_AMOUNTS)} is equivalent to,
     * <pre>{@code
     *     cryptoTransfer(...)
     *         .hasPrecheckFrom(OK, INVALID_ACCOUNT_AMOUNTS)
     *         .hasKnownStatus(INVALID_ACCOUNT_AMOUNTS)
     * }</pre>
     *
     * @return the set of response codes that should be always be enforced on ingest
     */
    public Set<ResponseCodeEnum> streamlinedIngestChecks() {
        if (streamlinedIngestChecks == null) {
            final var nominal = props.get("spec.streamlinedIngestChecks");
            streamlinedIngestChecks = EnumSet.copyOf(
                    nominal.isEmpty()
                            ? Collections.emptySet()
                            : Stream.of(nominal.split(","))
                                    .map(ResponseCodeEnum::valueOf)
                                    .collect(Collectors.toSet()));
        }
        return streamlinedIngestChecks;
    }
}
