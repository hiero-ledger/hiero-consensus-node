// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.queries.contract;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.ensureDir;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.rethrowSummaryError;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.assertExpectedRels;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.assertNoUnexpectedRels;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asContractId;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ContractGetInfoResponse.ContractInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.base.MoreObjects;
import com.google.common.io.ByteSink;
import com.google.common.io.ByteSource;
import com.google.common.io.Files;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.assertions.ErroringAsserts;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hederahashgraph.api.proto.java.ContractGetInfoQuery;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * Get the info of a contract.
 * NOTE: Since we don't return token relationships from getContractInfo query, we are using getAccountDetails query
 * if there are any assertions about token relationships for internal testing.
 */
public class HapiGetContractInfo extends HapiQueryOp<HapiGetContractInfo> {
    private static final Logger LOG = LogManager.getLogger(HapiGetContractInfo.class);

    private final String contract;
    private boolean getPredefinedId = false;
    private Optional<String> contractInfoPath = Optional.empty();
    private Optional<String> validateDirPath = Optional.empty();
    private Optional<String> registryEntry = Optional.empty();
    private Optional<String> saveEVMAddressToRegistry = Optional.empty();
    private List<String> absentRelationships = new ArrayList<>();
    private List<ExpectedTokenRel> relationships = new ArrayList<>();
    private Optional<ContractInfoAsserts> expectations = Optional.empty();
    private Optional<Consumer<String>> exposingEvmAddress = Optional.empty();

    @Nullable
    private Consumer<ContractID> exposingContractId = null;

    public HapiGetContractInfo(String contract) {
        this.contract = contract;
    }

    public HapiGetContractInfo(String contract, boolean idPredefined) {
        this.contract = contract;
        getPredefinedId = idPredefined;
    }

    public HapiGetContractInfo has(ContractInfoAsserts provider) {
        expectations = Optional.of(provider);
        return this;
    }

    public HapiGetContractInfo hasExpectedInfo() {
        expectations = Optional.of(ContractInfoAsserts.contractWith().knownInfoFor(contract));
        return this;
    }

    public HapiGetContractInfo savingTo(String dirPath) {
        contractInfoPath = Optional.of(dirPath);
        return this;
    }

    public HapiGetContractInfo saveToRegistry(String registryEntry) {
        this.registryEntry = Optional.of(registryEntry);
        return this;
    }

    public HapiGetContractInfo saveEVMAddressToRegistry(String registryEntry) {
        this.saveEVMAddressToRegistry = Optional.of(registryEntry);
        return this;
    }

    public HapiGetContractInfo checkingAgainst(String dirPath) {
        validateDirPath = Optional.of(dirPath);
        return this;
    }

    public HapiGetContractInfo hasToken(ExpectedTokenRel relationship) {
        relationships.add(relationship);
        return this;
    }

    public HapiGetContractInfo hasNoTokenRelationship(String token) {
        absentRelationships.add(token);
        return this;
    }

    public HapiGetContractInfo exposingEvmAddress(Consumer<String> obs) {
        exposingEvmAddress = Optional.of(obs);
        return this;
    }

    public HapiGetContractInfo exposingContractId(Consumer<ContractID> obs) {
        exposingContractId = obs;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.ContractGetInfo;
    }

    @Override
    protected HapiGetContractInfo self() {
        return this;
    }

    @Override
    @SuppressWarnings("java:S5960")
    protected void assertExpectationsGiven(HapiSpec spec) throws Throwable {
        ContractInfo actualInfo = response.getContractGetInfo().getContractInfo();
        // Since we don't return token relationships from getContractInfo query, for internal testing
        // we are using getAccountDetails query to get token relationships.
        if (!relationships.isEmpty()
                || !absentRelationships.isEmpty()
                || expectations.isPresent()
                || registryEntry.isPresent()) {
            final var detailsLookup = getAccountDetails(
                            String.valueOf(actualInfo.getContractID().getContractNum()))
                    .payingWith(GENESIS);
            CustomSpecAssert.allRunFor(spec, detailsLookup);
            final var response = detailsLookup.getResponse();
            var actualTokenRels =
                    response.getAccountDetails().getAccountDetails().getTokenRelationshipsList();
            assertExpectedRels(contract, relationships, actualTokenRels, spec);
            assertNoUnexpectedRels(contract, absentRelationships, actualTokenRels, spec);
            actualInfo = actualInfo.toBuilder()
                    .clearTokenRelationships()
                    .addAllTokenRelationships(actualTokenRels)
                    .build();
        }
        if (expectations.isPresent()) {
            ErroringAsserts<ContractInfo> asserts = expectations.get().assertsFor(spec);
            List<Throwable> errors = asserts.errorsIn(actualInfo);
            rethrowSummaryError(LOG, "Bad contract info!", errors);
        }
        if (expectedLedgerId.isPresent()) {
            assertEquals(expectedLedgerId.get(), actualInfo.getLedgerId());
        }
        if (registryEntry.isPresent()) {
            spec.registry().saveContractInfo(registryEntry.get(), actualInfo);
        }
    }

