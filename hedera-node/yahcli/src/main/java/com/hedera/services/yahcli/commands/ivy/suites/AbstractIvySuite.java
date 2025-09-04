// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.suites;

import static com.hedera.services.bdd.spec.HapiPropertySource.asContractString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asFileString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asTopicString;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.fundOrCreateEd25519Account;
import static com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig.SCENARIO_PAYER_NAME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static java.util.Objects.requireNonNull;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.yahcli.commands.ivy.scenarios.Scenarios;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import com.hedera.services.yahcli.config.YahcliKeys;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import org.junit.jupiter.api.Assertions;

public abstract class AbstractIvySuite extends HapiSuite {
    protected final Map<String, String> specConfig;
    protected final ScenariosConfig scenariosConfig;
    protected final Supplier<Supplier<String>> nodeAccounts;
    protected final Runnable persistUpdatedScenarios;
    protected final YahcliKeys yahcliKeys;

    protected AbstractIvySuite(
            @NonNull final Map<String, String> specConfig,
            @NonNull final ScenariosConfig scenariosConfig,
            @NonNull final Supplier<Supplier<String>> nodeAccounts,
            @NonNull final Runnable persistUpdatedScenarios,
            @NonNull final YahcliKeys yahcliKeys) {
        this.specConfig = requireNonNull(specConfig);
        this.scenariosConfig = requireNonNull(scenariosConfig);
        this.nodeAccounts = requireNonNull(nodeAccounts);
        this.persistUpdatedScenarios = requireNonNull(persistUpdatedScenarios);
        this.yahcliKeys = yahcliKeys;
    }

    protected SpecOperation ensureScenarioPayer() {
        return ensureEd25519Account(
                SCENARIO_PAYER_NAME,
                scenariosConfig.getScenarioPayer(),
                scenariosConfig.getEnsureScenarioPayerHbars() * TINY_PARTS_PER_WHOLE,
                scenariosConfig::setScenarioPayer);
    }

    protected SpecOperation ensureEd25519Account(
            @NonNull final String name,
            @Nullable final Long maybeNumber,
            final long desiredBalance,
            @NonNull LongConsumer onCreatedNumber) {
        return fundOrCreateEd25519Account(
                name,
                maybeNumber,
                desiredBalance,
                number -> yahcliKeys.loadAccountKey(number, EdDSAPrivateKey.class),
                spec -> {
                    final long createdNum = spec.registry().getAccountID(name).getAccountNum();
                    onCreatedNumber.accept(createdNum);
                    yahcliKeys.exportAccountKey(spec, name);
                    persistUpdatedScenarios.run();
                });
    }

    protected SpecOperation ensureEd25519File(
            @NonNull final String name,
            @Nullable final Long number,
            @NonNull final byte[] contents,
            @NonNull final LongConsumer onCreatedNumber) {
        return doingContextual(spec -> {
            if (number == null) {
                final var creation = fileCreate(name)
                        .waclShape(KeyShape.listOf(ED25519))
                        .setNodeFrom(nodeAccounts.get())
                        .contents(contents)
                        .hasRetryPrecheckFrom(BUSY)
                        .advertisingCreation();
                allRunFor(spec, creation);
                onCreatedNumber.accept(spec.registry().getFileId(name).getFileNum());
                yahcliKeys.exportFirstFileWaclKey(spec, name);
                persistUpdatedScenarios.run();
            } else {
                final var fileId = spec.fileIdFactory().apply(number);
                final var idLiteral = asFileString(fileId);
                final var infoLookup = getFileInfo(idLiteral).setNodeFrom(nodeAccounts.get());
                allRunFor(spec, infoLookup);
                final var key = yahcliKeys.loadFileKey(number, EdDSAPrivateKey.class);
                final var expectedKey = Key.newBuilder()
                        .setKeyList(KeyList.newBuilder()
                                .addKeys(Key.newBuilder().setEd25519(ByteString.copyFrom(key.getAbyte()))))
                        .build();
                final var info = infoLookup.getResponse().getFileGetInfo().getFileInfo();
                Assertions.assertEquals(
                        expectedKey.getKeyList(),
                        info.getKeys(),
                        String.format("File %s had a different key than expected", idLiteral));
                final var contentLookup = getFileContents(idLiteral)
                        .setNodeFrom(nodeAccounts.get())
                        .hasContents(ignore -> contents);
                allRunFor(spec, contentLookup);
                spec.registry().saveKey(name, expectedKey);
                spec.registry().saveFileId(name, fileId);
                spec.keys().incorporateEd25519SimpleWacl(name, key);
            }
        });
    }

