// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.address;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.roster.RosterRetriever.retrieveActiveOrGenesisRoster;
import static com.swirlds.platform.roster.RosterUtils.buildAddressBook;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.config.AddressBookConfig;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.address.AddressBookValidator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * Determines the initial address book to use at platform start and validates it.
 */
public class AddressBookInitializer {

    /** The header before the config address book in the usedAddressBook file. */
    public static final String CONFIG_ADDRESS_BOOK_HEADER = "--- Configuration Address Book ---";
    /** The header before the state address book in the usedAddressBook file. */
    public static final String STATE_ADDRESS_BOOK_HEADER = "--- State Saved Address Book ---";
    /** The header before the used address book in the usedAddressBook file. */
    public static final String USED_ADDRESS_BOOK_HEADER = "--- Used Address Book ---";
    /** The text indicating the config address book was used in the usedAddressBook file. */
    public static final String CONFIG_ADDRESS_BOOK_USED = "The Configuration Address Book Was Used.";
    /** The text indicating the state address book was used in the usedAddressBook file. */
    public static final String STATE_ADDRESS_BOOK_USED = "The State Saved Address Book Was Used.";
    /** The text indicate the state address book was null. */
    public static final String STATE_ADDRESS_BOOK_NULL = "The State Saved Address Book Was NULL.";
    /** The file name prefix to use when creating address book files. */
    private static final String ADDRESS_BOOK_FILE_PREFIX = "usedAddressBook";
    /** The format of date and time to use when creating address book files. */
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss").withZone(ZoneId.systemDefault());
    /** For logging info, warn, and error. */
    private static final Logger logger = LogManager.getLogger(AddressBookInitializer.class);
    /** The id of this node. */
    private final NodeId selfId;
    /** The context for the platform */
    @NonNull
    private final PlatformContext platformContext;

    @NonNull
    private final ConsensusStateEventHandler consensusStateEventHandler;
    /** Indicate that the software version has upgraded. */
    private final boolean softwareUpgrade;
    /** The initial state. Must not be null. */
    @NonNull
    private final SignedState initialState;
    /** The address book in the state. May be null only at genesis */
    @Nullable
    private final AddressBook stateAddressBook;
    /** The address book derived from config.txt */
    @NonNull
    private final AddressBook configAddressBook;
    /** The address book determined for use after {@link AddressBookInitializer#initialize()} is called. */
    @NonNull
    private final AddressBook currentAddressBook;
    /** The address book used before the restart */
    @Nullable
    private final AddressBook previousAddressBook;
    /** The path to the directory for writing address books. */
    @NonNull
    private final Path pathToAddressBookDirectory;
    /** The maximum number of address book files to keep in the address book directory. */
    private final int maxNumFiles;
    /** Indicate that the unmodified config address book must be used. */
    private final boolean useConfigAddressBook;
    /** Indicates that one of the address books has changed */
    private boolean addressBookChange = false;

    /**
     * Constructs an AddressBookInitializer to initialize an address book from config.txt, the saved state from disk, or
     * the state on upgrade.
     *
     * @param selfId The id of this node.
     * @param softwareUpgrade Indicate that the software version has upgraded.
     * @param initialState The initial state to start from.
     * @param configAddressBook The address book derived from config.txt.
     * @param platformContext The context for the platform.
     */
    public AddressBookInitializer(
            @NonNull final NodeId selfId,
            final boolean softwareUpgrade,
            @NonNull final SignedState initialState,
            @NonNull final AddressBook configAddressBook,
            @NonNull final PlatformContext platformContext,
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final PlatformStateFacade platformStateFacade) {
        this.selfId = Objects.requireNonNull(selfId, "The selfId must not be null.");
        this.softwareUpgrade = softwareUpgrade;
        this.configAddressBook = Objects.requireNonNull(configAddressBook, "The configAddressBook must not be null.");
        this.platformContext = Objects.requireNonNull(platformContext, "The platformContext must not be null.");
        this.consensusStateEventHandler = consensusStateEventHandler;
        final AddressBookConfig addressBookConfig =
                platformContext.getConfiguration().getConfigData(AddressBookConfig.class);
        this.initialState = Objects.requireNonNull(initialState, "The initialState must not be null.");

        final var book = buildAddressBook(retrieveActiveOrGenesisRoster(initialState.getState(), platformStateFacade));
        this.stateAddressBook = (book == null || book.getSize() == 0) ? null : book;
        if (stateAddressBook == null && !initialState.isGenesisState()) {
            throw new IllegalStateException("Only genesis states can have null address books.");
        }

        this.pathToAddressBookDirectory = Path.of(addressBookConfig.addressBookDirectory());
        try {
            Files.createDirectories(pathToAddressBookDirectory);
        } catch (final IOException e) {
            logger.error(EXCEPTION.getMarker(), "Not able to create directory: {}", pathToAddressBookDirectory, e);
            throw new IllegalStateException("Not able to create directory: " + pathToAddressBookDirectory, e);
        }
        this.useConfigAddressBook = addressBookConfig.forceUseOfConfigAddressBook();
        this.maxNumFiles = addressBookConfig.maxRecordedAddressBookFiles();

        final InitializedAddressBooks addressBooks = initialize();
        currentAddressBook = addressBooks.currentAddressBook();
        if (!useConfigAddressBook && stateAddressBook != null) {
            AddressBookValidator.validateNewAddressBook(stateAddressBook, currentAddressBook);
        }
        previousAddressBook = addressBooks.previousAddressBook();
    }

