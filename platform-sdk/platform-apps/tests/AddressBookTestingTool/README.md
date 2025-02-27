# AddressBookTestingTool Instructions

This document describes various manual tests that were performed using this testing app. This
playbook should be used when designing automated tests using this app.

## Global Modifications and Starting Conditions

Be sure to clean and assemble the whole project if the java files have been modified.

### config.txt

comment out the current app

```
# app,		StatsDemo.jar,		   1, 3000, 0, 100, -1, 200
```

uncomment the AddressBookTestingTool.jar

```
app,    AddressBookTestingTool.jar,
```

### settings.txt

Add the following lines to the settings.txt file

```
addressBookTestingTool.softwareVersion, 1
addressBookTestingTool.weightingBehavior, 1
```

## Testing Genesis Behavior

### Test Scenario 1: Force Use of Config Address Book on Genesis

#### Instructions

1. Delete `sdk/data/saved` directory if it exists
2. Ensure settings.txt has the following values

```
state.saveStatePeriod,                    0
addressBook.forceUseOfConfigAddressBook,  true
addressBookTestingTool.testScenario,      GENESIS_FORCE_CONFIG_AB
addressBookTestingTool.softwareVersion,   1
addressBookTestingTool.weightingBehavior, 1
```

3. Run the app for 60 seconds

#### Validation

- check the swirlds.log for the text

```
AddressBookInitializer: Overriding the address book in the state with the address book from config.txt
```

and

```
AddressBookTestingToolState: Validating test scenario GENESIS_FORCE_CONFIG_AB.
```

- check that there are no errors or exceptions in the swirlds.log

Errors are logged if any of the following conditions are violated.

- check the directory `sdk/data/saved/address_book` for files
  - usedAddressBook*v1*<date>.txt
    - matches the addresses in the config.txt, including weight value.
  - usedAddressBook*v1*<date>.txt.debug
    - The configuration address book is the same as what is in config.txt
    - The state saved address book was null
    - The used address book text says `The Configuration Address Book Was Used.`

### Test Scenario 2: Unforced use of Config Address Book on Genesis

#### Instructions

1. Delete `sdk/data/saved` directory if it exists
2. Ensure settings.txt has the following values

```
state.saveStatePeriod,                    0
addressBook.forceUseOfConfigAddressBook,  false
addressBookTestingTool.testScenario,      GENESIS_NORMAL
addressBookTestingTool.softwareVersion,   1
addressBookTestingTool.weightingBehavior, 1
```

3. Run the app for 60 seconds

#### Validation

- check the swirlds.log for the text

```
AddressBookInitializer: The loaded signed state is null. The candidateAddressBook is set to the address book from config.txt.
```

and

```
AddressBookTestingToolState: Validating test scenario GENESIS_NORMAL.
```

- check that there are no errors or exceptions in the swirlds.log

Errors are logged if any of the following conditions are violated.

- check the directory `sdk/data/saved/address_book` for files
  - usedAddressBook*v1*<date>.txt
    - contains the addresses in the config.txt with identical weight
  - usedAddressBook*v1*<date>.txt.debug
    - The configuration address book is the same as what is in config.txt
    - The state saved address book was null
    - The used address book text says `The Configuration Address Book Was Used.`

## Testing Non-Genesis Behavior, No Software Upgrade

### Test Scenario 3: No Software Upgrade, Use Saved State Address Book

#### Instructions

1. Delete `sdk/data/saved` directory if it exists
2. Ensure settings.txt has the following values

```
state.saveStatePeriod,                    10
addressBook.forceUseOfConfigAddressBook,  false
addressBookTestingTool.testScenario,      SKIP_VALIDATION
addressBookTestingTool.softwareVersion,   1
addressBookTestingTool.weightingBehavior, 1
```

3. Run the app for 60 seconds
4. Stop the app
5. Ensure settings.txt has the following values

