// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.addressbook;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.state.address.AddressBookInitializer.CONFIG_ADDRESS_BOOK_HEADER;
import static com.swirlds.platform.state.address.AddressBookInitializer.CONFIG_ADDRESS_BOOK_USED;
import static com.swirlds.platform.state.address.AddressBookInitializer.STATE_ADDRESS_BOOK_HEADER;
import static com.swirlds.platform.state.address.AddressBookInitializer.STATE_ADDRESS_BOOK_USED;
import static com.swirlds.platform.state.address.AddressBookInitializer.USED_ADDRESS_BOOK_HEADER;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.config.AddressBookConfig;
import com.swirlds.platform.roster.RosterRetriever;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.address.AddressBookUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.ByteUtils;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.model.utility.StackTrace;

/**
 * This class is responsible for processing lifecycle events for the {@link AddressBookTestingToolState}.
 */
public class AddressBookTestingToolConsensusStateEventHandler
        implements ConsensusStateEventHandler<AddressBookTestingToolState> {

    private static final Logger logger = LogManager.getLogger(AddressBookTestingToolConsensusStateEventHandler.class);

    /** the suffix for the debug address book */
    private static final String DEBUG = "debug";

    /** flag indicating if weighting behavior has been logged. */
    private static final AtomicBoolean logWeightingBehavior = new AtomicBoolean(true);

    /** the suffix for the test address book */
    private AddressBookTestingToolConfig testingToolConfig;
    /** the address book configuration */
    private AddressBookConfig addressBookConfig;
    /**
     * If not zero and we are handling the first round after genesis, configure a freeze this duration later.
     * <p>
     * Does not affect the hash of this node (although actions may be taken based on this info that DO affect the
     * hash).
     */
    private Duration freezeAfterGenesis = null;

    private NodeId selfId;

    /** false until the test scenario has been validated, true afterwards. */
    private final AtomicBoolean validationPerformed = new AtomicBoolean(false);

    private Platform platform = null;
    private PlatformContext context = null;

    private final PlatformStateFacade platformStateFacade;

    /**
     * @param platformStateFacade platform state facade
     */
    public AddressBookTestingToolConsensusStateEventHandler(@NonNull final PlatformStateFacade platformStateFacade) {
        this.platformStateFacade = platformStateFacade;
    }

    @Override
    public void onStateInitialized(
            @NonNull final AddressBookTestingToolState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        requireNonNull(platform, "the platform cannot be null");
        requireNonNull(trigger, "the init trigger cannot be null");
        addressBookConfig = platform.getContext().getConfiguration().getConfigData(AddressBookConfig.class);
        testingToolConfig = platform.getContext().getConfiguration().getConfigData(AddressBookTestingToolConfig.class);
        this.freezeAfterGenesis = testingToolConfig.freezeAfterGenesis();

        this.platform = platform;
        this.context = platform.getContext();

        logger.info(STARTUP.getMarker(), "init called in State.");
        state.throwIfImmutable();

        this.selfId = platform.getSelfId();

        state.initState();

        // Since this demo State doesn't call Hedera.onStateInitialized() to init States API for all services
        // (because it doesn't call super.init(), and the FakeConsensusStateEventHandler doesn't do that anyway),
        // we need to register PlatformService and RosterService states for the rest of the code to operate
        // when an instance of this state is received via reconnect. In any other cases, this call
        // should be idempotent.
        SignedStateFileReader.registerServiceStates(state);
        logger.info(STARTUP.getMarker(), "Registered PlatformService and RosterService states.");
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull Round round,
            @NonNull AddressBookTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        requireNonNull(round, "the round cannot be null");
        requireNonNull(state, "the state cannot be null");
        state.throwIfImmutable();

        if (state.getRoundsHandled() == 0 && !freezeAfterGenesis.equals(Duration.ZERO)) {
            // This is the first round after genesis.
            logger.info(
                    STARTUP.getMarker(),
                    "Setting freeze time to {} seconds after genesis.",
                    freezeAfterGenesis.getSeconds());
            platformStateFacade.bulkUpdateOf(state, v -> {
                v.setFreezeTime(round.getConsensusTimestamp().plus(freezeAfterGenesis));
            });
        }

        state.incrementRoundsHandled();

        for (final var event : round) {
            event.consensusTransactionIterator().forEachRemaining(transaction -> {
                // We should consume in the callback the new form of system transactions in Bytes
                if (state.areTransactionBytesSystemOnes(transaction)) {
                    consumeSystemTransaction(transaction, event, stateSignatureTransactionCallback);
                } else {
                    handleTransaction(transaction, state);
                }
            });
        }

        if (!validationPerformed.getAndSet(true)) {
            String testScenario = testingToolConfig.testScenario();
            if (validateTestScenario(state)) {
                logger.info(STARTUP.getMarker(), "Test scenario {}: finished without errors.", testScenario);
            } else {
                logger.error(EXCEPTION.getMarker(), "Test scenario {}: validation failed with errors.", testScenario);
            }
        }
    }

    /**
     * Apply a transaction to the state.
     *
     * @param transaction the transaction to apply
     */
    private void handleTransaction(
            @NonNull final ConsensusTransaction transaction, @NonNull final AddressBookTestingToolState state) {
        final int delta =
                ByteUtils.byteArrayToInt(transaction.getApplicationTransaction().toByteArray(), 0);
        state.incrementRunningSum(delta);
    }

    @Override
    public void onUpdateWeight(
            @NonNull AddressBookTestingToolState state,
            @NonNull AddressBook addressBook,
            @NonNull PlatformContext context) {
        Objects.requireNonNull(addressBook, "the address book cannot be null");
        this.context = Objects.requireNonNull(context, "the platform context cannot be null");
        final int weightingBehavior = context.getConfiguration()
                .getConfigData(AddressBookTestingToolConfig.class)
                .weightingBehavior();
        logger.info(DEMO_INFO.getMarker(), "updateWeight called in State. Weighting Behavior: {}", weightingBehavior);
        switch (weightingBehavior) {
            case 1:
                weightingBehavior1(addressBook);
                return;
            case 2:
                weightingBehavior2(addressBook);
                return;
            default:
                logger.info(
                        STARTUP.getMarker(), "Weighting Behavior {}: no change to address book.", weightingBehavior);
        }
    }

    /**
     * All nodes received 10 weight.
     *
     * @param addressBook the address book to update.
     */
    private void weightingBehavior1(@NonNull final AddressBook addressBook) {
        if (logWeightingBehavior.get()) {
            logger.info(STARTUP.getMarker(), "Weighting Behavior 1: updating all nodes to have 10 weight.");
        }
        for (final Address address : addressBook) {
            addressBook.updateWeight(address.getNodeId(), 10);
        }
    }

    /**
     * All nodes received weight equal to their nodeId.
     *
     * @param addressBook the address book to update.
     */
    private void weightingBehavior2(@NonNull final AddressBook addressBook) {
        if (logWeightingBehavior.get()) {
            logger.info(
                    STARTUP.getMarker(),
                    "Weighting Behavior 2: updating all nodes to have weight equal to their nodeId.");
        }
        for (final Address address : addressBook) {
            addressBook.updateWeight(address.getNodeId(), address.getNodeId().id());
        }
    }

    private boolean validateTestScenario(AddressBookTestingToolState state) {
        if (platform == null) {
            throw new IllegalStateException("platform is null, init has not been called.");
        }
        logWeightingBehavior.set(false);
        final AddressBookTestScenario testScenario = AddressBookTestScenario.valueOf(testingToolConfig.testScenario());
        try {
            logger.info(DEMO_INFO.getMarker(), "Validating test scenario {}.", testScenario);
            switch (testScenario) {
                case GENESIS_FORCE_CONFIG_AB:
                    return genesisForceUseOfConfigAddressBookTrue(testScenario, state);
                case GENESIS_NORMAL:
                    return genesisForceUseOfConfigAddressBookFalse(testScenario, state);
                case NO_UPGRADE_USE_SAVED_STATE:
                    return noSoftwareUpgradeUseSavedStateAddressBook(testScenario, state);
                case NO_UPGRADE_FORCE_CONFIG_AB:
                    return noSoftwareUpgradeForceUseOfConfigAddressBook(testScenario, state);
                case UPGRADE_WEIGHT_BEHAVIOR_2:
                    return softwareUpgradeWeightingBehavior2(testScenario, state);
                case UPGRADE_FORCE_CONFIG_AB:
                    return softwareUpgradeForceUseOfConfigAddressBook(testScenario, state);
                case UPGRADE_ADD_NODE:
                    return softwareUpgradeAddNodeWeightingBehavior1(testScenario, state);
                case UPGRADE_REMOVE_NODE:
                    return softwareUpgradeRemoveNodeWeightingBehavior1(testScenario, state);
                case SKIP_VALIDATION:
                    // fall into default case. No validation performed.
                default:
                    logger.info(DEMO_INFO.getMarker(), "Test Scenario {}: no validation performed.", testScenario);
                    return true;
            }
        } catch (final Exception e) {
            logger.error(EXCEPTION.getMarker(), "Exception occurred in Test Scenario {}.", testScenario, e);
            return false;
        }
    }

    private boolean softwareUpgradeRemoveNodeWeightingBehavior1(
            @NonNull final AddressBookTestScenario testScenario, @NonNull final AddressBookTestingToolState state)
            throws IOException, ParseException {
        if (!checkTestScenarioConditions(false, testScenario, 2, 1)) {
            return false;
        }

        final AddressBook platformAddressBook = RosterUtils.buildAddressBook(platform.getRoster());
        final AddressBook configAddressBook = getConfigAddressBook();
        final AddressBook stateAddressBook = getStateAddressBook();
        final AddressBook usedAddressBook = getUsedAddressBook();
        final AddressBook updatedAddressBook = configAddressBook.copy();
        onUpdateWeight(state, updatedAddressBook, context);

        return equalsAsRoster(platformAddressBook, configAddressBook, true)
                && equalsAsRoster(platformAddressBook, stateAddressBook, false)
                && equalsAsRoster(platformAddressBook, usedAddressBook, true)
                && equalsAsRoster(platformAddressBook, updatedAddressBook, true)
                && removedNodeFromAddressBook(platformAddressBook, stateAddressBook);
    }

    private boolean softwareUpgradeAddNodeWeightingBehavior1(
            @NonNull final AddressBookTestScenario testScenario, @NonNull final AddressBookTestingToolState state)
            throws IOException, ParseException {
        if (!checkTestScenarioConditions(false, testScenario, 2, 1)) {
            return false;
        }

        final AddressBook platformAddressBook = RosterUtils.buildAddressBook(platform.getRoster());
        final AddressBook configAddressBook = getConfigAddressBook();
        final AddressBook stateAddressBook = getStateAddressBook();
        final AddressBook usedAddressBook = getUsedAddressBook();

        final AddressBook updatedAddressBook = configAddressBook.copy();
        onUpdateWeight(state, updatedAddressBook, context);
        if (equalsAsRosterWithoutCert(stateAddressBook, configAddressBook)) {
            // This is a new node.
            return equalsAsRoster(platformAddressBook, configAddressBook, true)
                    // genesis state uses the config address book.
                    && equalsAsRoster(platformAddressBook, stateAddressBook, true)
                    && equalsAsRoster(platformAddressBook, usedAddressBook, true)
                    && equalsAsRoster(platformAddressBook, updatedAddressBook, true);
        } else {
            // This is an existing node.

            // The equality to config text is due to a limitation of testing capability.
            // Currently all nodes have to start with the same config.txt file that matches the updated weight.
            return equalsAsRoster(platformAddressBook, configAddressBook, true)
                    && equalsAsRoster(platformAddressBook, stateAddressBook, false)
                    && equalsAsRoster(platformAddressBook, usedAddressBook, true)
                    && equalsAsRoster(platformAddressBook, updatedAddressBook, true)
                    && addedNodeToAddressBook(platformAddressBook, stateAddressBook);
        }
    }

    private boolean softwareUpgradeForceUseOfConfigAddressBook(
            @NonNull final AddressBookTestScenario testScenario, @NonNull final AddressBookTestingToolState state)
            throws IOException, ParseException {
        if (!checkTestScenarioConditions(true, testScenario, 2, 2)) {
            return false;
        }

        final AddressBook platformAddressBook = RosterUtils.buildAddressBook(platform.getRoster());
        final AddressBook configAddressBook = getConfigAddressBook();
        final AddressBook stateAddressBook = getStateAddressBook();
        final AddressBook usedAddressBook = getUsedAddressBook();
        final AddressBook updatedAddressBook = configAddressBook.copy();
        onUpdateWeight(state, updatedAddressBook, context);

        return equalsAsRoster(platformAddressBook, configAddressBook, true)
                && equalsAsRoster(platformAddressBook, stateAddressBook, true)
                && equalsAsRoster(platformAddressBook, usedAddressBook, true)
                && equalsAsRoster(platformAddressBook, updatedAddressBook, false)
                && theConfigurationAddressBookWasUsed();
    }

    private boolean softwareUpgradeWeightingBehavior2(
            @NonNull final AddressBookTestScenario testScenario, @NonNull final AddressBookTestingToolState state)
            throws IOException, ParseException {
        if (!checkTestScenarioConditions(false, testScenario, 2, 2)) {
            return false;
        }

        final AddressBook platformAddressBook = RosterUtils.buildAddressBook(platform.getRoster());
        final AddressBook configAddressBook = getConfigAddressBook();
        final AddressBook stateAddressBook = getStateAddressBook();
        final AddressBook usedAddressBook = getUsedAddressBook();
        final AddressBook updatedAddressBook = configAddressBook.copy();
        onUpdateWeight(state, updatedAddressBook, context);

        return equalsAsRoster(platformAddressBook, configAddressBook, false)
                && equalsAsRoster(platformAddressBook, stateAddressBook, false)
                && equalsAsRoster(platformAddressBook, usedAddressBook, true)
                && equalsAsRoster(platformAddressBook, updatedAddressBook, true);
    }

    private boolean noSoftwareUpgradeForceUseOfConfigAddressBook(
            @NonNull final AddressBookTestScenario testScenario, @NonNull final AddressBookTestingToolState state)
            throws IOException, ParseException {
        if (!checkTestScenarioConditions(true, testScenario, 1, 1)) {
            return false;
        }

        final AddressBook platformAddressBook = RosterUtils.buildAddressBook(platform.getRoster());
        final AddressBook configAddressBook = getConfigAddressBook();
        final AddressBook stateAddressBook = getStateAddressBook();
        final AddressBook usedAddressBook = getUsedAddressBook();
        final AddressBook updatedAddressBook = configAddressBook.copy();
        onUpdateWeight(state, updatedAddressBook, context);

        return equalsAsRoster(platformAddressBook, configAddressBook, true)
                && equalsAsRoster(platformAddressBook, stateAddressBook, true)
                && equalsAsRoster(platformAddressBook, usedAddressBook, true)
                && equalsAsRoster(platformAddressBook, updatedAddressBook, false)
                && theConfigurationAddressBookWasUsed();
    }

    private boolean noSoftwareUpgradeUseSavedStateAddressBook(
            AddressBookTestScenario testScenario, @NonNull final AddressBookTestingToolState state)
            throws IOException, ParseException {
        if (!checkTestScenarioConditions(false, testScenario, 1, 1)) {
            return false;
        }

        final AddressBook platformAddressBook = RosterUtils.buildAddressBook(platform.getRoster());
        final AddressBook configAddressBook = getConfigAddressBook();
        final AddressBook stateAddressBook = getStateAddressBook();
        final AddressBook usedAddressBook = getUsedAddressBook();
        final AddressBook updatedAddressBook = configAddressBook.copy();
        onUpdateWeight(state, updatedAddressBook, context);

        return equalsAsRoster(platformAddressBook, configAddressBook, true)
                && equalsAsRoster(platformAddressBook, stateAddressBook, true)
                && equalsAsRoster(platformAddressBook, usedAddressBook, true)
                && equalsAsRoster(platformAddressBook, updatedAddressBook, false)
                && theStateAddressBookWasUsed();
    }

    private boolean genesisForceUseOfConfigAddressBookFalse(
            @NonNull final AddressBookTestScenario testScenario, @NonNull final AddressBookTestingToolState state)
            throws IOException, ParseException {
        if (!checkTestScenarioConditions(false, testScenario, 1, 1)) {
            return false;
        }

        final AddressBook platformAddressBook = RosterUtils.buildAddressBook(platform.getRoster());
        final AddressBook configAddressBook = getConfigAddressBook();
        final AddressBook stateAddressBook = getStateAddressBook();
        final AddressBook usedAddressBook = getUsedAddressBook();
        final AddressBook updatedAddressBook = configAddressBook.copy();
        onUpdateWeight(state, updatedAddressBook, context);

        return equalsAsRoster(platformAddressBook, configAddressBook, true)
                && equalsAsRoster(platformAddressBook, usedAddressBook, true)
                && equalsAsRoster(platformAddressBook, stateAddressBook, true)
                && equalsAsRoster(platformAddressBook, updatedAddressBook, false);
    }

    private boolean genesisForceUseOfConfigAddressBookTrue(
            @NonNull final AddressBookTestScenario testScenario, @NonNull final AddressBookTestingToolState state)
            throws IOException, ParseException {
        if (!checkTestScenarioConditions(true, testScenario, 1, 1)) {
            return false;
        }

        final AddressBook platformAddressBook = RosterUtils.buildAddressBook(platform.getRoster());
        final AddressBook configAddressBook = getConfigAddressBook();
        final AddressBook stateAddressBook = getStateAddressBook();
        final AddressBook usedAddressBook = getUsedAddressBook();
        final AddressBook updatedAddressBook = configAddressBook.copy();
        onUpdateWeight(state, updatedAddressBook, context);

        return equalsAsRoster(platformAddressBook, configAddressBook, true)
                && equalsAsRoster(platformAddressBook, usedAddressBook, true)
                && equalsAsRoster(platformAddressBook, stateAddressBook, true)
                && equalsAsRoster(platformAddressBook, updatedAddressBook, false)
                && theConfigurationAddressBookWasUsed();
    }

    /**
     * Check the test scenario preconditions.
     *
     * @param forceUseConfigAddressBook the expected value of `addressBook.forceUseOfConfigAddressBook`
     * @param testScenario              the expected value of `testingTool.testScenario`
     * @param softwareVersion           the expected value of `testingTool.softwareVersion`
     * @param weightingBehavior         the expected value of `testingTool.weightingBehavior`
     * @return true if the preconditions are met, false otherwise
     */
    private boolean checkTestScenarioConditions(
            final boolean forceUseConfigAddressBook,
            final AddressBookTestScenario testScenario,
            final int softwareVersion,
            final int weightingBehavior) {
        boolean passed = true;
        if (addressBookConfig.forceUseOfConfigAddressBook() != forceUseConfigAddressBook) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "The test scenario requires the setting `addressBook.forceUseOfConfigAddressBook, {}`",
                    forceUseConfigAddressBook);
            passed = false;
        }
        if (!testScenario.toString().equals(testingToolConfig.testScenario())) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "The test scenario requires the setting `testingTool.testScenario, {}`",
                    testScenario);
            passed = false;
        }
        if (testingToolConfig.softwareVersion() != softwareVersion) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "The test scenario requires the setting `testingTool.softwareVersion, {}`",
                    softwareVersion);
            passed = false;
        }
        if (testingToolConfig.weightingBehavior() != weightingBehavior) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "The test scenario requires the setting `testingTool.weightingBehavior, {}`",
                    weightingBehavior);
            passed = false;
        }
        return passed;
    }

    /**
     * Remove certificates from a roster.
     * @param roster an input roster
     * @return the same roster as input but with all gossip certs removed
     */
    private Roster removeCerts(@NonNull final Roster roster) {
        return roster.copyBuilder()
                .rosterEntries(roster.rosterEntries().stream()
                        .map(re -> re.copyBuilder()
                                .gossipCaCertificate(Bytes.EMPTY)
                                .build())
                        .toList())
                .build();
    }

    /**
     * Compare two AddressBooks after converting them to Rosters and removing all certificates
     * to avoid mismatches for fields absent from the Roster.
     * @param addressBook1 the first address book
     * @param addressBook2 the second address book
     * @return true if equals, false otherwise
     */
    private boolean equalsAsRosterWithoutCert(
            @NonNull final AddressBook addressBook1, @NonNull final AddressBook addressBook2) {
        final Roster roster1 = removeCerts(RosterRetriever.buildRoster(addressBook1));
        final Roster roster2 = removeCerts(RosterRetriever.buildRoster(addressBook2));
        return roster1.equals(roster2);
    }

    /**
     * This test compares the equality of two address books against the expected result.
     *
     * @param addressBook1 the first address book
     * @param addressBook2 the second address book
     * @return true if the comparison matches the expected result, false otherwise.
     */
    private boolean equalsAsRoster(
            @NonNull final AddressBook addressBook1,
            @NonNull final AddressBook addressBook2,
            final boolean expectedResult) {
        final boolean pass = equalsAsRosterWithoutCert(addressBook1, addressBook2) == expectedResult;
        if (!pass) {
            if (expectedResult) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "The address books are not equal as Roster. {}",
                        StackTrace.getStackTrace());
            } else {
                logger.error(
                        EXCEPTION.getMarker(), "The address books are equal as Roster. {}", StackTrace.getStackTrace());
            }
        }
        return pass;
    }

    /**
     * Checks if the state address book was used.
     *
     * @return true if the state address book was used, false otherwise.
     */
    private boolean theStateAddressBookWasUsed() throws IOException {
        final String fileContents = getLastAddressBookFileEndsWith(DEBUG);
        final String textAfterUsedHeader = getTextAfterHeader(fileContents, USED_ADDRESS_BOOK_HEADER);
        final boolean pass = textAfterUsedHeader.contains(STATE_ADDRESS_BOOK_USED);
        if (!pass) {
            logger.error(EXCEPTION.getMarker(), "The state address book was not used. {}", StackTrace.getStackTrace());
        }
        return pass;
    }

    /**
     * Checks if the configuration address book was used.
     *
     * @return true if the configuration address book was used, false otherwise.
     */
    private boolean theConfigurationAddressBookWasUsed() throws IOException {
        final String fileContents = getLastAddressBookFileEndsWith(DEBUG);
        final String textAfterUsedHeader = getTextAfterHeader(fileContents, USED_ADDRESS_BOOK_HEADER);
        final boolean pass = textAfterUsedHeader.contains(CONFIG_ADDRESS_BOOK_USED);
        if (!pass) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "The configuration address book was not used. {}",
                    StackTrace.getStackTrace());
        }
        return pass;
    }

    /**
     * Checks if the new address book contains a proper subset of the node ids in the old address book and that the
     * nextNodeId value of the new address book is the same.
     *
     * @param newAddressBook The new address book
     * @param oldAddressBook The old address book
     * @return true if the new address book has all the nodes in the old address book (ignoring weight differences) plus
     * at least one more and its nextNodeId value is larger.
     */
    private boolean removedNodeFromAddressBook(
            @NonNull final AddressBook newAddressBook, @NonNull final AddressBook oldAddressBook) {
        int missingCount = 0;
        for (final Address address : oldAddressBook) {
            final NodeId nodeId = address.getNodeId();
            if (newAddressBook.contains(nodeId)) {
                continue;
            }
            missingCount++;
        }
        final int newSize = newAddressBook.getSize();
        final int oldSize = oldAddressBook.getSize();
        final boolean atLeastOneNodeRemoved = missingCount > 0;
        if (!atLeastOneNodeRemoved) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "The new address book does not have at least one node removed. {}",
                    StackTrace.getStackTrace());
        }
        final boolean sizesCorrespond = missingCount == oldSize - newSize;
        if (!sizesCorrespond) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "The new address book contains new nodes instead of only having nodes removed. {}",
                    StackTrace.getStackTrace());
        }
        return sizesCorrespond && atLeastOneNodeRemoved;
    }

    /**
     * Checks if the old address book contains a proper subset of node ids in the new address book and that the
     * nextNodeId value of the new address book is larger.
     *
     * @param newAddressBook The new address book
     * @param oldAddressBook The old address book
     * @return true if the new address book has all the nodes in the old address book (ignoring weight differences) plus
     * at least one more and its nextNodeId value is larger.
     */
    private boolean addedNodeToAddressBook(
            @NonNull final AddressBook newAddressBook, @NonNull final AddressBook oldAddressBook) {
        int missingCount = 0;
        for (final Address address : newAddressBook) {
            final NodeId nodeId = address.getNodeId();
            if (oldAddressBook.contains(nodeId)) {
                continue;
            }
            missingCount++;
        }
        final int newSize = newAddressBook.getSize();
        final int oldSize = oldAddressBook.getSize();
        final boolean atLeastOneNodeAdded = missingCount > 0;
        if (!atLeastOneNodeAdded) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "The new address book does not have at least one node added. {}",
                    StackTrace.getStackTrace());
        }
        final boolean sizesCorrespond = missingCount == newSize - oldSize;
        if (!sizesCorrespond) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "The new address book has nodes removed instead of only having nodes added. {}",
                    StackTrace.getStackTrace());
        }
        return sizesCorrespond && atLeastOneNodeAdded;
    }

    /**
     * Get the address book in the last usedAddressBook file.
     *
     * @return the address book in the last usedAddressBook file.
     */
    @NonNull
    private AddressBook getUsedAddressBook() throws IOException, ParseException {
        final String fileContents = getLastAddressBookFileEndsWith("txt");
        return parseAddressBook(fileContents);
    }

    /**
     * Get the config address book from the last debug addressBook file.
     *
     * @return the config address book from the last debug addressBook file.
     */
    @NonNull
    private AddressBook getConfigAddressBook() throws IOException, ParseException {
        return getDebugAddressBookAfterHeader(CONFIG_ADDRESS_BOOK_HEADER);
    }

    /**
     * Get the state address book from the last debug addressBook file.
     *
     * @return the state address book from the last debug addressBook file.
     */
    @NonNull
    private AddressBook getStateAddressBook() throws IOException, ParseException {
        return getDebugAddressBookAfterHeader(STATE_ADDRESS_BOOK_HEADER);
    }

    /**
     * Get the address book in the last debug addressBook file after the header.
     *
     * @param header the header to find.
     * @return the address book in the last debug addressBook file after the header.
     */
    @NonNull
    AddressBook getDebugAddressBookAfterHeader(@NonNull final String header) throws IOException, ParseException {
        final String fileContents = getLastAddressBookFileEndsWith(DEBUG);
        final String addressBookString = getTextAfterHeader(fileContents, header);
        return parseAddressBook(addressBookString);
    }

    /**
     * Get the text from the fileContents after the header.
     *
     * @param fileContents the file contents.
     * @param header       the header to find.
     * @return the text from the fileContents after the header.
     */
    @NonNull
    String getTextAfterHeader(@NonNull final String fileContents, @NonNull final String header) {
        Objects.requireNonNull(fileContents, "fileContents must not be null");
        Objects.requireNonNull(header, "header must not be null");
        final int configABHeaderStart = fileContents.indexOf(CONFIG_ADDRESS_BOOK_HEADER);
        final int stateABHeaderStart = fileContents.indexOf(STATE_ADDRESS_BOOK_HEADER);
        final int usedABHeaderStart = fileContents.indexOf(USED_ADDRESS_BOOK_HEADER);

        final int headerStartIndex = fileContents.indexOf(header);
        final int addressBookStartIndex = headerStartIndex + header.length();

        final int addressBookEndIndex;
        if (headerStartIndex == configABHeaderStart) {
            addressBookEndIndex = stateABHeaderStart;
        } else if (headerStartIndex == stateABHeaderStart) {
            addressBookEndIndex = usedABHeaderStart;
        } else {
            addressBookEndIndex = fileContents.length();
        }

        return fileContents
                .substring(addressBookStartIndex, addressBookEndIndex)
                .trim();
    }

    /**
     * Parse the address book from the given string.
     *
     * @param addressBookString the address book string.
     * @return the address book.
     * @throws ParseException if unable to parse the address book.
     */
    @NonNull
    private AddressBook parseAddressBook(@NonNull final String addressBookString) throws ParseException {
        Objects.requireNonNull(addressBookString, "addressBookString must not be null");
        return AddressBookUtils.parseAddressBookText(addressBookString);
    }

    /**
     * Get the last address book file that ends with the given suffix.
     *
     * @param suffix the suffix to match.
     * @return the last address book file that ends with the given suffix.
     * @throws IOException if unable to read the file.
     */
    @NonNull
    private String getLastAddressBookFileEndsWith(@NonNull final String suffix) throws IOException {
        final String nodeId = "node_%s".formatted(this.selfId);
        final Path addressBookDirectory = Path.of(addressBookConfig.addressBookDirectory());
        File[] files = addressBookDirectory.toFile().listFiles(File::isFile);
        final AtomicReference<Path> lastAddressBookDebugFile = new AtomicReference<>(null);
        for (int i = 0; i < files.length; i++) {
            final String fileName = files[i].getName();
            if (fileName.contains(nodeId) && fileName.endsWith(suffix)) {
                final Path lastAddressBookDebugFilePath = lastAddressBookDebugFile.get();
                if (lastAddressBookDebugFilePath == null
                        || fileName.compareTo(lastAddressBookDebugFilePath
                                        .getFileName()
                                        .toString())
                                > 0) {
                    lastAddressBookDebugFile.set(files[i].toPath());
                }
            }
        }
        return Files.readString(lastAddressBookDebugFile.get());
    }

    @Override
    public void onPreHandle(
            @NonNull Event event,
            @NonNull AddressBookTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        event.transactionIterator().forEachRemaining(transaction -> {
            // We should consume in the callback the new form of system transactions in Bytes
            if (state.areTransactionBytesSystemOnes(transaction)) {
                consumeSystemTransaction(transaction, event, stateSignatureTransactionCallback);
            }
        });
    }

    /**
     * Converts a transaction to a {@link StateSignatureTransaction} and then consumes it into a callback.
     *
     * @param transaction                       the transaction to consume
     * @param event                             the event that contains the transaction
     * @param stateSignatureTransactionCallback the callback to call with the system transaction
     */
    private void consumeSystemTransaction(
            final Transaction transaction,
            final Event event,
            final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        try {
            final var stateSignatureTransaction =
                    StateSignatureTransaction.PROTOBUF.parse(transaction.getApplicationTransaction());
            stateSignatureTransactionCallback.accept(new ScopedSystemTransaction<>(
                    event.getCreatorId(), event.getSoftwareVersion(), stateSignatureTransaction));
        } catch (final com.hedera.pbj.runtime.ParseException e) {
            logger.error("Failed to parse StateSignatureTransaction", e);
        }
    }

    @Override
    public boolean onSealConsensusRound(@NonNull Round round, @NonNull AddressBookTestingToolState state) {
        // no-op
        return true;
    }

    @Override
    public void onNewRecoveredState(@NonNull AddressBookTestingToolState recoveredState) {
        // no-op
    }
}