    /**
     * Returns the address book to use in the platform.
     *
     * @return the address book to use in the platform.
     */
    @NonNull
    public AddressBook getCurrentAddressBook() {
        return currentAddressBook;
    }

    /**
     * Returns the address book used before the restart.
     *
     * @return the address book used before the restart.
     */
    @Nullable
    public AddressBook getPreviousAddressBook() {
        return previousAddressBook;
    }

    /**
     * Checks whether there has been a change in the address book on initialization.
     *
     * @return true if the address book has changed on initialization
     */
    public boolean hasAddressBookChanged() {
        return addressBookChange;
    }

    /**
     * Determines the address book to use.  If the configured current version of the application is higher than the save
     * state version, the swirld state is given the config address book to determine weights.  If the address book
     * returned by the swirld application is valid, it is the initial address book to use, otherwise the configuration
     * address book is the one to use.  All three address books, the configuration address book, the save state address
     * book, and the new address book to use are recorded in the address book directory.
     *
     * @return the address books to use in the platform.
     */
    @NonNull
    private InitializedAddressBooks initialize() {
        AddressBook candidateAddressBook;
        final AddressBook previousAddressBook;
        if (useConfigAddressBook) {
            // settings.txt override to use config.txt address book
            logger.info(
                    STARTUP.getMarker(),
                    "Overriding the address book in the state with the address book from config.txt");
            candidateAddressBook = configAddressBook;
            previousAddressBook = stateAddressBook;
            // change indicator is true to indicate that the address books need to be updated in the state.
            addressBookChange = true;
        } else if (initialState.isGenesisState()) {
            // If this is a genesis state, the state's address book and previous address book may be null.
            // Adopt the config.txt address book.
            logger.info(STARTUP.getMarker(), "Starting from genesis: using the config address book.");
            candidateAddressBook = configAddressBook;
            checkCandidateAddressBookValidity(candidateAddressBook);
            previousAddressBook = null;
            // change indicator is true to indicate that the address books need to be updated in the state.
            addressBookChange = true;
        } else if (!softwareUpgrade) {
            // Loaded State From Disk, Non-Genesis, No Software Upgrade
            logger.info(STARTUP.getMarker(), "Using the loaded state's address book and weight values.");
            candidateAddressBook = stateAddressBook;
            // since state address book was checked for validity prior to adoption, no check needed here.
            previousAddressBook = null;
            // change indicator is false to keep the current and previous address books the same.
            addressBookChange = false;
        } else {
            // Loaded State from Disk, Non-Genesis, There is a software version upgrade
            logger.info(
                    STARTUP.getMarker(),
                    "The address book weight may be updated by the application using data from the state snapshot.");

            AddressBook configAddressBookCopy = configAddressBook.copy();
            consensusStateEventHandler.onUpdateWeight(initialState.getState(), configAddressBookCopy, platformContext);
            candidateAddressBook = configAddressBookCopy;
            candidateAddressBook = checkCandidateAddressBookValidity(candidateAddressBook);
            previousAddressBook = stateAddressBook;
            copyCertsIfAbsent(configAddressBook, stateAddressBook);
            // change indicator is true to indicate that the address books need to be updated in the state.
            addressBookChange = true;
        }
        recordAddressBooks(candidateAddressBook);
        return new InitializedAddressBooks(candidateAddressBook, previousAddressBook);
    }

    /**
     * Copies the certificates from the configAddressBook to the stateAddressBook if the stateAddressBook does not have
     * them.
     *
     * @param configAddressBook the address book from the configuration
     * @param stateAddressBook  the address book from the state
     */
    private void copyCertsIfAbsent(
            @NonNull final AddressBook configAddressBook, @NonNull final AddressBook stateAddressBook) {
        for (final Address address : stateAddressBook) {
            if (address.getSigCert() == null && configAddressBook.contains(address.getNodeId())) {
                final X509Certificate sigCert =
                        configAddressBook.getAddress(address.getNodeId()).getSigCert();
                final X509Certificate agreeCert =
                        configAddressBook.getAddress(address.getNodeId()).getAgreeCert();
                if (sigCert != null) {
                    stateAddressBook.add(address.copySetSigCert(sigCert).copySetAgreeCert(agreeCert));
                } else {
                    logger.warn(
                            "Signing certificate was not found in the config address book for node {}",
                            address.getNodeId());
                }
            }
        }
    }