```
state.saveStatePeriod,                    10
addressBook.forceUseOfConfigAddressBook,  false
addressBookTestingTool.testScenario,      NO_UPGRADE_USE_SAVED_STATE
addressBookTestingTool.softwareVersion,   1
addressBookTestingTool.weightingBehavior, 1
```

6. Run the app for 60 seconds.

#### Validation

- check the swirlds.log for the text

```
BootstrapUtils: Not upgrading software, current software is version 1.
AddressBookInitializer: Using the loaded signed state's address book and weight values.
```

and

```
AddressBookTestingToolState: Validating test scenario NO_UPGRADE_USE_SAVED_STATE.
```

- check that there are no errors or exceptions in the swirlds.log

Errors are logged if any of the following conditions are violated.

- check the directory `sdk/data/saved/address_book` for the latest files
  - usedAddressBook*v1*<date>.txt
    - matches the addresses in the config.txt, including weight value.
  - usedAddressBook*v1*<date>.txt.debug
    - The configuration address book is the same as what is in config.txt
    - The state saved address book matches the content of the non-debug .txt file.
    - The used address book text says `The State Saved Address Book Was Used.`

### Test Scenario 4: No Software Upgrade, Force Use of Config Address Book

#### Instructions

1. Delete `sdk/data/saved` directory if it exists
2. Ensure settings.txt has the following values

```
state.saveStatePeriod,                    10
addressBook.forceUseOfConfigAddressBook,  false
addressBookTestingTool.testScenario,      SKIP_VALIDATION
addressBookTestingTool.softwareVersion,   1
addressBookTestingTool.weightingBehavior, 1
```

3. Run the app for 60 seconds
4. Stop the app
5. Ensure settings.txt has the following values

```
state.saveStatePeriod,                    10
addressBook.forceUseOfConfigAddressBook,  true
addressBookTestingTool.testScenario,      NO_UPGRADE_FORCE_CONFIG_AB
addressBookTestingTool.softwareVersion,   1
addressBookTestingTool.weightingBehavior, 1
```

6. Run the app for 60 seconds.

#### Validation

- check the swirlds.log for the text

```
AddressBookInitializer: Overriding the address book in the state with the address book from config.txt
```

and

```
AddressBookTestingToolState: Validating test scenario NO_UPGRADE_FORCE_CONFIG_AB.
```

- check that there are no errors or exceptions in the swirlds.log

Errors are logged if any of the following conditions are violated.

- check the directory `sdk/data/saved/address_book` for the latest files
  - usedAddressBook*v1*<date>.txt
    - matches the addresses in the config.txt, including weight value.
  - usedAddressBook*v1*<date>.txt.debug
    - The configuration address book is the same as what is in config.txt
    - The state saved address book is the same as what is in config.txt
    - The used address book text says `The Configuration Address Book Was Used.`

### Test Scenario 5: Software Upgrade, Weighting Behavior 2

#### Instructions

1. Delete `sdk/data/saved` directory if it exists
2. Ensure settings.txt has the following values

```
state.saveStatePeriod,                     10
addressBook.forceUseOfConfigAddressBook,   false
addressBookTestingTool.testScenario,       SKIP_VALIDATION
addressBookTestingTool.softwareVersion,    1
addressBookTestingTool.weightingBehavior,  1
addressBookTestingTool.freezeAfterGenesis, 1m
```

3. Run the app for 60 seconds
4. Stop the app
5. Ensure settings.txt has the following values

```
state.saveStatePeriod,                    10
addressBook.forceUseOfConfigAddressBook,  false
addressBookTestingTool.testScenario,      UPGRADE_WEIGHT_BEHAVIOR_2
addressBookTestingTool.softwareVersion,   2
addressBookTestingTool.weightingBehavior, 2
```

6. Run the app for 60 seconds.

#### Validation

- check the swirlds.log for the text

