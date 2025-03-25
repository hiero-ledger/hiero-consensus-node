// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.platform.roster.RosterUtils.buildAddressBook;
import static com.swirlds.platform.state.address.AddressBookInitializer.CONFIG_ADDRESS_BOOK_HEADER;
import static com.swirlds.platform.state.address.AddressBookInitializer.CONFIG_ADDRESS_BOOK_USED;
import static com.swirlds.platform.state.address.AddressBookInitializer.STATE_ADDRESS_BOOK_HEADER;
import static com.swirlds.platform.state.address.AddressBookInitializer.STATE_ADDRESS_BOOK_NULL;
import static com.swirlds.platform.state.address.AddressBookInitializer.STATE_ADDRESS_BOOK_USED;
import static com.swirlds.platform.state.address.AddressBookInitializer.USED_ADDRESS_BOOK_HEADER;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.config.AddressBookConfig_;
import com.swirlds.platform.roster.RosterRetriever;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.state.address.AddressBookInitializer;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.roster.RosterServiceStateMock;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.stubbing.Stubber;

class AddressBookInitializerTest {

    @TempDir
    Path testDirectory;

    @Mock
    ConsensusStateEventHandler consensusStateEventHandler;

    @BeforeEach
    public void setUp() {
        openMocks(this);
    }

    @Test
    @DisplayName("Force the use of the config address book")
    void forceUseOfConfigAddressBook() throws IOException {
        final Randotron randotron = Randotron.create();
        clearTestDirectory();
        final AddressBook configAddressBook = getRandomAddressBook(randotron);
        final SignedState signedState = getMockSignedState7WeightRandomAddressBook(randotron);
        final AddressBookInitializer initializer = new AddressBookInitializer(
                NodeId.of(0),
                getMockSoftwareVersion(2),
                // no software upgrade
                false,
                signedState,
                configAddressBook,
                getPlatformContext(true),
                consensusStateEventHandler,
                TEST_PLATFORM_STATE_FACADE);
        final AddressBook inititializedAddressBook = initializer.getCurrentAddressBook();
        final AddressBook signedStateAddressBook = buildAddressBook(signedState.getRoster());
        assertEquals(
                configAddressBook,
                inititializedAddressBook,
                "The initial address book must equal the config address book.");
        assertEquals(
                signedStateAddressBook,
                initializer.getPreviousAddressBook(),
                "The previous address book must equal the state address book.");
        assertAddressBookFileContent(initializer, configAddressBook, signedStateAddressBook, inititializedAddressBook);
        assertTrue(initializer.hasAddressBookChanged());
    }

    @Test
    @DisplayName("Genesis. Config.txt Initializes Address Book.")
    void noStateLoadedFromDisk() throws IOException {
        final Randotron randotron = Randotron.create();
        clearTestDirectory();
        final AddressBook configAddressBook = getRandomAddressBook(randotron);
        // initial state has no address books set.
        final SignedState signedState = getMockSignedState(10, null, null, true);
        final AddressBookInitializer initializer = new AddressBookInitializer(
                NodeId.of(0),
                getMockSoftwareVersion(2),
                // no software upgrade
                false,
                signedState,
                configAddressBook,
                getPlatformContext(false),
                consensusStateEventHandler,
                TEST_PLATFORM_STATE_FACADE);
        final AddressBook inititializedAddressBook = initializer.getCurrentAddressBook();
        final AddressBook signedStateAddressBook = buildAddressBook(signedState.getRoster());
        assertEquals(
                configAddressBook,
                inititializedAddressBook,
                "The initial address book must equal the expected address book.");
        assertNull(initializer.getPreviousAddressBook(), "The previous address book should be null.");
        assertAddressBookFileContent(initializer, configAddressBook, signedStateAddressBook, inititializedAddressBook);
        assertTrue(initializer.hasAddressBookChanged());
    }

