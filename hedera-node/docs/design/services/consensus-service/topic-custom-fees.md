# Topic custom fees

## Purpose

The implementation of a native fixed fee system for the submission of topic messages on the Hedera network, will add the possibility of adding custom fees to a given topic.

## Prerequisite reading

* [HIP-991](https://hips.hedera.com/hip/hip-991)

## Goals

1. Update existing `ConsensusCreateTopic`, `ConsensusUpdateTopic` and `ConsensusTopicInfo` to support custom fees.
2. Update existing `ConsensusSubmitMessage` to validate and charge custom fees, if needed.

## Architecture

### HAPI Updates

Topics now have a new Fee Schedule Key.

```protobuf
/**
 * Access control for update/delete of custom fees.
 * <p>
 * If unset, custom fees CANNOT be set for this topic.<br/>
 * If not set when the topic is created, this field CANNOT be set via update.
 * If set when the topic is created, this field MAY be changed via update.
 */
Key fee_schedule_key = 8;
```

Topics can have an optional fees for submitting messages.

```protobuf
/**
 * A set of custom fee definitions.<br/>
 * These are fees to be assessed for each submit to this topic.
 * <p>
 * Each fee defined in this set SHALL be evaluated for
 * each message submitted to this topic, and the resultant
 * total assessed fees SHALL be charged.<br/>
 * Custom fees defined here SHALL be in addition to the base
 * network and node fees.
 */
repeated FixedCustomFee custom_fees = 10;
```

Fees can be only a `fixed fee` and can be set in HBAR or HTS fungible tokens.

```protobuf
/**
 * A custom fee definition for a consensus topic.
 *
 * This fee definition is specific to an Hedera Consensus Service (HCS) topic
 * and SHOULD NOT be used in any other context.<br/>
 * All fields for this message are REQUIRED.<br/>
 * Only "fixed" fee definitions are supported because there is no basis for
 * a fractional fee on a consensus submit transaction.
 */
message FixedCustomFee {
  /**
   * A fixed custom fee.
   * <p>
   * The amount of HBAR or other token described by this `FixedFee` SHALL
   * be charged to the transction payer for each message submitted to a
   * topic that assigns this consensus custom fee.
   */
  FixedFee fixed_fee = 1;

  /**
   * A collection account identifier.
   * <p>
   * All amounts collected for this consensus custom fee SHALL be transferred
   * to the account identified by this field.
   */
  AccountID fee_collector_account_id = 2;
}
```

Topics can have a list of keys that are allowed to send free messages.

```protobuf
/**
 * A set of keys that are allowed to submit messages to the topic without
 * paying the topic's custom fees.
 * <p>
 * If a submit transaction is signed by _any_ key included in this set,
 *  custom fees SHALL NOT be charged for that transaction.
 * <p>
 * If fee_exempt_key_list is unset in this transaction, there SHALL NOT be
 * any fee-exempt keys.  In particular, the following keys SHALL NOT be
 * implicitly or automatically added to this list:
 * `adminKey`, `submitKey`, `fee_schedule_key`.
 */
repeated Key fee_exempt_key_list = 9;
```

Note:
In `consensus_update_topic`, `custom_fees` and `fee_exempt_key_list` are wrapped in order to differentiate between setting an empty list and not updating the list.

HIP-991 introduces generic custom fee limits.
The `TransactionBody` message is updated to include the optional list maxCustomFees for specifying the maximum fee that the users are willing to pay for the transaction.

```protobuf
message TransactionBody {
  [..]
  /**
   * A list of maximum custom fees that the users are willing to pay.
   * <p>
   * This field is OPTIONAL.<br/>
   * If left empty, the users are accepting to pay any custom fee.<br/>
   * If used with a transaction type that does not support custom fee limits, the transaction will fail.
   */
  repeated CustomFeeLimit maxCustomFees = 1001;
}
```

```protobuf
/**
 * A maximum custom fee that the user is willing to pay.
 * <p>
 * This message is used to specify the maximum custom fee that given user is
 * willing to pay.
 */
message CustomFeeLimit {
  /**
   * A payer account identifier.
   */
  AccountID account_id = 1;

  /**
   * A custom fee amount limit.
   */
  FixedFee amount_limit = 2;
}
```

### Fees

- Topics may have fees for submitting messages.
- Fees are defined as a list of custom fees.
- The list of custom fees can contain a maximum of `MAX_CUSTOM_FEE_ENTRIES_FOR_TOPICS` entries.
- A custom fee is defined leveraging the HTS’s FixedFee data structure.
- A custom fee can be set in HBAR or HTS fungible tokens and must have an accountID as collector.
- Fees can be set at topic creation time.
- Fees can be changed (updated or removed) in a topic with an update topic transaction signed by the Fee Schedule Key.

### Keys

- Topics can have a Fee Schedule Key set at creation time.
- The Fee Schedule Key can manage fee updates for the topic.
- Only the Admin Key should be able to remove the Fee Schedule Key.
- The Fee Schedule Key can change itself to another valid or unusable key.
- If the topic was created without a Fee Schedule Key, the key cannot be added later.

### Service updates

#### ConsensusTopicInfo

- Update in `topic.proto` the `Topic` protobuf message, to be able to store the new fields in the state.

```protobuf
    /**
     * Access control for update/delete of custom fees.
     * <p>
     * If this field is unset, the current custom fees CANNOT be changed.<br/>
     * If this field is set, that `Key` MUST sign any transaction to update
     * the custom fee schedule for this topic.
     */
    Key fee_schedule_key = 11;

    /**
     * A list of "privileged payer" keys.
     * <p>
     * If a submit transaction is signed by _any_ key from this list,
     * custom fees SHALL NOT be charged for that transaction.<br/>
     * If fee_exempt_key_list is unset, it SHALL _implicitly_ contain
     * the key `admin_key`, the key `submit_key`, and the key
     * `fee_schedule_key`, if any of those keys are set.
     */
    repeated Key fee_exempt_key_list = 12;

    /**
     * A set of custom fee definitions.<br/>
     * These are fees to be assessed for each submit to this topic.
     * <p>
     * If this list is empty, the only fees charged for a submit to this
     * topic SHALL be the network and node fees.<br/>
     * If this list is not empty, each fee defined in this set SHALL
     * be evaluated for each message submitted to this topic, and the
     * resultant total assessed fees SHALL be charged.<br/>
     * If this list is not empty, custom fees defined here SHALL be
     * charged _in addition to_ the base network and node fees.
     */
    repeated ConsensusCustomFee custom_fees = 13;
```

- Update `ConsensusTopicInfoHandler` class.
  - Add information about the new fields `feeScheduleKey()`, `feeExemptKeyList()`, `customFees()` to the query response.
  - Add needed fields and methods in `HapiCetTopicInfo` to support the new fields.

#### ConsensusCreateTopic

- Add default values for `maxCustoFeeEntriesForTopics` and `maxEntriesForFeeExemptKeyList`
- Update `ConsensusCreateTopicHandler`
  - Pure-check:
    - Check if all keys in FEKL are unique.
  - Handle:
    - Add validation on keys - `Fee Schedule Key` and all keys from the `Fee Exempt Key List`.
    - Add validation on custom fees:
      - Check if fee collector account is usable (not deleted/expired).
      - Check if the fee type is `fixed`.
      - If the fee has denominating token:
        - Check if the token is usable
        - Check if the token is fungible
        - Check if the fee amount is no more then the token's max supply
        - Check if the collector is associated with the token
    - Store the values of the new fields in the state.

#### ConsensusUpdateTopic

- Update `ConsensusUpdateTopicHandler`
  - Pure-check:
    - Check if all keys in FEKL are unique.
  - Pre-handle:
    - Check for `Fee Schedule Key` signature, in case of fees updates.
  - Handle:
    - Add validation on keys
      - `Fee Schedule Key` and all keys from the `Fee Exempt Key List`.
      - Verify that the size is no more than the config value(`maxEntriesForFeeExemptKeyList`)
    - Add validation on custom fees:
      - Verify that the size is no more than the config(`maxCustomFeeEntriesForTopics`)
      - Check if the fee collector account is usable (not deleted/expired).
      - Check if the fee type is `fixed`.
      - If the fee has denominating token:
        - Check if the token is usable
        - Check if the token is fungible
        - Check if the fee amount is no more then the token's max supply
        - Check if the collector is associated with the token
    - Update the values of the new fields in the state.

#### ConsensusSubmitMessage

- Update `ConsensusSubmitMessageHandler`
  - Handle:
    - Check if the topic has custom fees.
    - Check if the payer is fee exempt.
    - Check if max fee limit covers the total fees.
    - Build and Dispatch a crypto transfer transaction to pay the fees.

## Acceptance Tests

### Topic create acceptance tests

* Create topic with `feeScheduleKey`. The topic should have a `feeScheduleKey`.
* Create topic with `feeExemptKeyList`. The topic should have a `feeExemptKeyList`.
* Create topic with fixed HBAR `customFees`. The topic should have fixed HBAR `customFees`.
* Create topic with fixed fungible token `customFees`. The topic should have fixed fungible token `customFees`.
* Create topic with invalid custom fee. The transaction should fail.
* Create topic with more than ten custom fees. The transaction should fail.
* Create topic with more than ten fee exempt keys. The transaction should fail.

### Topic update acceptance tests

* Update the custom fee value. Sign with the feeScheduleKey. Custom fee should be updated.
* Update the custom fee from FT to HBAR. The custom fee should now be HBAR.
* Update the custom fee from HBAR to FT. The custom fee should now be FT.
* Update the custom fee to remove all custom fees. The topic shouldn't have custom fees.
* Update the custom fee to remove one of two custom fees. The topic should have only one custom fee.
* Update the topic with no fee exempt key list to add a valid entry in the list. The topic should have an entry in the
  list.
* Update the topic with a fee exempt key list entry to add a new entry. Now the topic should have two entries.
* Update the topic with a fee exempt key list entry to remove a new entry. Now the topic should have a single entry.
* Update the topic with a fee exempt key list to remove all entries. Now the topic should have no entries.
* Update the feeScheduleKey and sign with the old one. The transaction should fail with `INVALID_SIGNATURE`

### Submit message acceptance tests

* Submit a message to a topic with no custom fees. The transaction should succeed.
* Submit a message to a topic with HBAR custom fees and the payer has sufficient balance. The transaction should succeed.
* Submit a message to a topic with HBAR custom fees and the payer has insufficient balance. The transaction should fail.
* Submit a message to a topic with fungible token custom fees and the payer has sufficient balance. The transaction should
  succeed.
* Submit a message to a topic with fungible token custom fees and the payer has insufficient balance. The transaction should
  fail.
* Submit a message to a topic with custom fees and the payer is in the fee exempt list. The transaction should succeed and payer should be fee exempt.
* Submit a message to a topic with custom fees but the max fee is not enough. The transaction should fail.
* Submit a message with acceptAllCustomFees set to true. The transaction should succeed.