    /**
     * Checks if the candidateAddressBook is valid and returns it, otherwise returns the configAddressBook if it has
     * non-zero weight.   If the candidateAddressBook's addresses are out of sync with the configAddressBook or both
     * address books have 0 weight, an IllegalStateException is thrown.
     *
     * @return the valid address book to use.
     */
    @NonNull
    private AddressBook checkCandidateAddressBookValidity(@Nullable final AddressBook candidateAddressBook) {
        if (candidateAddressBook == null) {
            logger.warn(STARTUP.getMarker(), "The candidateAddressBook is null, using configAddressBook instead.");
            return configAddressBook;
        } else if (!AddressBookValidator.hasNonZeroWeight(candidateAddressBook)
                || !AddressBookValidator.sameExceptForWeight(configAddressBook, candidateAddressBook)) {
            // an error was recorded by the address book validator.  Check the configuration address book for usability.
            if (!AddressBookValidator.hasNonZeroWeight(configAddressBook)) {
                throw new IllegalStateException(
                        "The candidateAddressBook is not valid and the configAddressBook has 0 total weight.");
            } else {
                logger.warn(
                        STARTUP.getMarker(), "The candidateAddressBook is not valid, using configAddressBook instead.");
                return configAddressBook;
            }
        }
        return candidateAddressBook;
    }

    /**
     * Records the configuration address book, the state address book, and the usedAddressBook in a timestamped file for
     * diagnostic purposes.  If the path to the address book directory does not resolve or a new file for address books
     * cannot be created, no file is generated.
     *
     * @param usedAddressBook the address book to be returned from the AddressBookInitializer.
     */
    private synchronized void recordAddressBooks(@NonNull final AddressBook usedAddressBook) {
        final String date = DATE_TIME_FORMAT.format(Instant.now());
        final String addressBookFileName = "%s_v%s_%s_node_%s.txt".formatted(ADDRESS_BOOK_FILE_PREFIX, 1, date, selfId);
        final String addressBookDebugFileName = addressBookFileName + ".debug";
        try {
            final File debugFile = Path.of(this.pathToAddressBookDirectory.toString(), addressBookDebugFileName)
                    .toFile();
            try (final FileWriter out = new FileWriter(debugFile)) {
                out.write(CONFIG_ADDRESS_BOOK_HEADER + "\n");
                out.write(configAddressBook.toConfigText() + "\n\n");
                out.write(STATE_ADDRESS_BOOK_HEADER + "\n");
                final String text =
                        stateAddressBook == null ? STATE_ADDRESS_BOOK_NULL : stateAddressBook.toConfigText();
                out.write(text + "\n\n");
                out.write(USED_ADDRESS_BOOK_HEADER + "\n");
                if (usedAddressBook == configAddressBook) {
                    out.write(CONFIG_ADDRESS_BOOK_USED);
                } else if (usedAddressBook == stateAddressBook) {
                    out.write(STATE_ADDRESS_BOOK_USED);
                } else {
                    out.write(usedAddressBook.toConfigText());
                }
                out.write("\n\n");
            }
            final File usedFile = Path.of(this.pathToAddressBookDirectory.toString(), addressBookFileName)
                    .toFile();
            try (final FileWriter out = new FileWriter(usedFile)) {
                out.write(usedAddressBook.toConfigText());
            }
        } catch (final IOException e) {
            logger.error(EXCEPTION.getMarker(), "Not able to write address book to file. ", e);
        }
        cleanAddressBookDirectory();
    }

    /**
     * Deletes the oldest address book files if there are more than the maximum number of address book files.
     */
    private synchronized void cleanAddressBookDirectory() {
        try (final Stream<Path> filesStream = Files.list(pathToAddressBookDirectory)) {
            final List<Path> files = filesStream.sorted().toList();
            if (files.size() > maxNumFiles) {
                for (int i = 0; i < files.size() - maxNumFiles; i++) {
                    Files.delete(files.get(i));
                }
            }
        } catch (final IOException e) {
            logger.info(EXCEPTION.getMarker(), "Unable to list files in address book directory. ", e);
        }
    }

    /**
     * Get the path to the directory where the address books are being recorded.
     *
     * @return the directory where the address books are being recorded.
     */
    @NonNull
    public Path getPathToAddressBookDirectory() {
        return pathToAddressBookDirectory;
    }

    /**
     * A record of the address books produced by initialization
     *
     * @param currentAddressBook  the address book that should be used by the platform
     * @param previousAddressBook the address book that was used before the current address book
     */
    private record InitializedAddressBooks(
            @NonNull AddressBook currentAddressBook, @Nullable AddressBook previousAddressBook) {}
}
