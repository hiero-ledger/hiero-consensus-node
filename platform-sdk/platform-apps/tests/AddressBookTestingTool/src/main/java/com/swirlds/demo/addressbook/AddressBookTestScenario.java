// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.addressbook;

/**
 * Enumerated values for the test scenarios that can be run for validation of AddressBook Initialization
 */
public enum AddressBookTestScenario {
    /** Skip validation of the address book initialization. */
    SKIP_VALIDATION,

    /** On Genesis, the config address book is forced to be used. */
    GENESIS_FORCE_CONFIG_AB,

    /** On Genesis, use normal behavior,the config address book is not forced to be used. */
    GENESIS_NORMAL,

    /** On restart, no upgrade, use the saved state address book. */
    NO_UPGRADE_USE_SAVED_STATE,

    /** On restart, no upgrade, force use of the config address book. */
    NO_UPGRADE_FORCE_CONFIG_AB,

    /** On restart, upgrade, update address book weight with behavior 2. */
    UPGRADE_WEIGHT_BEHAVIOR_2,

    /** On restart, upgrade, force use of the config address book. */
    UPGRADE_FORCE_CONFIG_AB,

    /** On restart, upgrade, add a new Node **/
    UPGRADE_ADD_NODE,

    /** On restart, upgrade, remove a node **/
    UPGRADE_REMOVE_NODE;
}