    @Test
    @DisplayName("No state loaded from disk. Genesis State set 0 weight.")
    void noStateLoadedFromDiskGenesisStateSetZeroWeight() throws IOException {
        final Randotron randotron = Randotron.create();
        clearTestDirectory();
        final AddressBook configAddressBook = getRandomAddressBook(randotron);
        // genesis state address book is set to null to test the code path where it may be null.
        final SignedState signedState = getMockSignedState(10, null, null, true);
        final AddressBookInitializer initializer = new AddressBookInitializer(
                NodeId.of(0),
                getMockSoftwareVersion(2),
                // no software upgrade
                false,
                signedState,
                configAddressBook,
                getPlatformContext(false),
                consensusStateEventHandler,
                TEST_PLATFORM_STATE_FACADE);
        final AddressBook inititializedAddressBook = initializer.getCurrentAddressBook();
        final AddressBook signedStateAddressBook = buildAddressBook(signedState.getRoster());
        assertEquals(
                configAddressBook,
                inititializedAddressBook,
                "The initial address book must equal the config address book.");
        assertNull(initializer.getPreviousAddressBook(), "The previous address book should be null.");
        assertAddressBookFileContent(initializer, configAddressBook, signedStateAddressBook, inititializedAddressBook);
        assertTrue(initializer.hasAddressBookChanged());
    }

    @Test
    @DisplayName("No state loaded from disk. Genesis State modifies address book entries.")
    void noStateLoadedFromDiskGenesisStateChangedAddressBook() throws IOException {
        final Randotron randotron = Randotron.create();
        clearTestDirectory();
        final Roster roster = getRandomRoster(randotron);
        final AddressBook configAddressBook = buildAddressBook(roster);
        final SignedState signedState = getMockSignedState(7, roster, null, true);
        final AddressBookInitializer initializer = new AddressBookInitializer(
                NodeId.of(0),
                getMockSoftwareVersion(2),
                // no software upgrade
                false,
                signedState,
                configAddressBook,
                getPlatformContext(false),
                consensusStateEventHandler,
                TEST_PLATFORM_STATE_FACADE);
        final AddressBook inititializedAddressBook = initializer.getCurrentAddressBook();
        final AddressBook signedStateAddressBook = buildAddressBook(signedState.getRoster());
        assertEquals(
                configAddressBook,
                inititializedAddressBook,
                "The initial address book must equal the config address book.");
        assertNull(initializer.getPreviousAddressBook(), "The previous address book should be null.");
        assertAddressBookFileContent(initializer, configAddressBook, signedStateAddressBook, inititializedAddressBook);
        // Even when the genesis state has the correct address book, we always adopt the config.txt address book and
        // indicate an address book change.
        assertTrue(initializer.hasAddressBookChanged());
    }

    @Test
    @DisplayName("Current software version is equal to state software version.")
    void currentVersionEqualsStateVersion() throws IOException {
        final Randotron randotron = Randotron.create();
        clearTestDirectory();
        // start state with previous address book
        final SignedState signedState =
                getMockSignedState(2, getRandomRoster(randotron), getRandomAddressBook(randotron), false);
        final AddressBook configAddressBook = copyWithWeightChanges(buildAddressBook(signedState.getRoster()), 10);
        final AddressBookInitializer initializer = new AddressBookInitializer(
                NodeId.of(0),
                getMockSoftwareVersion(2),
                // no software upgrade
                false,
                signedState,
                configAddressBook,
                getPlatformContext(false),
                consensusStateEventHandler,
                TEST_PLATFORM_STATE_FACADE);
        final AddressBook inititializedAddressBook = initializer.getCurrentAddressBook();
        final AddressBook signedStateAddressBook = buildAddressBook(signedState.getRoster());
        assertEquals(
                signedStateAddressBook,
                inititializedAddressBook,
                "The initial address book must equal the state address book.");
        assertEqualsAsRosters(
                null,
                initializer.getPreviousAddressBook(),
                "When there is no upgrade, the address book should not change");
        assertAddressBookFileContent(initializer, configAddressBook, signedStateAddressBook, inititializedAddressBook);
        // The addressBooks remain unchanged when there is no software upgrade.
        assertFalse(initializer.hasAddressBookChanged());
    }