```
BootstrapUtils: Software upgrade in progress. Previous software version was 1, current version is 2.
AddressBookInitializer: The address book weight may be updated by the application using data from the state snapshot.
```

and

```
AddressBookTestingToolState: Validating test scenario UPGRADE_WEIGHT_BEHAVIOR_2.
```

- check that there are no errors or exceptions in the swirlds.log

Errors are logged if any of the following conditions are violated.

- check the directory `sdk/data/saved/address_book` for the latest files
  - usedAddressBook*v1*<date>.txt
    - matches the addresses in the config.txt, but the weight values incrementally increase
      starting from 0.
  - usedAddressBook*v1*<date>.txt.debug
    - The configuration address book is the same as what is in config.txt
    - The state saved address book is the same as what is in config.txt
    - The used address book matches the content of the non-debug .txt file.

### Test Scenario 6: Software Upgrade, Force Use Of Config Address Book

#### Instructions

1. Delete `sdk/data/saved` directory if it exists
2. Ensure settings.txt has the following values

```
state.saveStatePeriod,                     10
addressBook.forceUseOfConfigAddressBook,   false
addressBookTestingTool.testScenario,       SKIP_VALIDATION
addressBookTestingTool.softwareVersion,    1
addressBookTestingTool.weightingBehavior,  1
addressBookTestingTool.freezeAfterGenesis, 1m
```

3. Run the app for 60 seconds
4. Stop the app
5. Ensure settings.txt has the following values

```
state.saveStatePeriod,                    10
addressBook.forceUseOfConfigAddressBook,  true
addressBookTestingTool.testScenario,      UPGRADE_FORCE_CONFIG_AB
addressBookTestingTool.softwareVersion,   2
addressBookTestingTool.weightingBehavior, 2
```

6. Run the app for 60 seconds.

#### Validation

- check the swirlds.log for the text

```
BootstrapUtils: Software upgrade in progress. Previous software version was 1, current version is 2.
AddressBookInitializer: Overriding the address book in the state with the address book from config.txt
```

and

```
AddressBookTestingToolState: Validating test scenario UPGRADE_FORCE_CONFIG_AB.
```

- check that there are no errors or exceptions in the swirlds.log

Errors are logged if any of the following conditions are violated.

- check the directory `sdk/data/saved/address_book` for the latest files
  - usedAddressBook*v1*<date>.txt
    - matches the addresses in the config.txt, including weight value.
  - usedAddressBook*v1*<date>.txt.debug
    - The configuration address book is the same as what is in config.txt
    - The state saved address book is the same as what is in config.txt
    - The used address book text says `The Configuration Address Book Was Used.`

## Testing Adding and Removing Nodes With Software Upgrade

### Test Scenario 7: Software Upgrade, Add New Node To Network

#### Instructions

1. Delete `sdk/data/saved` directory if it exists
2. Ensure settings.txt has the following values

```
state.saveStatePeriod,                     10
addressBook.forceUseOfConfigAddressBook,   false
addressBookTestingTool.testScenario,       SKIP_VALIDATION
addressBookTestingTool.softwareVersion,    1
addressBookTestingTool.weightingBehavior,  1
addressBookTestingTool.freezeAfterGenesis, 1m
```

3. Ensure Config.txt has the following values

```
swirld, 123
app,		AddressBookTestingTool.jar,
address,  0, A, Alice,    10, 127.0.0.1, 15301, 127.0.0.1, 15301
address,  1, B, Bob,      10, 127.0.0.1, 15302, 127.0.0.1, 15302
address,  2, C, Carol,    10, 127.0.0.1, 15303, 127.0.0.1, 15303
address,  3, D, Dave,     10, 127.0.0.1, 15304, 127.0.0.1, 15304
# address,  4, E, Eric,     10, 127.0.0.1, 15305, 127.0.0.1, 15305
```

4. Run the app for 60 seconds
5. Stop the app
6. Ensure settings.txt has the following values

