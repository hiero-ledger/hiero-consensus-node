# Metadata management via smart contracts

## Purpose

HIP-646/657/765 introduces a new metadata field for Fungible Tokens (FT), Non-Fungible Tokens (NFT) and METADATA key for
updating metadata. However, these features are not supported by smart contracts.
This proposal aims to enhance Hedera Smart Contract Service (HSCS) by exposing HIP-646/657/765 features.

## Goals

Allow users to create, update and get info for metadata and metadata key using smart contracts
1. Create `HederaTokenV2` struct with `bytes metadata` field in `IHederaTokenService.sol`
2. Create `TokenInfoV2`, `FungibleTokenInfoV2` and `NonFungibleTokenInfoV2` structs with `HederaTokenV2`
2. Extend `TokenKey` struct with comment that the 7th bit will be metadata key in `IHederaTokenService.sol`
3. Add new versions of the create and update functions in Translators and Decoders
4. Add new functions for view operations: `getTokenInfoV2(address token)`,
`getNonFungibleTokenInfoV2(address token, int64 serialNumber)`, `getFungibleTokenInfoV2(address token)`
5. Add new function `updateNFTsMetadata(address token, int64[] memory serialNumbers, bytes memory metadata)` dispatching to
TokenUpdateNfts HAPI operation

## Non Goals

- The implementation of the HAPI operation, as it is already an existing feature.

## Architecture

The new structs will change the signature of functions. However the old versions of `IHederaTokenService.sol`
will continue working. To achieve this we will add new versions of the functions in Translators and Decoders.

We need to add new functions for view calls since they are returning new structs and we want old
versions of `IHederaTokenService.sol` functions to continue working

We need to add a new function for the TokenUpdateNfts HAPI operation

|   Hash   |                                                                                   Selector                                                                                    |                                   Return                                   |
|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| 0fcaca1f | `updateNFTsMetadata(address token, int64[] memory serialNumbers, bytes memory metadata)`                                                                                      | `int responsecode`                                                         |
| ad7f8f0b | `createNonFungibleToken(HederaTokenV2 memory token)`                                                                                                                          | `(int responseCode, addess tokenAddress)`                                  |
| c5bc16bc | `createNonFungibleTokenWithCustomFees(HederaTokenV2 memory token, FixedFee[] memory fixedFees,RoyaltyFee[] memory royaltyFees)`                                               | `(int responseCode, addess tokenAddress)`                                  |
| 7cb8323a | `createFungibleToken(HederaTokenV2 memory token, int64 initialTotalSupply, int32 decimals)`                                                                                   | `(int responseCode, addess tokenAddress)`                                  |
| 5ac3e67a | `createFungibleTokenWithCustomFees(HederaTokenV2 memory token, int64 initialTotalSupply, int32 decimals, FixedFee[] memory fixedFees, FractionalFee[] memory fractionalFees)` | `(int responseCode, addess tokenAddress)`                                  |
| 54c832a5 | `updateTokenInfo(address token, HederaTokenV2 memory tokenInfo)`                                                                                                              | `int responseCode`                                                         |
| bc03816f | `getTokenInfoV2(address token)`                                                                                                                                               | `(int64 responseCode, TokenInfoV2 memory tokenInfo)`                       |
| fb29ac6e | `getNonFungibleTokenInfoV2(address token, int64 serialNumber)`                                                                                                                | `(int64 responseCode, NonFungibleTokenInfoV2 memory nonFungibleTokenInfo)` |
| 3f9dc353 | `getFungibleTokenInfoV2(address token)`                                                                                                                                       | `(int64 responseCode, FungibleTokenInfoV2 memory fungibleTokenInfo)`       |

## Acceptance Tests

### Positive Tests

- Verify `createFungibleToken` creates a fungible token with metadata
- Verify `createFungibleTokenWithCustomFees` creates a fungible token with metadata
- Verify `createNonFungibleToken` creates a non-fungible token with metadata
- Verify `createNonFungibleTokenWithCustomFees` creates a non-fungible token with metadata
- Verify `updateTokenInfo` updates token info with metadata
- Verify `getFungibleTokenInfoV2` returns the correct metadata for a fungible token
- Verify `getTokenInfoV2` returns the correct metadata for a token
- Verify `getNonFungibleTokenInfoV2` returns the correct metadata for a non-fungible token
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
- Verify `getFungibleTokenInfoV2` returns the correct metadata for a fungible token with metadata key
- Verify `getTokenInfoV2` returns the correct metadata for a token with metadata key
- Verify `getNonFungibleTokenInfoV2` returns the correct metadata for a non-fungible token with metadata key
- Verify `updateNftsMetadata` updates metadata for multiple NFTs
- Verify `updateNftsMetadata` updates metadata for single NFT

### Negative Tests

- Verify `updateTokenInfo` fails to update token info with metadata when metadata key is not set
- Verify `updateTokenInfo` fails to update token info with metadata when metadata key is different
- Verify `updateNftsMetadata` fails to update metadata for multiple NFTs when metadata key is not set
- Verify `updateNftsMetadata` fails to update metadata for multiple NFTs when metadata key is different
