# Scheduled Token Create System Contract

## Purpose

[HIP-755](https://hips.hedera.com/hip/hip-755) defines a new system contract that allows accounts and contracts to interact with the schedule service system contract enabling the signing of scheduled transactions.
[HIP-756](https://hips.hedera.com/hip/hip-756) defines a new system contract functions to schedule token creation and updates in order for a smart contract to be able to designate accounts for autoRenew and/or treasury roles.
This document will define the architecture and implementation of this functionality.

## Goals

- Provide the users with the ability to schedule token creation and updates.
- Provide the ability to retrieve the information about a scheduled token creation/update.
- ~~Provide the ability to retrieve the newly created token address via scheduled transaction.~~

## Non Goals

- The implementation of the Schedule Service logic as it is already done
- The implementation of the HSSSystemContract infrastructure wiring as it is already done with HIP-755
- The modification of existing Scheduled Transactions like updating or deleting is out of scope for this HIP.
- The implementation of a more generic `scheduleContractCall` to enable cron like functionality for calling contract functions.
  While this functionality will not be explicitly designed in this document, care will be taken to ensure that the design is extensible to support this in the future.
- Provide the ability to retrieve the newly created token address via scheduled transaction.

## Architecture and Implementation

The architecture for the scheduled creations follows the existing framework defined for handling all calls to the HederaScheduleService system contract in the modularization services and is described in more detail in the section below.
One difference is that instead of creating multiple functions for each type of HTS system contract functions, we will have a single function called `scheduleNative` that will be able to handle all types of HTS functions, starting with token creation and updates. This function will
be responsible for assembling the correct `TransactionBody` which defines the inner transaction that will be executed by the Schedule Service. As the primary goal
is to gather the required signatures for the scheduled transaction, the `waitForExpiry` flag will be set to `false` for all scheduled token creation calls created via the `scheduleNative` function.

### New Solidity Functions

| Function Selector Hash |                                    Short Selector                                     |                                                                  Function Signature                                                                  | HAPI operation  |                           Description                            |
|------------------------|---------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------|------------------------------------------------------------------|
| `0xda2d5f8f`           | `getScheduledCreateFungibleTokenInfo(address scheduleAddress)`                        | `function getScheduledCreateFungibleTokenInfo(address scheduleAddress) returns (int64 responseCode, FungibleTokenInfo memory tokenInfo)`             | ScheduleGetInfo | Retrieve information for the scheduled token create              |
| `0xd68c902c`           | `getScheduledCreateNonFungibleTokenInfo(address scheduleAddress)`                     | `function getScheduledCreateNonFungibleTokenInfo(address scheduleAddress) returns (int64 responseCode, NonFungibleTokenInfo memory tokenInfo)`       | ScheduleGetInfo | Retrieve information for the scheduled nft create                |
| `0xca829811`           | `scheduleNative(address systemContractAddress, bytes callData, address payerAddress)` | `function scheduleNative(address systemContractAddress, bytes callData, address payerAddress) returns (int64 responseCode, address scheduleAddress)` | ScheduleCreate  | Schedule a token create or update as determined by the call data |

### System Contract Module

- `ScheduleNativeTranslator` - This class will be responsible for handling the `scheduleNative` selector and dispatching it to the Schedule Service.
- `ScheduleNativeCall` - This class provides methods and constants for decoding the given `HssCallAttempt` into the appropriate `TransactionBody` by using the `asSchedulableDispatchIn` method of the `Call` interfaced described below.
- `GetScheduledInfoTranslator` - This class will be responsible for handling the `getScheduledCreateFungibleTokenInfo` and `getScheduledCreateNonFungibleTokenInfo` selectors and dispatching them to the Schedule Service.
- `GetScheduledTokenInfoCall` - This class provides methods and constants for decoding the `ScheduleGetInfoResponse` into a `PricedResult`.
- `GetScheduledNonFungibleTokenInfoCall` - This class provides methods and constants for decoding the `ScheduleGetInfoResponse` into a `PricedResult`.

### Supported Function Calls

In order to maintain control over which functions are supported by the `scheduleNative` function, the call data will be validated to ensure that the function selector matches the following function definitions:
`createFungibleToken(HederaToken memory token,int64 initialTotalSupply, int32 decimals)`
`createFungibleTokenWithCustomFees(HederaToken memory token, int64 initialTotalSupply, int32 decimals, FixedFee[] memory fixedFees, FractionalFee[] memory fractionalFees)`
`createNonFungibleToken(HederaToken memory token)`
`createNonFungibleTokenWithCustomFees(HederaToken memory token, FixedFee[] memory fixedFees, RoyaltyFee[] memory royaltyFees)`
`updateTokenInfo(address token, HederaToken memory tokenInfo)`

Call data that does not match these function definitions will result in a `SCHEDULED_TRANSACTION_NOT_IN_WHITELIST` response code.

### New method in Call Interface

The current `Call` interface will be extended to include a new method `asSchedulableDispatchIn` that will be used to convert the `Call` object into a `TransactionBody` object.
Existing functions that produce a `TransactionBody` object will be updated to use this new method.

```
/**
* Encapsulates a call to the HTS system contract.
*/
public interface Call {
...
  /**
  * @param frame the message frame
    * @return the native TransactionBody implied by this call
    */
      @NonNull
      default TransactionBody asSchedulableDispatchIn(MessageFrame frame) {
          throw new UnsupportedOperationException("Needs scheduleNative() support");
      }
    }
```

### Gas Costs

The gas costs will consist of the price for the call to the Schedule Service plus the intrinsic gas cost plus mark-up.
The calling contract should then have the balance to cover the cost of the Native transaction.

### Feature Flags

In order to gate the newly introduced system contract calls, we will add the following feature flag:
- `contracts.systemContract.scheduleService.scheduleNative.enabled` - to enable the `scheduleNative` function.

## Security Implications

The newly added flows will adopt the HAPI authorization logic and the security V2 model.
The throttles for `ScheduledCreate` and `ScheduledGetInfo` will be applied to the newly added functions.

## Phased Implementation

1. Implement the `scheduleNative` system contract function.
2. Implement the `getScheduledCreateFungibleTokenInfo`, `getScheduledCreateNonFungibleTokenInfo` system contract functions.

## Acceptance Tests

### BDD Tests

#### Positive Tests

- validate that `getScheduledCreateFungibleTokenInfo` returns the correct token info for a given schedule address.
- validate that `getScheduledCreateNonFungibleTokenInfo` returns the correct non-fungible token info for a given schedule address.
- validate that `scheduleNative` successfully creates a schedule for create token and returns the schedule address.
- validate that `scheduleNative` successfully creates a schedule for create token with a designated payer and returns the schedule address.
- validate that `scheduleNative` successfully creates a schedule for create token with custom fees and returns the schedule address.
- validate that `scheduleNative` successfully creates a schedule for create nft and returns the schedule address.
- validate that `scheduleNative` successfully creates a schedule for create nft with a designated payer and returns the schedule address.
- validate that `scheduleNative` successfully creates a schedule for create nft with custom fees and returns the schedule address.
- validate that `scheduleNative` successfully creates a schedule for token update and returns the schedule address.
- validate that `scheduleNative` successfully creates a schedule for token update with a designated payer and returns the schedule address.
- validate that the gas cost is correctly calculated for the newly added functions.

#### Negative Tests

- validate that `getScheduledCreateFungibleTokenInfo` returns an error for a non-existing schedule address.
- validate that `getScheduledCreateNonFungibleTokenInfo` returns an error for a non-existing schedule address.
- validate that the create/update functions would not be executed if the required signers did not sign the schedules.