```
state.saveStatePeriod,                    0
addressBook.forceUseOfConfigAddressBook,  false
addressBookTestingTool.testScenario,      UPGRADE_ADD_NODE
addressBookTestingTool.softwareVersion,   2
addressBookTestingTool.weightingBehavior, 1
```

7. Ensure Config.txt has the following values

```
swirld, 123
app,		AddressBookTestingTool.jar,
address,  0, A, Alice,    10, 127.0.0.1, 15301, 127.0.0.1, 15301
address,  1, B, Bob,      10, 127.0.0.1, 15302, 127.0.0.1, 15302
address,  2, C, Carol,    10, 127.0.0.1, 15303, 127.0.0.1, 15303
address,  3, D, Dave,     10, 127.0.0.1, 15304, 127.0.0.1, 15304
address,  4, E, Eric,     10, 127.0.0.1, 15305, 127.0.0.1, 15305
```

8. Run the app for 60 seconds.

#### Validation

- check the swirlds.log for the text

```
BootstrapUtils: Software upgrade in progress. Previous software version was 1, current version is 2.
```

and

```
AddressBookTestingToolState: Validating test scenario UPGRADE_ADD_NODE.
AddressBookTestingToolState: Test scenario UPGRADE_ADD_NODE: finished without errors.
```

- check that there are no errors or exceptions in the swirlds.log

### Test Scenario 8: Software Upgrade, Remove Node From Network

#### Instructions

1. Delete `sdk/data/saved` directory if it exists
2. Ensure settings.txt has the following values

```
state.saveStatePeriod,                     10
addressBook.forceUseOfConfigAddressBook,   false
addressBookTestingTool.testScenario,       SKIP_VALIDATION
addressBookTestingTool.softwareVersion,    1
addressBookTestingTool.weightingBehavior,  1
addressBookTestingTool.freezeAfterGenesis, 1m
```

3. Ensure Config.txt has the following values

```
swirld, 123
app,		AddressBookTestingTool.jar,
address,  0, A, Alice,    10, 127.0.0.1, 15301, 127.0.0.1, 15301
address,  1, B, Bob,      10, 127.0.0.1, 15302, 127.0.0.1, 15302
address,  2, C, Carol,    10, 127.0.0.1, 15303, 127.0.0.1, 15303
address,  3, D, Dave,     10, 127.0.0.1, 15304, 127.0.0.1, 15304
address,  4, E, Eric,     10, 127.0.0.1, 15305, 127.0.0.1, 15305
```

4. Run the app for 60 seconds
5. Stop the app
6. Ensure settings.txt has the following values

```
state.saveStatePeriod,                    0
addressBook.forceUseOfConfigAddressBook,  false
addressBookTestingTool.testScenario,      UPGRADE_REMOVE_NODE
addressBookTestingTool.softwareVersion,   2
addressBookTestingTool.weightingBehavior, 1
```

7. Ensure Config.txt has the following values

```
swirld, 123
app,		AddressBookTestingTool.jar,
address,  0, A, Alice,    10, 127.0.0.1, 15301, 127.0.0.1, 15301
address,  1, B, Bob,      10, 127.0.0.1, 15302, 127.0.0.1, 15302
address,  2, C, Carol,    10, 127.0.0.1, 15303, 127.0.0.1, 15303
address,  3, D, Dave,     10, 127.0.0.1, 15304, 127.0.0.1, 15304
# address,  4, E, Eric,     10, 127.0.0.1, 15305, 127.0.0.1, 15305
```

8. Run the app for 60 seconds.

#### Validation

- check the swirlds.log for the text

```
BootstrapUtils: Software upgrade in progress. Previous software version was 1, current version is 2.
```

and

```
AddressBookTestingToolState: Validating test scenario UPGRADE_REMOVE_NODE.
AddressBookTestingToolState: Test scenario UPGRADE_REMOVE_NODE: finished without errors.
```

- check that there are no errors or exceptions in the swirlds.log
