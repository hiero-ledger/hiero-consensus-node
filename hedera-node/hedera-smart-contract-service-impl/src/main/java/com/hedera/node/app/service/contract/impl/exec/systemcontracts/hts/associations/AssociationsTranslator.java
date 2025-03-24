// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.associations;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates associate and dissociate calls to the HTS system contract. There are no special cases for
 * these calls, so the returned {@link Call} is simply an instance of {@link DispatchForResponseCodeHtsCall}.
 */
@Singleton
public class AssociationsTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /**
     * Selector for associate() method.
     */
    public static final SystemContractMethod HRC_ASSOCIATE = SystemContractMethod.declare(
                    "associate()", ReturnTypes.INT)
            .withVia(CallVia.PROXY)
            .withCategories(Category.ASSOCIATION);
    /**
     * Selector for associateToken(address,address) method.
     */
    public static final SystemContractMethod ASSOCIATE_ONE = SystemContractMethod.declare(
                    "associateToken(address,address)", ReturnTypes.INT_64)
            .withCategories(Category.ASSOCIATION);
    /**
     * Selector for dissociateToken(address,address) method.
     */
    public static final SystemContractMethod DISSOCIATE_ONE = SystemContractMethod.declare(
                    "dissociateToken(address,address)", ReturnTypes.INT_64)
            .withCategories(Category.ASSOCIATION);
    /**
     * Selector for dissociate() method.
     */
    public static final SystemContractMethod HRC_DISSOCIATE = SystemContractMethod.declare(
                    "dissociate()", ReturnTypes.INT)
            .withVia(CallVia.PROXY)
            .withCategories(Category.ASSOCIATION);
    /**
     * Selector for associateTokens(address,address[]) method.
     */
    public static final SystemContractMethod ASSOCIATE_MANY = SystemContractMethod.declare(
                    "associateTokens(address,address[])", ReturnTypes.INT_64)
            .withCategories(Category.ASSOCIATION);
    /**
     * Selector for dissociateTokens(address,address[]) method.
     */
    public static final SystemContractMethod DISSOCIATE_MANY = SystemContractMethod.declare(
                    "dissociateTokens(address,address[])", ReturnTypes.INT_64)
            .withCategories(Category.ASSOCIATION);

    private final AssociationsDecoder decoder;

    /**
     * Constructor for injection.
     * @param decoder
     */
    @Inject
    public AssociationsTranslator(
            @NonNull final AssociationsDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;

        registerMethods(ASSOCIATE_ONE, ASSOCIATE_MANY, DISSOCIATE_ONE, DISSOCIATE_MANY, HRC_ASSOCIATE, HRC_DISSOCIATE);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isRedirect()
                ? attempt.isMethod(HRC_ASSOCIATE, HRC_DISSOCIATE)
                : attempt.isMethod(ASSOCIATE_ONE, ASSOCIATE_MANY, DISSOCIATE_ONE, DISSOCIATE_MANY);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        return new DispatchForResponseCodeHtsCall(
                attempt,
                attempt.isSelector(HRC_ASSOCIATE, HRC_DISSOCIATE) ? bodyForHrc(attempt) : bodyForClassic(attempt),
                AssociationsTranslator::gasRequirement);
    }

    /**
     * @param body                          the transaction body to be dispatched
     * @param systemContractGasCalculator   the gas calculator for the system contract
     * @param enhancement                   the enhancement to use
     * @param payerId                       the payer of the transaction
     * @return the required gas
     */
    public static long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.ASSOCIATE, payerId);
    }

    private TransactionBody bodyForHrc(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(HRC_ASSOCIATE)) {
            return decoder.decodeHrcAssociate(attempt);
        } else {
            return decoder.decodeHrcDissociate(attempt);
        }
    }

    private TransactionBody bodyForClassic(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(ASSOCIATE_ONE)) {
            return decoder.decodeAssociateOne(attempt);
        } else if (attempt.isSelector(ASSOCIATE_MANY)) {
            return decoder.decodeAssociateMany(attempt);
        } else if (attempt.isSelector(DISSOCIATE_ONE)) {
            return decoder.decodeDissociateOne(attempt);
        } else {
            return decoder.decodeDissociateMany(attempt);
        }
    }
}
