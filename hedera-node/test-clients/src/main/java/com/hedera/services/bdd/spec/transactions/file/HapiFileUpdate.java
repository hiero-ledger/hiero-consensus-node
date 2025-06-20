// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.file;

import static com.hedera.services.bdd.spec.keys.SigMapGenerator.Nature.FULL_PREFIXES;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.TxnFactory.expiryNowFor;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.utils.sysfiles.serdes.StandardSerdes.SYS_FILE_SERDES;
import static java.util.Collections.EMPTY_MAP;

import com.google.common.base.MoreObjects;
import com.google.common.io.Files;
import com.google.protobuf.ByteString;
import com.google.protobuf.StringValue;
import com.hedera.node.app.hapi.fees.usage.file.ExtantFileContext;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.keys.TrieSigMapGenerator;
import com.hedera.services.bdd.spec.queries.file.HapiGetFileContents;
import com.hedera.services.bdd.spec.queries.file.HapiGetFileInfo;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.ExchangeRateSet;
import com.hederahashgraph.api.proto.java.FileGetInfoResponse;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.FileUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import com.hederahashgraph.api.proto.java.Setting;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class HapiFileUpdate extends HapiTxnOp<HapiFileUpdate> {
    // Temporary work-around for CI states with expired system files
    private static final long MIN_SYS_FILE_LIFETIME = 7776000L;
    static final Logger LOG = LogManager.getLogger(HapiFileUpdate.class);

    /* WARNING - set to true only if you really want to replace 0.0.121/2! */
    private boolean dropUnmentionedProperties = false;
    private boolean useBadlyEncodedWacl = false;
    private boolean useEmptyWacl = false;

    private final String file;
    private OptionalLong expiryExtension = OptionalLong.empty();
    private Optional<Long> lifetimeSecs = Optional.empty();
    private Optional<String> newMemo = Optional.empty();
    private Optional<String> newWaclKey = Optional.empty();
    private Optional<String> newContentsPath = Optional.empty();
    private Optional<String> literalNewContents = Optional.empty();
    private Optional<String> basePropsFile = Optional.empty();
    private Optional<ByteString> newContents = Optional.empty();
    private Optional<Set<String>> propDeletions = Optional.empty();
    private Optional<Map<String, String>> propOverrides = Optional.empty();
    private Optional<Function<HapiSpec, ByteString>> contentFn = Optional.empty();

    Optional<Consumer<FileID>> preUpdateCb = Optional.empty();
    Optional<Consumer<ResponseCodeEnum>> postUpdateCb = Optional.empty();

    public HapiFileUpdate(String file) {
        this.file = file;
        sigMapPrefixes(TrieSigMapGenerator.withNature(FULL_PREFIXES));
    }

    /**
     * Given a spec and a map of property overrides, returns the bytes of a new {@code 0.0.121}
     * file that is the result of:
     * <ol>
     *     <li>Downloading the {@code 0.0.121} file from the spec's network; and,</li>
     *     <li>Deserializing it as a {@link ServicesConfigurationList}; and,</li>
     *     <li>Merging the given overrides into this list; and</li>
     *     <li>Serializing the resulting list back to a {@code byte[]}.</li>
     * </ol>
     *
     * @param spec the spec whose target network we should download the properties file
     * @param overrides the overrides to apply to the downloaded properties file
     * @return the bytes of the updated properties file
     */
    public static byte[] getUpdated121(@NonNull HapiSpec spec, @NonNull final Map<String, String> overrides) {
        final var baseConfig = downloadConfigFile(spec, APP_PROPERTIES, Optional.of(GENESIS));
        return computeConfigFrom(baseConfig, overrides, Collections.emptySet());
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.FileUpdate;
    }

    public HapiFileUpdate lifetime(long secs) {
        lifetimeSecs = Optional.of(secs);
        return this;
    }

    public HapiFileUpdate extendingExpiryBy(long secs) {
        expiryExtension = OptionalLong.of(secs);
        return this;
    }

    public HapiFileUpdate entityMemo(String explicit) {
        newMemo = Optional.of(explicit);
        return this;
    }

    public HapiFileUpdate wacl(String name) {
        newWaclKey = Optional.of(name);
        return this;
    }

    public HapiFileUpdate contents(Function<HapiSpec, ByteString> fn) {
        contentFn = Optional.of(fn);
        return this;
    }

    public HapiFileUpdate settingProps(String path) {
        return settingProps(path, EMPTY_MAP);
    }

    public HapiFileUpdate settingProps(String path, Map<String, String> overrides) {
        basePropsFile = Optional.of(path);
        propOverrides = Optional.of(overrides);
        return this;
    }

    public HapiFileUpdate overridingProps(Map<String, String> overrides) {
        propOverrides = Optional.of(overrides);
        return this;
    }

    public HapiFileUpdate erasingProps(Set<String> tbd) {
        propDeletions = Optional.of(tbd);
        return this;
    }

    private static Setting asSetting(String name, String value) {
        return Setting.newBuilder().setName(name).setValue(value).build();
    }

    public HapiFileUpdate contents(ByteString byteString) {
        newContents = Optional.of(byteString);
        return this;
    }

    public HapiFileUpdate contents(byte[] literal) {
        newContents = Optional.of(ByteString.copyFrom(literal));
        return this;
    }

    public HapiFileUpdate contents(String literal) {
        literalNewContents = Optional.of(literal);
        contents(literal.getBytes());
        return this;
    }

    public HapiFileUpdate path(String path) {
        newContentsPath = Optional.of(path);
        return this;
    }

    private Key emptyWacl() {
        return Key.newBuilder().setKeyList(KeyList.getDefaultInstance()).build();
    }

    private Key badlyEncodedWacl() {
        return Key.newBuilder()
                .setKeyList(
                        KeyList.newBuilder().addKeys(Key.getDefaultInstance()).addKeys(Key.getDefaultInstance()))
                .build();
    }

    public HapiFileUpdate useBadWacl() {
        useBadlyEncodedWacl = true;
        return this;
    }

    public HapiFileUpdate useEmptyWacl() {
        useEmptyWacl = true;
        return this;
    }

    public HapiFileUpdate alertingPre(Consumer<FileID> preCb) {
        preUpdateCb = Optional.of(preCb);
        return this;
    }

    public HapiFileUpdate alertingPost(Consumer<ResponseCodeEnum> postCb) {
        postUpdateCb = Optional.of(postCb);
        return this;
    }

    @Override
    protected void updateStateOf(HapiSpec spec) throws Throwable {
        postUpdateCb.ifPresent(cb -> cb.accept(actualStatus));
        if (actualStatus != ResponseCodeEnum.SUCCESS) {
            return;
        }
        newWaclKey.ifPresent(k -> spec.registry().saveKey(file, spec.registry().getKey(k)));
        expiryExtension.ifPresent(extension -> {
            try {
                spec.registry()
                        .saveTimestamp(
                                file,
                                Timestamp.newBuilder()
                                        .setSeconds(spec.registry()
                                                        .getTimestamp(file)
                                                        .getSeconds()
                                                + extension)
                                        .build());
            } catch (Exception ignored) {
                // Intentionally ignored
            }
        });
        if (file.equals(spec.setup().exchangeRatesName()) && newContents.isPresent()) {
            var newRateSet = ExchangeRateSet.parseFrom(newContents.get());
            spec.ratesProvider().updateRateSet(newRateSet);
        }

        if (verboseLoggingOn) {
            LOG.info("Updated file  {} with ID {}.", file, lastReceipt.getFileID());
        }
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        Supplier<Optional<Key>> normalWaclSupplier =
                () -> useEmptyWacl ? Optional.of(emptyWacl()) : newWaclKey.map(spec.registry()::getKey);
        Optional<Key> wacl = useBadlyEncodedWacl ? Optional.of(badlyEncodedWacl()) : normalWaclSupplier.get();
        if (newContentsPath.isPresent()) {
            newContents = Optional.of(ByteString.copyFrom(Files.toByteArray(new File(newContentsPath.get()))));
        } else if (contentFn.isPresent()) {
            newContents = Optional.of(contentFn.get().apply(spec));
        } else if (propOverrides.isPresent() || propDeletions.isPresent()) {
            if (propOverrides.isEmpty()) {
                propOverrides = Optional.of(Collections.emptyMap());
            }
            final var baseProps = readBaseProps(spec);
            final var updatedFile121 =
                    computeConfigFrom(baseProps, propOverrides.get(), propDeletions.orElse(Collections.emptySet()));
            newContents = Optional.of(ByteString.copyFrom(updatedFile121));
        }

        long nl = -1;
        var fid = TxnUtils.asFileId(file, spec);
        if (expiryExtension.isPresent()) {
            try {
                var oldExpiry = spec.registry().getTimestamp(file).getSeconds();
                nl = oldExpiry - spec.consensusTime().getEpochSecond() + expiryExtension.getAsLong();
            } catch (Exception ignored) {
                // Intentionally ignored
            }
        } else if (lifetimeSecs.isPresent()) {
            nl = lifetimeSecs.get();
        } else if (SYS_FILE_SERDES.containsKey(fid.getFileNum())) {
            nl = MIN_SYS_FILE_LIFETIME;
        }
        final OptionalLong newLifetime = (nl == -1) ? OptionalLong.empty() : OptionalLong.of(nl);
        FileUpdateTransactionBody opBody = spec.txns()
                .<FileUpdateTransactionBody, FileUpdateTransactionBody.Builder>body(
                        FileUpdateTransactionBody.class, builder -> {
                            builder.setFileID(fid);
                            newMemo.ifPresent(s -> builder.setMemo(
                                    StringValue.newBuilder().setValue(s).build()));
                            wacl.ifPresent(k -> builder.setKeys(k.getKeyList()));
                            newContents.ifPresent(builder::setContents);
                            newLifetime.ifPresent(s -> builder.setExpirationTime(expiryNowFor(spec, s)));
                        });
        preUpdateCb.ifPresent(cb -> cb.accept(fid));
        return builder -> builder.setFileUpdate(opBody);
    }

    @SuppressWarnings("java:S5960")
    private ServicesConfigurationList readBaseProps(HapiSpec spec) {
        if (dropUnmentionedProperties) {
            return ServicesConfigurationList.getDefaultInstance();
        }

        if (!basePropsFile.isPresent()) {
            if (!file.equals(HapiSuite.API_PERMISSIONS) && !file.equals(HapiSuite.APP_PROPERTIES)) {
                throw new IllegalStateException("Property overrides make no sense for file '" + file + "'!");
            }
            return downloadConfigFile(spec, file, payer);
        } else {
            String defaultsPath = basePropsFile.get();
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(new File(defaultsPath).toPath());
                return ServicesConfigurationList.parseFrom(bytes);
            } catch (Exception e) {
                LOG.error("No available defaults for {} --- aborting!", file, e);
                throw new IllegalStateException("Property overrides via fileUpdate must have available defaults!");
            }
        }
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        List<Function<HapiSpec, Key>> signers = new ArrayList<>(oldDefaults());
        if (newWaclKey.isPresent()) {
            signers.add(spec -> spec.registry().getKey(newWaclKey.get()));
        }
        return signers;
    }

    private List<Function<HapiSpec, Key>> oldDefaults() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                .getKey(file));
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        try {
            final FileGetInfoResponse.FileInfo info = lookupInfo(spec);
            FeeCalculator.ActivityMetrics metricsCalc = (innerTxn, svo) -> {
                var ctx = ExtantFileContext.newBuilder()
                        .setCurrentExpiry(info.getExpirationTime().getSeconds())
                        .setCurrentMemo(info.getMemo())
                        .setCurrentWacl(info.getKeys())
                        .setCurrentSize(info.getSize())
                        .build();
                return fileOpsUsage.fileUpdateUsage(innerTxn, suFrom(svo), ctx);
            };
            return spec.fees().forActivityBasedOp(HederaFunctionality.FileUpdate, metricsCalc, txn, numPayerKeys);
        } catch (Throwable ignore) {
            return ONE_HBAR;
        }
    }

    @SuppressWarnings("java:S112")
    private FileGetInfoResponse.FileInfo lookupInfo(HapiSpec spec) throws Throwable {
        HapiGetFileInfo subOp = getFileInfo(file).noLogging().fee(ONE_HBAR);
        Optional<Throwable> error = subOp.execFor(spec);
        if (error.isPresent()) {
            if (!loggingOff) {
                LOG.warn("Unable to look up current file info!", error.get());
            }
            throw error.get();
        }
        return subOp.getResponse().getFileGetInfo().getFileInfo();
    }

    @Override
    protected HapiFileUpdate self() {
        return this;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        MoreObjects.ToStringHelper helper = super.toStringHelper().add("fileName", file);
        newContentsPath.ifPresent(p -> helper.add("path", p));
        literalNewContents.ifPresent(l -> helper.add("contents", l));
        return helper;
    }

    private static byte[] computeConfigFrom(
            @NonNull final ServicesConfigurationList baseConfig,
            @NonNull final Map<String, String> overrides,
            @NonNull final Set<String> deletions) {
        final var updatedConfig = ServicesConfigurationList.newBuilder();
        final Map<String, String> baseSettings =
                baseConfig.getNameValueList().stream().collect(Collectors.toMap(Setting::getName, Setting::getValue));

        final Set<String> keys = new HashSet<>();
        baseConfig.getNameValueList().stream()
                .map(Setting::getName)
                .filter(key -> !deletions.contains(key))
                .forEach(keys::add);
        keys.addAll(overrides.keySet());

        keys.forEach(key -> {
            if (overrides.containsKey(key)) {
                updatedConfig.addNameValue(asSetting(key, overrides.get(key)));
            } else {
                updatedConfig.addNameValue(asSetting(key, baseSettings.get(key)));
            }
        });
        return updatedConfig.build().toByteString().toByteArray();
    }

    private static ServicesConfigurationList downloadConfigFile(
            @NonNull final HapiSpec spec, @NonNull final String file, @NonNull final Optional<String> payer) {
        int getsRemaining = 10;
        var gotFileContents = false;
        HapiGetFileContents subOp = null;
        while (!gotFileContents) {
            try {
                var candSubOp = getFileContents(file);
                payer.ifPresent(name -> candSubOp.payingWith(payerToUse(name, spec)));
                allRunFor(spec, candSubOp);
                gotFileContents = true;
                subOp = candSubOp;
            } catch (Exception ignored) {
                getsRemaining--;
            }
            if (getsRemaining == 0) {
                break;
            }
        }
        if (!gotFileContents) {
            Assertions.fail("Unable to use 'overridingProps', couldn't get existing file contents!");
        }
        try {
            @SuppressWarnings("java:S2259")
            byte[] bytes = subOp.getResponse()
                    .getFileGetContents()
                    .getFileContents()
                    .getContents()
                    .toByteArray();
            return ServicesConfigurationList.parseFrom(bytes);
        } catch (Exception e) {
            LOG.error("No available defaults for {} --- aborting!", file, e);
            throw new IllegalStateException("Property overrides via fileUpdate must have available defaults!");
        }
    }

    private static String payerToUse(String designated, HapiSpec spec) {
        return isPrivileged(designated, spec) ? spec.setup().genesisAccountName() : designated;
    }

    private static boolean isPrivileged(String account, HapiSpec spec) {
        return account.equals(spec.setup().addressBookControlName())
                || account.equals(spec.setup().exchangeRatesControlName())
                || account.equals(spec.setup().feeScheduleControlName())
                || account.equals(spec.setup().strongControlName());
    }
}