    /**
     * Compare AddressBooks after converting them to rosters to exclude fields unsupported by the Roster
     * (memo, selfName, agreeCert, etc.)
     *
     * @param expected expected AddressBook
     * @param actual actual AddressBook
     * @param message error message
     */
    void assertEqualsAsRosters(final AddressBook expected, final AddressBook actual, final String message) {
        final Roster expectedRoster = RosterRetriever.buildRoster(expected);
        final Roster actualRoster = RosterRetriever.buildRoster(actual);
        assertEquals(expectedRoster, actualRoster, message);
    }

    @Test
    @DisplayName("Version upgrade, state set 0 weight.")
    void versionUpgradeStateZeroWeight() throws IOException {
        final Randotron randotron = Randotron.create();
        clearTestDirectory();
        final Roster roster = getRandomRoster(randotron);
        final SignedState signedState = getMockSignedState(0, roster, getRandomAddressBook(randotron), false);
        final AddressBook configAddressBook = copyWithWeightChanges(buildAddressBook(roster), 10);
        final AddressBookInitializer initializer = new AddressBookInitializer(
                NodeId.of(0),
                getMockSoftwareVersion(3),
                // software upgrade
                true,
                signedState,
                configAddressBook,
                getPlatformContext(false),
                consensusStateEventHandler,
                TEST_PLATFORM_STATE_FACADE);
        final AddressBook inititializedAddressBook = initializer.getCurrentAddressBook();
        final AddressBook signedStateAddressBook = buildAddressBook(signedState.getRoster());
        assertEquals(
                configAddressBook,
                inititializedAddressBook,
                "The initial address book must equal the config address book.");
        assertEquals(
                signedStateAddressBook,
                initializer.getPreviousAddressBook(),
                "The previous address book must equal the state address book.");
        assertAddressBookFileContent(initializer, configAddressBook, signedStateAddressBook, inititializedAddressBook);
        assertTrue(initializer.hasAddressBookChanged());
    }

    @Test
    @DisplayName("Version upgrade, state modified the address book.")
    void versionUpgradeStateModifiedAddressBook() throws IOException {
        final Randotron randotron = Randotron.create();
        clearTestDirectory();
        final Roster roster = getRandomRoster(randotron);
        final SignedState signedState = getMockSignedState(2, roster, getRandomAddressBook(randotron), false);
        final AddressBook signedStateAddressBook = buildAddressBook(roster);
        final AddressBook configAddressBook = copyWithWeightChanges(signedStateAddressBook, 3);
        final AddressBookInitializer initializer = new AddressBookInitializer(
                NodeId.of(0),
                getMockSoftwareVersion(3),
                // software upgrade
                true,
                signedState,
                configAddressBook,
                getPlatformContext(false),
                consensusStateEventHandler,
                TEST_PLATFORM_STATE_FACADE);
        final AddressBook inititializedAddressBook = initializer.getCurrentAddressBook();
        assertEquals(
                configAddressBook,
                inititializedAddressBook,
                "The initial address book must equal the config address book.");
        assertEquals(
                signedStateAddressBook,
                initializer.getPreviousAddressBook(),
                "The previous address book must equal the state address book.");
        assertAddressBookFileContent(initializer, configAddressBook, signedStateAddressBook, inititializedAddressBook);
        assertTrue(initializer.hasAddressBookChanged());
    }