    protected SpecOperation ensureEd25519Contract(
            @NonNull final String name,
            @Nullable final Long number,
            @NonNull final String initcodeName,
            @NonNull final LongConsumer onCreatedNumber) {
        return doingContextual(spec -> {
            if (number == null) {
                final var creation = contractCreate(name)
                        .adminKeyShape(ED25519)
                        .setNodeFrom(nodeAccounts.get())
                        .advertisingCreation()
                        .bytecode(initcodeName);
                allRunFor(spec, creation);
                onCreatedNumber.accept(spec.registry().getContractId(name).getContractNum());
                yahcliKeys.exportContractKey(spec, name);
                persistUpdatedScenarios.run();
            } else {
                final var contractId = spec.contractIdFactory().apply(number);
                final var idLiteral = asContractString(contractId);

                var infoLookup = getContractInfo(idLiteral).setNodeFrom(nodeAccounts.get());
                allRunFor(spec, infoLookup);
                final var info = infoLookup.getResponse().getContractGetInfo().getContractInfo();
                final var key = yahcliKeys.loadContractKey(number, EdDSAPrivateKey.class);
                final var expectedKey = Key.newBuilder()
                        .setEd25519(ByteString.copyFrom(key.getAbyte()))
                        .build();
                Assertions.assertEquals(
                        expectedKey,
                        info.getAdminKey(),
                        String.format("Contract %s had a different admin key than expected", idLiteral));

                final var bytecodeCheck = getContractBytecode(idLiteral)
                        .setNodeFrom(nodeAccounts.get())
                        .isNonEmpty();
                allRunFor(spec, bytecodeCheck);

                spec.registry().saveKey(name, expectedKey);
                spec.registry().saveContractId(name, contractId);
                spec.registry().saveAccountId(name, HapiPropertySource.asAccount(idLiteral));
                spec.keys().incorporate(name, key);
            }
        });
    }

    protected SpecOperation ensureEd25519Topic(
            @NonNull final String name,
            @Nullable final Long number,
            @NonNull final LongConsumer onCreatedNumber,
            @NonNull final LongConsumer onObservedSequenceNumber) {
        return doingContextual(spec -> {
            if (number == null) {
                final var creation = createTopic(name)
                        .setNodeFrom(nodeAccounts.get())
                        .adminKeyShape(ED25519)
                        .hasRetryPrecheckFrom(BUSY)
                        .advertisingCreation();
                allRunFor(spec, creation);
                onCreatedNumber.accept(spec.registry().getTopicID(name).getTopicNum());
                yahcliKeys.exportTopicAdminKey(spec, name);
                persistUpdatedScenarios.run();
            } else {
                final var topicId = spec.topicIdFactory().apply(number);
                final var idLiteral = asTopicString(topicId);
                final var infoLookup = getTopicInfo(idLiteral).setNodeFrom(nodeAccounts.get());
                allRunFor(spec, infoLookup);
                final var key = yahcliKeys.loadTopicAdminKey(number, EdDSAPrivateKey.class);
                final var expectedKey = Key.newBuilder()
                        .setEd25519(ByteString.copyFrom(key.getAbyte()))
                        .build();
                final var info =
                        infoLookup.getResponse().getConsensusGetTopicInfo().getTopicInfo();
                Assertions.assertEquals(
                        expectedKey,
                        info.getAdminKey(),
                        String.format("Topic %s had a different admin key than expected", idLiteral));
                onObservedSequenceNumber.accept(info.getSequenceNumber());
                spec.registry().saveKey(name, expectedKey);
                spec.registry().saveTopicId(name, topicId);
                spec.keys().incorporate(name, key);
            }
        });
    }

    protected Scenarios getOrCreateScenarios() {
        if (scenariosConfig.getScenarios() == null) {
            scenariosConfig.setScenarios(new Scenarios());
        }
        return scenariosConfig.getScenarios();
    }
}
