// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.fees;

import static com.hedera.services.bdd.spec.HapiPropertySource.asFileString;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTransferList;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.toReadableString;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileGetContents;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.TxnFactory;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.CurrentAndNextFeeSchedule;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.ExchangeRateSet;
import com.hederahashgraph.api.proto.java.FeeSchedule;
import com.hederahashgraph.api.proto.java.FileGetContentsQuery;
import com.hederahashgraph.api.proto.java.FileGetContentsResponse;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FeesAndRatesProvider {
    private static final Logger log = LogManager.getLogger(FeesAndRatesProvider.class);

    private static final int NUM_DOWNLOAD_ATTEMPTS = 10;

    private static final BigDecimal USD_DIVISOR = BigDecimal.valueOf(100L);
    private static final BigDecimal HBAR_DIVISOR = BigDecimal.valueOf(100_000_000L);

    private TxnFactory txns;
    private KeyFactory keys;
    private HapiSpecSetup setup;
    private HapiSpecRegistry registry;
    private static long gasPrice;
    private static FeeSchedule feeSchedule;
    private static ExchangeRateSet rateSet;
    private final HederaNetwork network;
    private final ScheduleTypePatching typePatching = new ScheduleTypePatching();

    public FeesAndRatesProvider(
            @NonNull final TxnFactory txns,
            @NonNull final KeyFactory keys,
            @NonNull final HapiSpecSetup setup,
            @NonNull final HapiSpecRegistry registry,
            @NonNull final HederaNetwork network) {
        this.txns = requireNonNull(txns);
        this.keys = requireNonNull(keys);
        this.setup = requireNonNull(setup);
        this.registry = requireNonNull(registry);
        this.network = requireNonNull(network);
    }

    public void init() throws IOException, ReflectiveOperationException, GeneralSecurityException {
        if (!setup.useFixedFee()) {
            downloadRateSet();
            downloadFeeSchedule();
        }
    }

    public FeeSchedule currentSchedule() {
        return feeSchedule;
    }

    public long currentTinybarGasPrice() {
        return toTbWithActiveRates(gasPrice / 1000L);
    }

    public ExchangeRate rates() {
        return activeRates();
    }

    public void updateRateSet(ExchangeRateSet newSet) {
        rateSet = newSet;
        final String message = String.format("Updating rates! Now :: %s", rateSetAsString(newSet));
        log.info(message);
    }

    private ExchangeRate activeRates() {
        boolean useCurrent = Instant.now().getEpochSecond()
                < rateSet.getCurrentRate().getExpirationTime().getSeconds();

        return useCurrent ? rateSet.getCurrentRate() : rateSet.getNextRate();
    }

    public boolean hasRateSet() {
        return rateSet != null;
    }

    private void downloadRateSet() throws IOException, GeneralSecurityException, ReflectiveOperationException {
        long queryFee = lookupDownloadFee(setup.exchangeRatesId());
        FileGetContentsResponse response = downloadWith(queryFee, false, setup.exchangeRatesId());
        byte[] bytes = response.getFileContents().getContents().toByteArray();
        rateSet = ExchangeRateSet.parseFrom(bytes);
        String newSetAsString = rateSetAsString(rateSet);
        final String message = String.format("The exchange rates are :: %s", newSetAsString);
        log.info(message);
    }

    private void downloadFeeSchedule() throws IOException, GeneralSecurityException, ReflectiveOperationException {
        long queryFee = lookupDownloadFee(setup.feeScheduleId());
        FileGetContentsResponse response = downloadWith(queryFee, false, setup.feeScheduleId());
        byte[] bytes = response.getFileContents().getContents().toByteArray();
        CurrentAndNextFeeSchedule wrapper = CurrentAndNextFeeSchedule.parseFrom(bytes);
        setScheduleAndGasPriceFrom(typePatching.withPatchedTypesIfNecessary(wrapper.getCurrentFeeSchedule()));
        String message = String.format(
                "The fee schedule covers %s ops.",
                feeSchedule.getTransactionFeeScheduleList().size());
        log.info(message);
    }

    private long lookupDownloadFee(FileID fileId)
            throws IOException, GeneralSecurityException, ReflectiveOperationException {
        return downloadWith(setup.feeScheduleFetchFee(), true, fileId)
                .getHeader()
                .getCost();
    }

    private FileGetContentsResponse downloadWith(long queryFee, boolean costOnly, FileID fid)
            throws IOException, GeneralSecurityException, ReflectiveOperationException {
        int attemptsLeft = NUM_DOWNLOAD_ATTEMPTS;
        ResponseCodeEnum status;
        FileGetContentsResponse response;
        do {
            var payment = defaultPayerSponsored(queryFee);
            var query = downloadQueryWith(payment, costOnly, fid);
            response = network.send(query, FileGetContents, setup.defaultNode()).getFileGetContents();
            status = response.getHeader().getNodeTransactionPrecheckCode();
            if (status != OK) {
                log.warn(
                        "'{}' download attempt paid with {} got status {}; retrying {}...",
                        asFileString(fid),
                        toReadableString(payment),
                        status,
                        attemptsLeft);
                simpleSleep(1);
            }
        } while (status != OK && --attemptsLeft > 0);
        if (status != OK) {
            throw new IllegalStateException(
                    String.format("Could not download '%s' final status %s", asFileString(fid), status));
        }
        return response;
    }

    // Should really be in a utility somewhere, but it doesn't seem to be.
    private void simpleSleep(final int secondsToSleep) {
        if (secondsToSleep <= Byte.MAX_VALUE) {
            try {
                TimeUnit.SECONDS.sleep(secondsToSleep);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Query downloadQueryWith(Transaction payment, boolean costOnly, FileID fileId) {
        FileGetContentsQuery costQuery = FileGetContentsQuery.newBuilder()
                .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                .setFileID(fileId)
                .build();
        return Query.newBuilder().setFileGetContents(costQuery).build();
    }

    private Transaction defaultPayerSponsored(long queryFee)
            throws IOException, GeneralSecurityException, ReflectiveOperationException {
        TransferList transfers = asTransferList(tinyBarsFromTo(queryFee, setup.defaultPayer(), setup.defaultNode()));
        CryptoTransferTransactionBody opBody =
                txns.<CryptoTransferTransactionBody, CryptoTransferTransactionBody.Builder>body(
                        CryptoTransferTransactionBody.class, b -> b.setTransfers(transfers));
        Transaction.Builder txnBuilder = txns.getReadyToSign(
                b -> {
                    b.setTransactionID(TransactionID.newBuilder()
                            .mergeFrom(b.getTransactionID())
                            .setAccountID(setup.defaultPayer()));
                    b.setCryptoTransfer(opBody);
                },
                null,
                null);

        final var payerKey = flattenedMaybeList(registry.getKey(setup.defaultPayerName()));
        try {
            return keys.sign(null, txnBuilder, List.of(payerKey), Map.of(payerKey, SigControl.ED25519_ON));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private Key flattenedMaybeList(final Key k) {
        if (k.hasKeyList()) {
            return k.getKeyList().getKeys(0);
        } else {
            return k;
        }
    }

    public ExchangeRateSet rateSetWith(int curHbarEquiv, int curCentEquiv) {
        ExchangeRate.Builder curRate =
                rateSet.getCurrentRate().toBuilder().setHbarEquiv(curHbarEquiv).setCentEquiv(curCentEquiv);

        ExchangeRateSet perturbedSet =
                rateSet.toBuilder().setCurrentRate(curRate).build();
        String message = String.format("Computed a new rate set :: %s", rateSetAsString(perturbedSet));
        log.info(message);

        return perturbedSet;
    }

    public ExchangeRateSet rateSetWith(int curHbarEquiv, int curCentEquiv, int nextHbarEquiv, int nextCentEquiv) {
        ExchangeRate.Builder curRate =
                rateSet.getCurrentRate().toBuilder().setHbarEquiv(curHbarEquiv).setCentEquiv(curCentEquiv);

        ExchangeRate.Builder nextRate =
                rateSet.getCurrentRate().toBuilder().setHbarEquiv(nextHbarEquiv).setCentEquiv(nextCentEquiv);

        ExchangeRateSet perturbedSet = rateSet.toBuilder()
                .setCurrentRate(curRate)
                .setNextRate(nextRate)
                .build();
        String message = String.format("Computed a new rate set :: %s", rateSetAsString(perturbedSet));
        log.info(message);

        return perturbedSet;
    }

    public double toUsdWithActiveRates(long tb) {
        return BigDecimal.valueOf(tb)
                .divide(HBAR_DIVISOR)
                .divide(USD_DIVISOR)
                .multiply(BigDecimal.valueOf(activeRates().getCentEquiv()))
                .divide(BigDecimal.valueOf(activeRates().getHbarEquiv()), 5, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public long toTbWithActiveRates(long tc) {
        return tc * activeRates().getHbarEquiv() / activeRates().getCentEquiv();
    }

    public static String rateSetAsString(ExchangeRateSet set) {
        return "[Current hbar/cent equiv "
                + set.getCurrentRate().getHbarEquiv()
                + " <-> "
                + set.getCurrentRate().getCentEquiv()
                + " expiry @ "
                + set.getCurrentRate().getExpirationTime().getSeconds()
                + ", "
                + "Next hbar/cent equiv "
                + set.getNextRate().getHbarEquiv()
                + " <-> "
                + set.getNextRate().getCentEquiv()
                + " expiry @ "
                + set.getNextRate().getExpirationTime().getSeconds()
                + "]";
    }

    private void setScheduleAndGasPriceFrom(@NonNull final FeeSchedule schedule) {
        feeSchedule = schedule;
        gasPrice = feeSchedule.getTransactionFeeScheduleList().stream()
                .filter(tfs -> tfs.getHederaFunctionality() == HederaFunctionality.ContractCall)
                .flatMap(tfs -> tfs.getFeesList().stream())
                .filter(feeData -> feeData.getSubType() == SubType.DEFAULT)
                .findFirst()
                .map(feeData -> feeData.getServicedata().getGas())
                .orElse(0L);
    }
}