    @Test
    @DisplayName("Version upgrade, State updates weight successfully.")
    void versionUpgradeStateWeightUpdateWorks() throws IOException {
        final Randotron randotron = Randotron.create();
        clearTestDirectory();
        final SignedState signedState = getMockSignedState7WeightRandomAddressBook(randotron);
        final AddressBook signedStateAddressBook = buildAddressBook(signedState.getRoster());
        final AddressBook configAddressBook = copyWithWeightChanges(signedStateAddressBook, 5);
        final AddressBookInitializer initializer = new AddressBookInitializer(
                NodeId.of(0),
                getMockSoftwareVersion(3),
                // software upgrade
                true,
                signedState,
                configAddressBook,
                getPlatformContext(false),
                consensusStateEventHandler,
                TEST_PLATFORM_STATE_FACADE);
        final AddressBook inititializedAddressBook = initializer.getCurrentAddressBook();
        assertNotEquals(
                configAddressBook,
                inititializedAddressBook,
                "The initial address book must not equal the config address book.");
        assertNotEquals(
                signedStateAddressBook,
                inititializedAddressBook,
                "The initial address book must not equal the state address book.");
        assertEquals(
                signedStateAddressBook,
                initializer.getPreviousAddressBook(),
                "The previous address book must equal the state address book.");
        assertAddressBookFileContent(initializer, configAddressBook, signedStateAddressBook, inititializedAddressBook);
        assertTrue(initializer.hasAddressBookChanged());
    }

    /**
     * Copies the address book while setting weight per address to the given weight value.
     *
     * @param addressBook The address book to copy
     * @param weightValue The new weight value per address.
     * @return the copy of the input address book with the given weight value per address.
     */
    private AddressBook copyWithWeightChanges(AddressBook addressBook, int weightValue) {
        final AddressBook newAddressBook = new AddressBook();
        for (Address address : addressBook) {
            newAddressBook.add(address.copySetWeight(weightValue));
        }
        return newAddressBook;
    }

    /**
     * Copies the address book while setting weight per address to the given weight value.
     *
     * @param addressBook The address book to copy
     * @param weightValue The new weight value per address.
     * @return the copy of the input address book with the given weight value per address.
     */
    private void updateWithWeightChanges(AddressBook addressBook, int weightValue) {
        final AddressBook temp = new AddressBook();
        for (Address address : addressBook) {
            temp.add(address.copySetWeight(weightValue));
        }
        addressBook.clear();
        for (Address address : temp) {
            addressBook.add(address);
        }
    }

    /**
     * Creates a mock SoftwareVersion matching the input version number.
     *
     * @param version the integer software version.
     * @return the SoftwareVersion matching the input version.
     */
    private SoftwareVersion getMockSoftwareVersion(int version) {
        final SoftwareVersion softwareVersion = mock(SoftwareVersion.class);
        when(softwareVersion.getVersion()).thenReturn(version);
        final AtomicReference<SoftwareVersion> softVersion = new AtomicReference<>();
        when(softwareVersion.compareTo(argThat(sv -> {
                    softVersion.set(sv);
                    return true;
                })))
                .thenAnswer(i -> {
                    SoftwareVersion other = softVersion.get();
                    if (other == null) {
                        return 1;
                    } else {
                        return Integer.compare(softwareVersion.getVersion(), other.getVersion());
                    }
                });
        when(softwareVersion.toString()).thenReturn(Integer.toString(version));
        return softwareVersion;
    }

    /**
     * Creates a mock signed state and a State that sets all addresses to have weight = 7.
     *
     * @return The mock SignedState.
     */
    private SignedState getMockSignedState7WeightRandomAddressBook(@NonNull final Randotron randotron) {
        return getMockSignedState(7, getRandomRoster(randotron), getRandomAddressBook(randotron), false);
    }

