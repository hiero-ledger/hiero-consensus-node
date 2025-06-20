# Metadata management via smart contracts

## Purpose

HIP-646/657/765 introduces a new metadata field for Fungible Tokens (FT), Non-Fungible Tokens (NFT) and METADATA key for
updating metadata. However, these features are not supported by smart contracts.
This proposal aims to enhance Hedera Smart Contract Service (HSCS) by exposing HIP-646/657/765 features.

## Goals

Allow users to create, update and get info for metadata and metadata key using smart contracts
1. Update the `HederaToken` struct with `bytes metadata` field in `IHederaTokenService.sol`
2. Extend `TokenKey` struct with comment that the 7th bit will be metadata key in `IHederaTokenService.sol`
3. Add new System Contracts for the relative HAPI operations with metadata support on System Contract address `0x16c`
4. Add new function `updateNFTsMetadata(address token, int64[] memory serialNumbers, bytes memory metadata)` dispatching to
TokenUpdateNfts HAPI operation

## Non Goals

- The implementation of the HAPI operation, as it is already an existing feature.

## Architecture

The system contract versioning enables us to add new functions or update existing structures/functions to the smart contracts without breaking existing logic.

The proposed changes of HIP-1028 will be exposed in the new version of the smart contract API, which will be deployed on address `0x16c`.
We will update the `IHederaTokenService.sol` interface to include the new `metadata` field in the `HederaToken` struct and extend the `TokenKey` struct to include the metadata key.
We will also add a new function to handle the `TokenUpdateNfts` HAPI operation.

However, the old versions of `IHederaTokenService.sol` will continue working on address `0x167`.

### Solidity changes

Updated `HederaToken` struct

````solidity
struct HederaToken {
    String name;
    String symbol;
    // other fields identical to the existing HederaToken struct
    ...
    // The new field for metadata
    bytes metadata;
}
````

Updated `TokenKey` struct

````solidity
struct TokenKey {
    // bit field representing the key type. Keys of all types that have corresponding bits set to 1
    // will be created for the token.
    // 0th bit: adminKey
    // 1st bit: kycKey
    // 2nd bit: freezeKey
    // 3rd bit: wipeKey
    // 4th bit: supplyKey
    // 5th bit: feeScheduleKey
    // 6th bit: pauseKey

    // the 7th bit will be metadata key
    // 7th bit: metadataKey
    uint keyType;

    // the value that will be set to the key type
    KeyValue key
}
````

The `TokenInfo`, `FungibleTokenInfo` and `NonFungibleTokenInfo` are also affected, as they have cascading modifications because of the inclusion of the `HederaToken` struct.

### Updated Function selectors

With the updated structures the function selector hashes will also change:

|   Hash   |                                                                                  Selector                                                                                   |                                  Return                                  |
|----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------|
| 0fcaca1f | `updateNFTsMetadata(address token, int64[] memory serialNumbers, bytes memory metadata)`                                                                                    | `int64 responsecode`                                                     |
| ad7f8f0b | `createNonFungibleToken(HederaToken memory token)`                                                                                                                          | `(int64 responseCode, addess tokenAddress)`                              |
| c5bc16bc | `createNonFungibleTokenWithCustomFees(HederaToken memory token, FixedFee[] memory fixedFees,RoyaltyFee[] memory royaltyFees)`                                               | `(int64 responseCode, addess tokenAddress)`                              |
| ce35bd38 | `createFungibleToken(HederaToken memory token, int64 initialTotalSupply, int32 decimals)`                                                                                   | `(int64 responseCode, addess tokenAddress)`                              |
| 5ac3e67a | `createFungibleTokenWithCustomFees(HederaToken memory token, int64 initialTotalSupply, int32 decimals, FixedFee[] memory fixedFees, FractionalFee[] memory fractionalFees)` | `(int64 responseCode, addess tokenAddress)`                              |
| 54c832a5 | `updateTokenInfo(address token, HederaToken memory tokenInfo)`                                                                                                              | `int64 responseCode`                                                     |
| 1f69565f | `getTokenInfo(address token)`                                                                                                                                               | `(int64 responseCode, TokenInfo memory tokenInfo)`                       |
| 287e1da8 | `getNonFungibleTokenInfo(address token, int64 serialNumber)`                                                                                                                | `(int64 responseCode, NonFungibleTokenInfo memory nonFungibleTokenInfo)` |
| 3f28a19b | `getFungibleTokenInfo(address token)`                                                                                                                                       | `(int64 responseCode, FungibleTokenInfo memory fungibleTokenInfo)`       |

