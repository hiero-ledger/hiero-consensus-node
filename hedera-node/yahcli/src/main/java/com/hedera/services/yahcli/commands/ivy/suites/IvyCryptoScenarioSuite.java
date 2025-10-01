// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.suites;

import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.yahcli.commands.ivy.scenarios.CryptoScenario.NOVEL_ACCOUNT_NAME;
import static com.hedera.services.yahcli.commands.ivy.scenarios.CryptoScenario.RECEIVER_NAME;
import static com.hedera.services.yahcli.commands.ivy.scenarios.CryptoScenario.SENDER_NAME;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig.SCENARIO_PAYER_NAME;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.yahcli.commands.ivy.scenarios.CryptoScenario;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import com.hedera.services.yahcli.config.YahcliKeys;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

/**
 * Implements the old {@code ValidationScenarios} crypto scenario.
 */
public class IvyCryptoScenarioSuite extends AbstractIvySuite {
    private static final Logger log = LogManager.getLogger(IvyCryptoScenarioSuite.class);

    private final boolean novel;

    public IvyCryptoScenarioSuite(
            @NonNull final Map<String, String> specConfig,
            @NonNull final ScenariosConfig scenariosConfig,
            @NonNull final Supplier<Supplier<String>> nodeAccounts,
            @NonNull final Runnable persistUpdatedScenarios,
            @NonNull final YahcliKeys yahcliKeys,
            final boolean novel) {
        super(specConfig, scenariosConfig, nodeAccounts, persistUpdatedScenarios, yahcliKeys);
        this.novel = novel;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(cryptoScenario());
    }

    final Stream<DynamicTest> cryptoScenario() {
        final var scenarios = getOrCreateScenarios();
        if (scenarios.getCrypto() == null) {
            scenarios.setCrypto(new CryptoScenario());
        }
        final var crypto = requireNonNull(scenariosConfig.getScenarios().getCrypto());
        final var transferFee = new AtomicLong();
        final long expectedBalanceDelta = novel ? 2L : 1L;
        return HapiSpec.customHapiSpec("CryptoScenario")
                .withProperties(specConfig)
                .given(
                        ensureScenarioPayer(),
                        ensureEd25519Account(SENDER_NAME, crypto.getSender(), 2L, crypto::setSender),
                        ensureEd25519Account(RECEIVER_NAME, crypto.getReceiver(), 0L, crypto::setReceiver),
                        balanceSnapshot("receiverBefore", RECEIVER_NAME))
                .when(flattened(
                        cryptoTransfer(tinyBarsFromTo(SENDER_NAME, RECEIVER_NAME, 1L))
                                .payingWith(SCENARIO_PAYER_NAME)
                                .setNodeFrom(nodeAccounts.get())
                                .via("transfer"),
                        withOpContext((spec, opLog) -> {
                            final var lookup = getTxnRecord("transfer")
                                    .payingWith(SCENARIO_PAYER_NAME)
                                    .setNodeFrom(nodeAccounts.get());
                            allRunFor(spec, lookup);
                            final var record = lookup.getResponseRecord();
                            transferFee.set(record.getTransactionFee());
                        }),
                        novelAccountIfDesired(transferFee)))
                .then(getAccountBalance(RECEIVER_NAME)
                        .setNodeFrom(nodeAccounts.get())
                        .hasTinyBars(changeFromSnapshot("receiverBefore", expectedBalanceDelta)));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    private HapiSpecOperation[] novelAccountIfDesired(@NonNull final AtomicLong transferFee) {
        if (!novel) {
            return new HapiSpecOperation[] {};
        }
        final var complex = KeyShape.threshOf(1, KeyShape.listOf(2), KeyShape.threshOf(1, 3));
        return new HapiSpecOperation[] {
            newKeyNamed("novelAccountFirstKey").shape(complex),
            newKeyNamed("novelAccountSecondKey"),
            cryptoCreate(NOVEL_ACCOUNT_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .balance(ignore -> Math.min(ONE_HBAR, 2 * transferFee.get()))
                    .key("novelAccountFirstKey"),
            cryptoUpdate(NOVEL_ACCOUNT_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .key("novelAccountSecondKey"),
            cryptoTransfer(tinyBarsFromTo(SENDER_NAME, RECEIVER_NAME, 1L))
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .payingWith(NOVEL_ACCOUNT_NAME),
            cryptoDelete(NOVEL_ACCOUNT_NAME)
                    .payingWith(SCENARIO_PAYER_NAME)
                    .setNodeFrom(nodeAccounts.get())
                    .transfer(DEFAULT_PAYER)
        };
    }
}