    /**
     * Creates a mock signed state and a State that sets all addresses to the given weightValue.
     *
     * @param weightValue         The weight value that the State should set all addresses to in its updateWeight
     *                            method.
     * @param currentRoster       The roster that should be returned by {@link SignedState#getRoster()} and used to
     *                            derive the address book for {@link PlatformStateAccessor#getAddressBook()}
     * @param previousAddressBook The address book that should be returned by
     *                            {@link PlatformStateAccessor#getPreviousAddressBook()}
     * @param fromGenesis         Whether the state should be from genesis or not.
     * @return The mock SignedState and State configured to set all addresses with given weightValue.
     */
    private SignedState getMockSignedState(
            final int weightValue,
            @Nullable final Roster currentRoster,
            @Nullable final AddressBook previousAddressBook,
            boolean fromGenesis) {
        final SignedState signedState = mock(SignedState.class);
        final SemanticVersion softwareVersion = getMockSoftwareVersion(2).getPbjSemanticVersion();
        configureUpdateWeightForStateEventHandler(weightValue);
        final MerkleNodeState state = mock(MerkleNodeState.class);
        final ReadableStates readableStates = mock(ReadableStates.class);
        final PlatformStateAccessor platformState = mock(PlatformStateAccessor.class);
        when(platformState.getCreationSoftwareVersion()).thenReturn(softwareVersion);
        when(state.getReadableStates(PlatformStateService.NAME)).thenReturn(readableStates);
        RosterServiceStateMock.setup(state, currentRoster, 1L, RosterRetriever.buildRoster(previousAddressBook));

        when(signedState.getState()).thenReturn(state);
        when(signedState.isGenesisState()).thenReturn(fromGenesis);
        when(signedState.getRoster()).thenReturn(currentRoster);
        return signedState;
    }

    /**
     * Creates a mock swirld state with the given scenario.
     *
     * @param scenario The scenario to load.
     */
    private void configureUpdateWeightForStateEventHandler(int scenario) {

        final AtomicReference<AddressBook> configAddressBook = new AtomicReference<>();

        final Stubber stubber;

        switch (scenario) {
            case 0:
                stubber = doAnswer(foo -> {
                    updateWithWeightChanges(configAddressBook.get(), 0);
                    return null;
                });
                break;
            case 1:
                stubber = doAnswer(foo -> configAddressBook.get());
                break;
            case 2:
                stubber = doAnswer(foo -> configAddressBook
                        .get()
                        .add(configAddressBook
                                .get()
                                .getAddress(configAddressBook.get().getNodeId(0))
                                .copySetNodeId(configAddressBook.get().getNextAvailableNodeId())));
                break;
            case 7:
                stubber = doAnswer(foo -> {
                    updateWithWeightChanges(configAddressBook.get(), 7);
                    return null;
                });
                break;
            default:
                stubber = doAnswer(foo -> copyWithWeightChanges(configAddressBook.get(), 10));
        }

        stubber.when(consensusStateEventHandler)
                .onUpdateWeight(
                        any(),
                        argThat(confAB -> {
                            configAddressBook.set(confAB);
                            return true;
                        }),
                        argThat(context -> true));
    }

    /**
     * Creates an address book where each address has weight equal to its node ID.
     *
     * @return the address book created.
     */
    @NonNull
    private AddressBook getRandomAddressBook(@NonNull final Random random) {
        return RandomAddressBookBuilder.create(random).withSize(5).build();
    }

    @NonNull
    private Roster getRandomRoster(@NonNull final Random random) {
        return RandomRosterBuilder.create(random).withSize(5).build();
    }

    /**
     * removes all files and subdirectories from the test directory.
     *
     * @throws IOException if any IOException is created by deleting files or directories.
     */
    private void clearTestDirectory() throws IOException {
        for (File file : testDirectory.toFile().listFiles()) {
            if (file.isDirectory()) {
                FileUtils.deleteDirectory(file.toPath());
            } else {
                file.delete();
            }
        }
    }

