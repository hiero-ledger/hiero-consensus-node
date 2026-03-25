// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.node.app.workflows.handle.HandleWorkflow.ALERT_MESSAGE;
import static java.util.Objects.requireNonNull;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.DEFAULT_HIGH_VOLUME_MULTIPLIER;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.HIGH_VOLUME_MULTIPLIER_SCALE;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.HIGH_VOLUME_PRICING_FUNCTIONS;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.fees.HighVolumePricingCalculator;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/**
 * Base class for simple fee calculators. Provides reusable utility methods for common fee
 * calculation patterns per HIP-1261.
 *
 * <p>Subclasses implement {@link SimpleFeeCalculator} directly and can use the static utility
 * methods provided here to avoid code duplication.
 */
public class SimpleFeeCalculatorImpl implements SimpleFeeCalculator {

    private static final Logger log = LogManager.getLogger(SimpleFeeCalculatorImpl.class);

    protected final FeeSchedule feeSchedule;
    private final Map<TransactionBody.DataOneOfType, ServiceFeeCalculator> serviceFeeCalculators;
    private final Map<Query.QueryOneOfType, QueryFeeCalculator> queryFeeCalculators;
    private final CongestionMultipliers congestionMultipliers;
    private final JSONFormatter custom_logger;


    public SimpleFeeCalculatorImpl(
            @NonNull FeeSchedule feeSchedule,
            @NonNull Set<ServiceFeeCalculator> serviceFeeCalculators,
            @NonNull Set<QueryFeeCalculator> queryFeeCalculators,
            @NonNull CongestionMultipliers congestionMultipliers) {
        this.feeSchedule = requireNonNull(feeSchedule);
        this.serviceFeeCalculators = serviceFeeCalculators.stream()
                .collect(Collectors.toMap(ServiceFeeCalculator::getTransactionType, Function.identity()));
        this.queryFeeCalculators = queryFeeCalculators.stream()
                .collect(Collectors.toMap(QueryFeeCalculator::getQueryType, Function.identity()));
        this.congestionMultipliers = congestionMultipliers;
        try {
            this.custom_logger = new JSONFormatter(new FileWriter(Path.of("/Users/josh/Documents/GitHub/hiero-consensus-node/simplefees.log.json").toFile()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @VisibleForTesting
    public SimpleFeeCalculatorImpl(
            @NonNull FeeSchedule feeSchedule,
            @NonNull Set<ServiceFeeCalculator> serviceFeeCalculators,
            @NonNull Set<QueryFeeCalculator> queryFeeCalculators) {
        this(feeSchedule, serviceFeeCalculators, queryFeeCalculators, null);
    }

    @VisibleForTesting
    public SimpleFeeCalculatorImpl(
            @NonNull FeeSchedule feeSchedule, @NonNull Set<ServiceFeeCalculator> serviceFeeCalculators) {
        this(feeSchedule, serviceFeeCalculators, Set.of());
    }

    /**
     * Adds fees from a list of extras to the result, using primitive counts.
     * Avoids Map allocation for hot path performance.
     *
     * @param result the fee result to accumulate fees into
     * @param extras the list of extra fee references from the fee schedule
     * @param signatures the number of signatures
     */
    private void addNodeExtras(
            @NonNull final FeeResult result,
            @NonNull final Iterable<ExtraFeeReference> extras,
            final long signatures,
            final long bytes) {
        for (final ExtraFeeReference ref : extras) {
            final long used =
                    switch (ref.name()) {
                        case SIGNATURES -> signatures;
                        case PROCESSING_BYTES -> bytes;
                        default -> 0;
                    };
            final long unitFee = getExtraFee(ref.name());
            result.addNodeExtraFeeTinycents(ref.name().name(), unitFee, used, ref.includedCount());
        }
    }

    /**
     * Calculates fees for transactions per HIP-1261.
     * Node fee includes BYTES (full transaction size) and SIGNATURES extras.
     * Service fee is transaction-specific.
     * For high-volume transactions (HIP-1313), applies a dynamic multiplier based on throttle utilization.
     * If congestion multipliers are configured and a store factory is available,
     * the congestion multiplier will be applied to the total fee.
     *
     * @param txnBody the transaction body
     * @param simpleFeeContext the fee context containing signature count and full transaction bytes
     * @return the calculated fee result
     */
    @NonNull
    @Override
    public FeeResult calculateTxFee(
            @NonNull final TransactionBody txnBody, @NonNull final SimpleFeeContext simpleFeeContext) {
        // Extract primitive counts (no allocations)
        final long signatures = simpleFeeContext.numTxnSignatures();
        // Get full transaction size in bytes (includes body, signatures, and all transaction data)
        final long bytes = simpleFeeContext.numTxnBytes();
        final var result = new FeeResult();
        final var functionality = simpleFeeContext.functionality();
        final var serviceFeeDefinition = lookupServiceFee(feeSchedule, functionality);
        final boolean nodeNetworkFeeExempt =
                serviceFeeDefinition != null && serviceFeeDefinition.nodeNetworkFeeExempt();
        if (!nodeNetworkFeeExempt) {
            // Add node base and extras (bytes and payer signatures)
            result.setNodeBaseFeeTinycents(requireNonNull(feeSchedule.node()).baseFee());
            addNodeExtras(result, feeSchedule.node().extras(), signatures, bytes);
            // Add network fee
            final int multiplier = requireNonNull(feeSchedule.network()).multiplier();
            result.setNetworkMultiplier(multiplier);
        }

        final var serviceFeeCalculator =
                serviceFeeCalculators.get(txnBody.data().kind());
        if(serviceFeeCalculator == null) {
            System.out.println("no calc for " + txnBody.data().kind());
        }
        serviceFeeCalculator.accumulateServiceFee(txnBody, simpleFeeContext, result, feeSchedule);
        final var isHighVolumeFunction = HIGH_VOLUME_PRICING_FUNCTIONS.contains(functionality);

        // Apply high-volume pricing multiplier if applicable (HIP-1313).
        // Also verify feature flags at consensus time to match the ingest-time guard in IngestChecker,
        // so that a flag toggle between ingest and consensus does not silently misprice the transaction.
        if (txnBody.highVolume() && isHighVolumeFunction && isHighVolumeFeatureEnabled(simpleFeeContext)) {
            applyHighVolumeMultiplier(simpleFeeContext, result);
        } else {
            // Apply congestion multiplier if available
            applyCongestionMultiplier(txnBody, simpleFeeContext, result, functionality);
        }

        try {
            this.logResult(result, txnBody);
        } catch (IOException e) {
            System.out.println("exception " + e);
//            throw new RuntimeException(e);
        }
        return result;
    }

    private void logResult(FeeResult result, TransactionBody txnBody) throws IOException {
        this.custom_logger.startRecord();
        this.custom_logger.key("name", txnBody.data().kind().name());
        this.custom_logger.startObject("transactionId");
        final var txnId = txnBody.transactionID();
        this.custom_logger.key("accountNum",txnId.accountID().accountNum());
        this.custom_logger.key("realmNum",txnId.accountID().realmNum());
        this.custom_logger.key("sharedNum",txnId.accountID().shardNum());
        this.custom_logger.key("seconds",txnId.transactionValidStart().seconds());
        this.custom_logger.key("nanos", txnId.transactionValidStart().nanos());
        this.custom_logger.key("nonce", txnId.nonce());
        this.custom_logger.endObject();
        this.custom_logger.startObject("simpleFee");
        this.custom_logger.key("totalFee",result.totalTinycents());
        this.custom_logger.key("serviceBaseFee",result.getServiceBaseFeeTinycents());
        this.custom_logger.key("serviceTotal",result.getServiceTotalTinycents());
        this.custom_logger.key("serviceExtras",result.getServiceExtraDetails().stream().<Map<String,Object>>map(d -> Map.of("name",d.name(),"perUnit",d.perUnit(),"used",d.used(),"included",d.included(),"charged",d.charged())).toList());
        this.custom_logger.key("nodeBaseFee",result.getNodeBaseFeeTinycents());
        this.custom_logger.key("nodeTotal",result.getNodeTotalTinycents());
        this.custom_logger.key("nodeExtras",result.getNodeExtraDetails().stream().<Map<String,Object>>map(d -> Map.of("name",d.name(),"perUnit",d.perUnit(),"used",d.used(),"included",d.included(),"charged",d.charged())).toList());
        this.custom_logger.key("networkMultiplier",result.getNetworkMultiplier());
        this.custom_logger.key("networkTotal",result.getNetworkTotalTinycents());
        this.custom_logger.key("highVolumeMultiplier",result.getHighVolumeMultiplier());
        this.custom_logger.endObject();
        this.custom_logger.endRecord();
    }

    /**
     * Applies the congestion multiplier to the fee result.
     * Gets the ReadableStoreFactory from the FeeContext implementation.
     *
     * @param txnBody the transaction body
     * @param simpleFeeContext the simple fee context
     * @param result the base fee result
     */
    private void applyCongestionMultiplier(
            @NonNull final TransactionBody txnBody,
            @NonNull final SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult result,
            @NonNull final HederaFunctionality functionality) {
        // For standalone fee calculator simpleFeeContext.feeContext() is null
        if (simpleFeeContext.feeContext() == null || congestionMultipliers == null) {
            return;
        }
        final var feeContext = simpleFeeContext.feeContext();
        final long congestionMultiplier =
                congestionMultipliers.maxCurrentMultiplier(txnBody, functionality, feeContext.readableStoreFactory());
        if (congestionMultiplier <= 1) {
            return;
        }
        result.applyMultiplier(congestionMultiplier, 1);
    }

    /**
     * Applies the high-volume pricing multiplier to the total fee based on throttle utilization.
     * This is applied after total fee is calculated.
     * Per HIP-1313, the multiplier is calculated from the pricing curve defined in the fee schedule.
     *
     * @param feeContext the fee context
     * @param result the fee result to modify
     */
    private void applyHighVolumeMultiplier(
            @NonNull final SimpleFeeContext feeContext, @NonNull final FeeResult result) {
        // For standalone fee calculator simpleFeeContext.feeContext() is null
        if (feeContext.feeContext() == null) {
            return;
        }
        final var rawMultiplier = highVolumeRawMultiplier(feeContext.body(), requireNonNull(feeContext.feeContext()));
        result.applyMultiplier(rawMultiplier, HIGH_VOLUME_MULTIPLIER_SCALE);
        result.setHighVolumeMultiplier(rawMultiplier);
    }

    @Override
    public long getExtraFee(Extra extra) {
        return feeSchedule.extras().stream()
                .filter(feeDefinition -> feeDefinition.name() == extra)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Extra fee not found: " + extra))
                .fee();
    }

    /**
     * Returns {@code true} when the high-volume feature is fully enabled, by checking both the
     * {@code fees.simpleFeesEnabled} and {@code networkAdmin.highVolumeThrottlesEnabled} flags
     * against the current configuration.  This mirrors the ingest-time guard in {@code IngestChecker}
     * so that a config change between ingest and consensus cannot silently bypass the feature gate.
     * Returns {@code false} when no {@link FeeContext} is available (standalone calculator).
     */
    private boolean isHighVolumeFeatureEnabled(@NonNull final SimpleFeeContext simpleFeeContext) {
        final var feeContext = simpleFeeContext.feeContext();
        if (feeContext == null) {
            return false;
        }
        final var config = feeContext.configuration();
        return config.getConfigData(FeesConfig.class).simpleFeesEnabled()
                && config.getConfigData(NetworkAdminConfig.class).highVolumeThrottlesEnabled();
    }

    @Override
    public long highVolumeRawMultiplier(@NonNull final TransactionBody txnBody, @NonNull final FeeContext feeContext) {
        final var functionality = feeContext.functionality();
        if (!txnBody.highVolume() || !HIGH_VOLUME_PRICING_FUNCTIONS.contains(functionality)) {
            return DEFAULT_HIGH_VOLUME_MULTIPLIER;
        }
        final var config = feeContext.configuration();
        if (!(config.getConfigData(FeesConfig.class).simpleFeesEnabled()
                && config.getConfigData(NetworkAdminConfig.class).highVolumeThrottlesEnabled())) {
            return DEFAULT_HIGH_VOLUME_MULTIPLIER;
        }
        final ServiceFeeDefinition serviceFeeDefinition = lookupServiceFee(feeSchedule, functionality);
        if (serviceFeeDefinition == null || serviceFeeDefinition.highVolumeRates() == null) {
            log.error(" {} - No high volume rates defined for {}", ALERT_MESSAGE, functionality);
            return DEFAULT_HIGH_VOLUME_MULTIPLIER;
        }
        final int utilizationPercentBasisPoints = feeContext.getHighVolumeThrottleUtilization(functionality);
        return HighVolumePricingCalculator.calculateMultiplier(
                serviceFeeDefinition.highVolumeRates(), utilizationPercentBasisPoints);
    }

    /**
     * Default implementation for query fee calculation.
     *
     * @param query The query to calculate fees for
     * @param simpleFeeContext the query context
     * @return Never returns normally
     * @throws UnsupportedOperationException always
     */
    @NonNull
    @Override
    public FeeResult calculateQueryFee(@NonNull final Query query, @NonNull final SimpleFeeContext simpleFeeContext) {
        final var result = new FeeResult();
        final var queryFeeCalculator = queryFeeCalculators.get(query.query().kind());
        queryFeeCalculator.accumulateNodePayment(query, simpleFeeContext, result, feeSchedule);
        return result;
    }

    private class JSONFormatter {

        private final FileWriter writer;
        private boolean start;

        public JSONFormatter(FileWriter writer) {
            this.writer = writer;
            this.start = false;
        }

        public void startRecord() throws IOException {
            writer.write("{ ");
            this.start = true;
        }

        public void key(String name, String value) throws IOException {
            if (!this.start) {
                writer.append(", ");
            }
            writer.append(String.format("\"%s\":\"%s\"", name, value.replaceAll("\n", " ")));
            this.start = false;
        }

        public void startObject(String name) throws IOException {
            if (!this.start) {
                writer.append(", ");
            }
            writer.append(String.format("\"%s\": {", name));
            this.start = true;
        }

        public void endObject() throws IOException {
            writer.append(" }");
            this.start = false;
        }

        public void endRecord() throws IOException {
            writer.write("}\n");
        }

        public void key(String name, long value) throws IOException {
            if (!this.start) {
                writer.append(", ");
            }
            writer.append(String.format("\"%s\" : %s ", name, "" + value));
            this.start = false;
        }

        public void key(String name, double value) throws IOException {
            if (!this.start) {
                writer.append(", ");
            }
            writer.append(String.format("\"%s\" : %.5f", name, value));
            this.start = false;
        }

        public void key(String name, List<Map<String, Object>> value) throws IOException {
            if (!this.start) {
                writer.append(", ");
            }
            writer.append(String.format("\"%s\" : [", name));
            for (int i = 0; i < value.size(); i++) {
                if (i > 0) writer.append(", ");
                writer.append("{ ");
                boolean first = true;
                for (var entry : value.get(i).entrySet()) {
                    if (!first) writer.append(", ");
                    Object v = entry.getValue();
                    if (v instanceof String s) {
                        writer.append(String.format("\"%s\":\"%s\"", entry.getKey(), s.replaceAll("\n", " ")));
                    } else {
                        writer.append(String.format("\"%s\":%s", entry.getKey(), v));
                    }
                    first = false;
                }
                writer.append(" }");
            }
            writer.append("]");
            this.start = false;
        }

        public void close() throws IOException {
            this.writer.flush();
            this.writer.close();
        }
    }
}