## System Contract Module

### Versioned System Contract Translators/Decoders

We will add new implementation for the existing translator/decoder logic to handle the new version of the smart contract API packaged as `address_0x16c`.

- `TokenCreateTranslator` - This class will be responsible for handling the `createFungibleToken`, `createFungibleTokenWithCustomFees`, `createNonFungibleToken` and `createNonFungibleTokenWithCustomFees` selectors and dispatching them to the TokenCreate HAPI operation.
- `TokenUpdateTranslator` - This class will be responsible for handling the `updateTokenInfo` selector and dispatching it to the TokenUpdate HAPI operation.
- `TokenGetInfoTranslator` - This class will be responsible for handling the `getTokenInfo` selectors and dispatching them to the TokenGetInfo HAPI operation.
- `TokenUpdateNftsMetadataTranslator` - This class will be responsible for handling the `updateNFTsMetadata` selector and dispatching it to the TokenUpdateNfts HAPI operation.
- `TokenUpdateKeysTranslator` - This class will be responsible for handling the `updateTokenKeys` selector and dispatching it to the TokenUpdateKeys HAPI operation.
- `FungibleTokenInfoTranslator` - This class provides methods and constants for decoding the `TokenGetInfoResponse` into a `PricedResult`.
- `NonFungibleTokenInfoTranslator` - This class provides methods and constants for decoding the `TokenGetInfoResponse` into a `PricedResult`.

The above classes exist with corresponding Decoder or Call classes to handle the decoding of the ABI or prepare the result responses.

## Acceptance Tests

The tests are to be targeted at 0x16c system contract address.

### Positive Tests

- Verify `createFungibleToken` creates a fungible token with metadata
- Verify `createFungibleTokenWithCustomFees` creates a fungible token with metadata
- Verify `createNonFungibleToken` creates a non-fungible token with metadata
- Verify `createNonFungibleTokenWithCustomFees` creates a non-fungible token with metadata
- Verify `updateTokenInfo` updates token info with metadata
- Verify `getFungibleTokenInfo` returns the correct metadata for a fungible token
- Verify `getTokenInfo` returns the correct metadata for a token
- Verify `getNonFungibleTokenInfo` returns the correct metadata for a non-fungible token
- Verify `createFungibleToken` creates a fungible token with old version of the function
- Verify `createFungibleTokenWithCustomFees` creates a fungible token with old version of the function
- Verify `createNonFungibleToken` creates a non-fungible token with old version of the function
- Verify `createNonFungibleTokenWithCustomFees` creates a non-fungible token with old version of the function
- Verify `updateTokenInfo` updates token info with old version of the function
- Verify `updateTokenKeys` updates token metadata when metadata key is set
- Verify `createFungibleToken` creates a fungible token with metadata and metadata key
- Verify `createFungibleTokenWithCustomFees` creates a fungible token with metadata and metadata key
- Verify `createNonFungibleToken` creates a non-fungible token with metadata and metadata key
- Verify `createNonFungibleTokenWithCustomFees` creates a non-fungible token with metadata and metadata key
- Verify `updateTokenInfo` updates token info with metadata and metadata key
- Verify `getFungibleTokenInfo` returns the correct metadata for a fungible token with metadata key
- Verify `getTokenInfo` returns the correct metadata for a token with metadata key
- Verify `getNonFungibleTokenInfo` returns the correct metadata for a non-fungible token with metadata key
- Verify `updateNftsMetadata` updates metadata for multiple NFTs
- Verify `updateNftsMetadata` updates metadata for single NFT

### Negative Tests

- Verify `updateTokenInfo` fails to update token info with metadata when metadata key is not set
- Verify `updateTokenInfo` fails to update token info with metadata when metadata key is different
- Verify `updateNftsMetadata` fails to update metadata for multiple NFTs when metadata key is not set
- Verify `updateNftsMetadata` fails to update metadata for multiple NFTs when metadata key is different
