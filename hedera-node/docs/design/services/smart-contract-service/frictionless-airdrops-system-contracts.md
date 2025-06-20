# Frictionless Airdrops via System Contracts

## Purpose

[HIP-904](https://hips.hedera.com/hip/hip-904) introduced the Frictionless Airdrops feature for fungible and non-fungible tokens.
This document will define the architecture and implementation of the `airdropToken`, `claimAirdrops`, `cancelAirdrops` and `rejectTokens` smart contract functions
and their respective redirect function calls (`cancelAirdropFT`, `cancelAirdropNFT`, `claimAirdropFT`, `claimAirdropNFT`, `rejectTokenFT`, `rejectTokenNFTs`, `setUnlimitedAutomaticAssociations`) that will extend the capabilities of the Hedera Smart Contract Service (HSCS) to support frictionless airdrops.

## References

[HIP-904](https://hips.hedera.com/hip/hip-904) - HIP that introduces the frictionless airdrops.

## Goals

- Expose `airdropToken`, `claimAirdrops`, `cancelAirdrops` and `rejectTokens` as new functions in the Hedera Token Service Smart Contract.
- Expose `cancelAirdropFT`, `cancelAirdropNFT`, `claimAirdropFT`, `claimAirdropNFT`, `rejectTokenFt`, `rejectTokenNFTs`, `setUnlimitedAutomaticAssociations` as new functions in a new proxy redirect token facade contract IHRC904.
- Implement the needed HTS system contract classes to support the new functions.

## Non Goals

- The implementation of the HAPI operation, as it is already an existing feature.

## Architecture

The architecture for the frictionless airdrops follows the existing framework defined for handling all calls to the HederaTokenService system contract in the modularization services and is described in more detail in the Implementation section below.

## Implementation

### New Solidity Structures

We will introduce the following new structures to support the new functionality:

`PendingAirdrop` - A struct that represents a pending airdrop request.

```solidity
struct PendingAirdrop {
    address sender;
    address receiver;

    address token;
    int64 serial;
}
```

`NftID` - A struct that represents the Nft serial to be rejected.

```solidity
struct NftID {
    address nft;
    int64 serial;
}
```

### New Solidity Functions

New system contract functions must be added to the `IHederaTokenService` interface to support airdropping tokens.

| Function Selector Hash |                                                               Function Signature                                                               | HAPI Transaction |    Response    |                                 |
|------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|------------------|----------------|---------------------------------|
| `0x2f348119`           | `function airdropTokens(TokenTransferList[] memory tokenTransfers) external returns (int64 responseCode)`                                      | TokenAirdrop     | `ResponseCode` | The response code from the call |
| `0x012ebcaf`           | `function cancelAirdrops(PendingAirdrop[] memory pendingAirdrops) external returns (int64 responseCode)`                                       | TokenCancel      | `ResponseCode` | The response code from the call |
| `0x05961641`           | `function claimAirdrops(PendingAirdrop[] memory pendingAirdrops) external returns (int64 responseCode)`                                        | TokenClaim       | `ResponseCode` | The response code from the call |
| `0xebd595e0`           | `function rejectTokens(address rejectingAaddress, address[] memory ftAddresses,  NftID[] memory nftIDs) external returns (int64 responseCode)` | TokenReject      | `ResponseCode` | The response code from the call |

New system contract functions must be added to a new `IHRC904` interface to support airdropping tokens.

| Function Selector Hash |                                               Function Signature                                                | HAPI Transaction | Responsible service |    Response    |                                 |
|------------------------|-----------------------------------------------------------------------------------------------------------------|------------------|---------------------|----------------|---------------------------------|
| `0xcef5b705`           | `function cancelAirdropFT(address receiverAddress) external returns (int64 responseCode)`                       | TokenCancel      | HTS                 | `ResponseCode` | The response code from the call |
| `0xad4917cf`           | `function cancelAirdropNFT(address receiverAddress, int64 serialNumber) external returns (int64 responseCode)`  | TokenCancel      | HTS                 | `ResponseCode` | The response code from the call |
| `0xa83bc5b2`           | `function claimAirdropFT(address senderAddress) external returns (int64 responseCode)`                          | TokenClaim       | HTS                 | `ResponseCode` | The response code from the call |
| `0x63ada5d7`           | `function claimAirdropNFT(address senderAddress, int64 serialNumber) external returns (int64 responseCode)`     | TokenClaim       | HTS                 | `ResponseCode` | The response code from the call |
| `0x76c6b391`           | `function rejectTokenFT() external returns (int64 responseCode)`                                                | TokenReject      | HTS                 | `ResponseCode` | The response code from the call |
| `0xa869c78a`           | `function rejectTokenNFTs(int64[] memory serialNumbers) external returns (int64 responseCode)`                  | TokenReject      | HTS                 | `ResponseCode` | The response code from the call |
| `0xf5677e99`           | `function setUnlimitedAutomaticAssociations(bool enableAutoAssociations) external returns (int64 responseCode)` | CryptoUpdate     | HAS                 | `ResponseCode` | The response code from the call |

#### Input limitations

- The `airdropTokens` function will accept an array of `TokenTransferList` with a maximum of 10 elements by default managed by `tokens.maxAllowedAirdropTransfersPerTx` configuration.
- The `cancelAirdrops` function will accept an array of `PendingAirdrop` with a maximum of 10 elements by default managed by `tokens.maxAllowedPendingAirdropsToCancel` configuration.
- The `claimAirdrops` function will accept an array of `PendingAirdrop` with a maximum of 10 elements by default managed by `tokens.maxAllowedPendingAirdropsToClaim` configuration.
- The `rejectTokens` function will accept array of `address` and `NftID` with a maximum of 10 elements combined by default managed by `ledger.tokenRejects.maxLen` configuration. Same limitation applies to `rejectTokenNFTs` function.
- The `setAutomaticAssociations` function will accept a boolean value to set the automatic associations to -1 if true and 0 for false.

### System Contract Module

- `AirdropTokensTranslator` - This class will be responsible for handling the `airdropTokens` selector and dispatching it to the corresponding HAPI calls.
- `AirdropTokensDecoder` - This class provides methods and constants for decoding the given `HtsCallAttempt` into a `TokenTransferList` for `TokenAirdrop` call.
- `CancelAirdropsTranslator` - This class will be responsible for handling the `cancelAirdrops`, `cancelAirdropFT` and `cancelAirdropNFT` selectors and dispatching them to the corresponding HAPI calls.
- `CancelAirdropsDecoder` - This class provides methods and constants for decoding the given `HtsCallAttempt` into a `PendingAirdropId` list for `TokenCancelAirdrop` call.
- `ClaimAirdropsTranslator` - This class will be responsible for handling the `claimAirdrops`, `claimAirdropFT` and `claimAirdropNFT` selectors and dispatching them to the corresponding HAPI calls.
- `ClaimAirdropsDecoder` - This class provides methods and constants for decoding the given `HtsCallAttempt` into a `PendingAirdropId` list for `TokenClaimAirdrop` call.
- `RejectTokensTranslator` - This class will be responsible for handling the `rejectTokens`, `rejectTokenFT` and `rejectTokenNFT` selectors and dispatching them to the corresponding HAPI calls.
- `RejectTokensDecoder` - This class provides methods and constants for decoding the given `HtsCallAttempt` into `TokenReference` list for `TokenReject` call.
- `SetAutomaticAssociationsTranslator` - This class will be responsible for handling the `setAutomaticAssociations` selector and dispatching it to the corresponding HAPI calls.
- `SetAutomaticAssociationsCall` - This class provides methods for preparing and executing the given `HasCallAttempt`.

### Feature Flags

In order to gate the newly introduced system contract calls, we will introduce the following feature flags:
- `contracts.systemContract.airdropTokens.enabled` - Enable/Disable the `airdropTokens` system contract call.
- `contracts.systemContract.cancelAirdrops.enabled` - Enable/Disable the `cancelAirdrops`, `cancelAirdropFT` and `cancelAirdropNFT` system contract calls.
- `contracts.systemContract.claimAirdrops.enabled` - Enable/Disable the `claimAirdrops`, `claimAirdropFT` and `claimAirdropNFT` system contract calls.
- `contracts.systemContract.rejectTokens.enabled` - Enable/Disable the `rejectTokens`, `rejectTokenFT` and `rejectTokenNFTs` system contract calls.
- `contracts.systemContract.setUnlimitedAutoAssociations.enabled` - Enable/Disable the `setUnlimitedAutomaticAssociations` system contract call.

## Security Implications

The newly added flows will adopt the HAPI authorization logic and the security V2 model.
We will apply the `TokenReject`, `TokenAirdrop`, `TokenClaimAirdrop`, `TokenCancelAirdrop` throttle mechanisms.

## Acceptance Tests

### BDD Tests

#### Positive Tests

- Verify that the `airdropTokens` function airdrops multiple tokens both ft and nft to multiple accounts.
- Verify that the `airdropTokens` function airdrops 10 tokens both ft and nft to multiple accounts.
- Verify that the `airdropTokens` function can airdrop multiple fungible tokens to multiple accounts.
- Verify that the `airdropTokens` function can airdrop multiple nft tokens to multiple accounts.
- Verify that the `airdropTokens` function airdrops a fungible token to an account.
- Verify that the `airdropTokens` function airdrops a nft token to an account.
- Verify that the `cancelAirdrops` function cancels multiple pending airdrops.
- Verify that the `cancelAirdrops` function cancels 10 pending airdrops.
- Verify that the `cancelAirdrops` function cancels single pending fungible token airdrop.
- Verify that the `cancelAirdrops` function cancels single pending nft airdrop.
- Verify that the `claimAirdrops` function claims multiple pending airdrops.
- Verify that the `claimAirdrops` function claims 10 pending airdrops.
- Verify that the `claimAirdrops` function claims a single pending fungible token airdrop.
- Verify that the `claimAirdrops` function claims a single pending nft airdrop.
- Verify that the `rejectTokens` function rejects fungible token for an account.
- Verify that the `rejectTokens` function rejects nft token for an account.
- Verify that the `rejectTokens` function rejects multiple tokens for an account.
- Verify that the `cancelAirdropFT` function cancels a pending airdrop of the redirected token.
- Verify that the `cancelAirdropNFT` function cancels a pending airdrop of the redirected nft serial.
- Verify that the `claimAirdropFT` function claims a pending airdrop of the redirected token.
- Verify that the `claimAirdropNFT` function claims a pending airdrop of the redirected nft serial number.
- Verify that the `rejectTokenFT` function rejects tokens for a given account.
- Verify that the `rejectTokenNFTs` function rejects tokens for a given account and serial number.
- Verify that the `rejectTokenNFTs` function rejects 10 nft serials for a given account.
- Verify that the `setUnlimitedAutomaticAssociations` function enables the automatic associations to unlimited (-1) for a given account.
- Verify that the `setUnlimitedAutomaticAssociations` function disables the automatic associations to zero for a given account.

#### Negative Tests

- Verify that the `airdropTokens` function fails when the sender does not have enough balance.
- Verify that the `airdropTokens` function fails when the receiver does not have a valid account.
- Verify that the `airdropTokens` function fails when the token does not exist.
- Verify that the `airdropTokens` function fails when the airdropped serials are out of bounds.
- Verify that the `airdropTokens` function fails when 11 or more airdrops are provided.
- Verify that the `cancelAirdrops` function fails when the sender does not have any pending airdrops.
- Verify that the `cancelAirdrops` function fails when the sender does not have a valid account.
- Verify that the `cancelAirdrops` function fails when the receiver does not have a valid account.
- Verify that the `cancelAirdrops` function fails when the token does not exist.
- Verify that the `cancelAirdrops` function fails when the nft does not exist.
- Verify that the `cancelAirdrops` function fails when 11 or more pending airdrops are provided.
- Verify that the `cancelAirdrops` function fails when the nft serial number does not exist.
- Verify that the `claimAirdrops` function fails when the sender does not have any pending airdrops.
- Verify that the `claimAirdrops` function fails when the sender does not have a valid account.
- Verify that the `claimAirdrops` function fails when 11 or more pending airdrops are provided.
- Verify that the `claimAirdrops` function fails when the receiver does not have a valid account.
- Verify that the `claimAirdrops` function fails when the token does not exist.
- Verify that the `claimAirdrops` function fails when the nft does not exist.
- Verify that the `claimAirdrops` function fails when the nft serial number does not exist.
- Verify that the `rejectTokens` function fails when the sender does not have any associated tokens.
- Verify that the `rejectTokens` function fails when the provided fungible token is invalid.
- Verify that the `rejectTokens` function fails when the provided nft is invalid.
- Verify that the `rejectTokens` function fails when 11 or more tokens are provided.
- Verify that the `cancelAirdropFT` function fails when the sender does not have any pending airdrops.
- Verify that the `cancelAirdropFT` function fails when the receiver does not have a valid account.
- Verify that the `cancelAirdropNFT` function fails when the sender does not have any pending airdrops.
- Verify that the `cancelAirdropNFT` function fails when the receiver does not have a valid account.
- Verify that the `claimAirdropFT` function fails when the sender does not have any pending airdrops.
- Verify that the `claimAirdropFT` function fails when the sender does not have a valid account.
- Verify that the `claimAirdropNFT` function fails when the sender does not have any pending airdrops.
- Verify that the `claimAirdropNFT` function fails when the sender does not have a valid account.
- Verify that the `rejectTokenFT` function fails when the sender does not have any tokens.
- Verify that the `rejectTokensNFT` function fails when the sender does not have any tokens.
- Verify that the `rejectTokensNFT` function fails when 11 or more serials are provided.