    /**
     * Validates the state of the recorded address book file according to the excepted input states.
     *
     * @param initializer       The AddressBookInitializer constructed.
     * @param configAddressBook The configuration address book.
     * @param stateAddressBook  The state recorded address book.  May be null if there is no state, or it was unusable.
     * @param usedAddressBook   The address book returned by the initializer.
     * @throws IOException if any IOExceptions occur while reading the recorded file on disk.
     */
    private void assertAddressBookFileContent(
            @NonNull final AddressBookInitializer initializer,
            @NonNull final AddressBook configAddressBook,
            @NonNull final AddressBook stateAddressBook,
            @NonNull final AddressBook usedAddressBook)
            throws IOException {
        Objects.requireNonNull(initializer, "The initializer must not be null.");
        Objects.requireNonNull(configAddressBook, "The configAddressBook must not be null.");
        Objects.requireNonNull(configAddressBook, "The initializedAddressBook must not be null.");
        final Path addressBookDirectory = initializer.getPathToAddressBookDirectory();
        assertEquals(
                2,
                Arrays.stream(addressBookDirectory.toFile().listFiles()).count(),
                "There should be exactly one file in the test directory.");
        final File[] files = addressBookDirectory.toFile().listFiles();
        final File debugFile;
        final File usedFile;
        if (files[0].toString().contains("debug")) {
            debugFile = files[0];
            usedFile = files[1];
        } else {
            debugFile = files[1];
            usedFile = files[0];
        }
        final String debugFileContent = Files.readString(debugFile.toPath());
        final String usedFileContent = Files.readString(usedFile.toPath());

        // check used AddressBook content
        final String usedAddressBookText = usedAddressBook.toConfigText();
        assertEquals(
                usedAddressBookText,
                usedFileContent,
                "The used file content is not the same as the used address book.");

        // check debug AddressBook content
        final String configText = CONFIG_ADDRESS_BOOK_HEADER + "\n" + configAddressBook.toConfigText();
        assertTrue(
                debugFileContent.contains(configText),
                "The configAddressBook content is not:\n" + configText + "\n\n debugFileContent:\n" + debugFileContent);

        // check stateAddressBook content
        final String stateAddressBookText =
                (stateAddressBook == null ? STATE_ADDRESS_BOOK_NULL : stateAddressBook.toConfigText());
        final String stateText = STATE_ADDRESS_BOOK_HEADER + "\n" + stateAddressBookText;

        assertTrue(
                debugFileContent.contains(stateText),
                "The stateAddressBook content is not:\n" + stateText + "\n\n debugFileContent:\n" + debugFileContent);

        // check usedAddressBook content
        String usedText = USED_ADDRESS_BOOK_HEADER + "\n";
        if (Objects.equals(usedAddressBook, configAddressBook)) {
            usedText += CONFIG_ADDRESS_BOOK_USED;
        } else if (Objects.equals(usedAddressBook, stateAddressBook)) {
            usedText += STATE_ADDRESS_BOOK_USED;
        } else {
            usedText += usedAddressBook.toConfigText();
        }
        assertTrue(
                debugFileContent.contains(usedText),
                "The usedAddressBook content is not:\n" + usedText + "\n\n debugFileContent:\n" + debugFileContent);
    }

    /**
     * Creates an PlatformContext object with a configuration able to return AddressBookConfiguration with the given
     * forceUseOfConfigAddressBook value.
     *
     * @param forceUseOfConfigAddressBook the value to use for the forceUseOfConfigAddressBook property.
     * @return the PlatformContext object.
     */
    private PlatformContext getPlatformContext(boolean forceUseOfConfigAddressBook) {
        return TestPlatformContextBuilder.create()
                .withConfiguration(new TestConfigBuilder()
                        .withValue(AddressBookConfig_.FORCE_USE_OF_CONFIG_ADDRESS_BOOK, forceUseOfConfigAddressBook)
                        .withValue(AddressBookConfig_.ADDRESS_BOOK_DIRECTORY, testDirectory.toString())
                        .withValue(AddressBookConfig_.MAX_RECORDED_ADDRESS_BOOK_FILES, 50)
                        .getOrCreateConfig())
                .build();
    }
}
