# System Contract Versioning

## Purpose

This document explores the implementation changes needed to support and test versioning of system contracts in the Hedera Smart Contract Service.

## Architecture and Implementation

### Relevant Current Implementation

Currently, the handling of HTS, HAS and HSS system function calls follows similar pattern as described below:

1. A particular descendant of the `SystemContract` class is identified by the contract address for processing the call.
2. A descendant of the `AbstractCallAttempt` class is created from the call data and other information available in the message frame.
3. The `AbstractCallAttempt` descendant is tested using the `identifyMethod` against a list of class that implement the `CallTranslator` interface provided by dagger in order to determine which translator is responsible for processing the function call.
4. The `CallTranslator` descendant decodes and calls the necessary helper classes to either dispatch or process the call.
5. The `CallTranslator` also keeps metadata about the system contract function in the `SystemContractMethod` class.

### Proposed Changes

The following changes are needed in order to support versioning of system contracts:
1. Descendants of the `SystemContract` class will map to more than one contract address.  The dagger provided map allows for the mapping of multiple address to a single `SystemContract` class.
2. Descendants of the `AbstractCallAttempt` class will be augmented with a system contract address field.  Otherwise the current common `AbstractCallAttempt` and related `CallFactory` class descendants will be used commonly by all system contracts of the same type.
3. The `SystemContractMethod` class metadata will be augmented with an enum set of supported system contract addresses which can include an item that can denote _all_ addresses.
4. The `isMethod` function support by `CallTranslator` descendants will be augmented to check the system contract address field in the `AbstractCallAttempt` descendant in order to determine a matching translator.

### Package Organization

In order to organize and support multiple addresses for a system contract, a sensible package must be introduced.
Currently , all `CallTranslator` descendant classes and related helper classes are packaged together under the system contract package.
Functions supported under a new system contract address for the same system contract type will be placed in a new package with the name `address_<system_contract_address>`.
The packages at top level for a system contract will contain the base implementation which will be used if no override implementation of the function is available.

For example, the HTS system contract will have the following packages where a new address for the HTS system contract at address `0x16c` is introduced and overrides the `create` function:

```
...
   |--- hts
      airdrops
      allowance
      associations
      ...
      create
      ...
      |--- address_0x16c
            create
            ...
```

## Testing

As with the `Package Organization` discussed above, bdd tests will also need to be organized in order to test variant
expectations for overridden functions in the system contract.

The path for looking up contract bin files uploading contract initcode will be augmented with an optional system contract address
to differentiate contracts using different system contract addresses.
Currently, the path used to locate contracts is `hedera-node/test-clients/src/test/resources/contracts`.
The path for the new system contract address will be `hedera-node/test-clients/src/test/resources/contracts_<system_contract_address>` if the optional
system contract address is given to the argument to the `uploadInitCode` utility function.
Similarly, an attribute will be added to the @Contract annotation to denote the system contract address for the contract.

New tests that that cover the new system contract address' behavior will be added:
1. into new class files
2. into existing class files using the @Nested annotation to group the tests together.
3. into existing tests using the @ParameterizedTest annotation to test the same function with different system contract addresses.
as appropriate in order to increase test readability and maintainability.

## Scope for System Contract Version Releases

In order to ensure that the system contract are immutable from a user standpoint,
each new version of a system contract needs to be scope in terms of expected behavior.  A master feature flag for the
version will be used to control release of the new system contract version to gate access
as user deployed contract that are written to partially implemented system contract will break once behavior is altered.

## Scope for Next HTS System Contract Version

The next system contract version for HTS will encompass the following
scope:

1. HIP-1028 - Metadata HIP
   2. Updates to the `HederaToken` struct with resultant cascading changes to the `TokenInfo`, `FungibleTokenInfo` and `NonFungibleTokenInfo` structs.
2. Update the HTS static functions to return a response code to indicate invalid input instead of reverting on incorrect input.