    @Override
    protected void updateStateOf(HapiSpec spec) throws Throwable {
        ContractInfo actualInfo = response.getContractGetInfo().getContractInfo();

        if (saveEVMAddressToRegistry.isPresent()) {
            spec.registry()
                    .saveEVMAddress(
                            String.valueOf(actualInfo.getContractID().getContractNum()),
                            actualInfo.getContractAccountID());
        }
    }

    @Override
    protected void processAnswerOnlyResponse(@NonNull final HapiSpec spec) {
        final var contractInfo = response.getContractGetInfo().getContractInfo();
        if (verboseLoggingOn) {
            LOG.info("Info: {}", contractInfo);
        }
        if (contractInfoPath.isPresent()) {
            saveContractInfo(spec, contractInfo);
        }
        if (validateDirPath.isPresent()) {
            validateAgainst(spec, contractInfo);
        }
        exposingEvmAddress.ifPresent(stringConsumer -> stringConsumer.accept(contractInfo.getContractAccountID()));
        if (exposingContractId != null) {
            exposingContractId.accept(contractInfo.getContractID());
        }
    }

    private String specScopedDir(HapiSpec spec, Optional<String> prefix) {
        return prefix.map(d -> d + "/" + spec.getName()).orElseThrow();
    }

    private void saveContractInfo(HapiSpec spec, ContractInfo contractInfo) {
        String specSnapshotDir = specScopedDir(spec, contractInfoPath);
        ensureDir(specSnapshotDir);
        String snapshotDir = specSnapshotDir + "/" + contract;
        ensureDir(snapshotDir);

        try {
            File contractIdFile = new File(snapshotDir + "/contractId.txt");
            ByteSink byteSinkId = Files.asByteSink(contractIdFile);
            byteSinkId.write(contractInfo.getContractID().toByteArray());

            File contractInfoFile = new File(snapshotDir + "/contractInfo.bin");
            ByteSink byteSinkInfo = Files.asByteSink(contractInfoFile);
            byteSinkInfo.write(contractInfo.toByteArray());

            if (verboseLoggingOn) {
                LOG.info("Saved contractInfo of {} to {}", contractInfo.getContractID(), snapshotDir);
            }
        } catch (Exception e) {
            LOG.error("Couldn't save contractInfo of {}", contractInfo.getContractID(), e);
        }
    }

    private void validateAgainst(HapiSpec spec, ContractInfo contractInfo) {
        String specExpectationsDir = specScopedDir(spec, validateDirPath);
        try {
            String expectationsDir = specExpectationsDir + "/" + contract;

            File contractInfoFile = new File(expectationsDir + "/contractInfo.bin");
            ByteSource byteSourceInfo = Files.asByteSource(contractInfoFile);
            ContractInfo savedContractInfo = ContractInfo.parseFrom(byteSourceInfo.read());
            if (verboseLoggingOn) {
                LOG.info("Info: {}", contractInfo);
            }
            Assertions.assertEquals(
                    contractInfo.getAccountID().getAccountNum(),
                    savedContractInfo.getAccountID().getAccountNum());
            Assertions.assertEquals(contractInfo.getStorage(), savedContractInfo.getStorage());
            Assertions.assertEquals(contractInfo.getBalance(), savedContractInfo.getBalance());
        } catch (Exception e) {
            LOG.error("Something amiss with the expected records...", e);
            Assertions.fail("Impossible to meet expectations (on records)!");
        }
    }

    private ContractID readContractID(HapiSpec spec) {
        String specExpectationsDir = specScopedDir(spec, validateDirPath);
        try {
            String expectationsDir = specExpectationsDir + "/" + contract;
            File contractIdFile = new File(expectationsDir + "/contractId.txt");
            ByteSource contractIdByteSource = Files.asByteSource(contractIdFile);
            return ContractID.parseFrom(contractIdByteSource.read());
        } catch (Exception e) {
            LOG.error("Something wrong with the expected ContractInfo file", e);
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Query queryFor(
            @NonNull final HapiSpec spec,
            @NonNull final Transaction payment,
            @NonNull final ResponseType responseType) {
        return getContractInfoQuery(spec, payment, responseType == ResponseType.COST_ANSWER);
    }

    private Query getContractInfoQuery(HapiSpec spec, Transaction payment, boolean costOnly) {
        ContractGetInfoQuery contractGetInfo;
        if (getPredefinedId) {
            var contractID = readContractID(spec);
            if (contractID != null) {
                contractGetInfo = ContractGetInfoQuery.newBuilder()
                        .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment))
                        .setContractID(contractID)
                        .build();
            } else {
                LOG.error("Couldn't read contractID from saved file");
                return null;
            }
        } else {
            final var builder = ContractGetInfoQuery.newBuilder()
                    .setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment));
            builder.setContractID(asContractId(contract, spec));
            contractGetInfo = builder.build();
        }
        return Query.newBuilder().setContractGetInfo(contractGetInfo).build();
    }

    @Override
    protected boolean needsPayment() {
        return true;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("contract", contract);
    }
}
