// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

interface IHederaAccountService {

    /// Returns the amount of hbar that the spender has been authorized to spend on behalf of the owner.
    /// @param owner The account that has authorized the spender.
    /// @param spender The account that has been authorized by the owner.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    /// @return amount The amount of hbar that the spender has been authorized to spend on behalf of the owner.
    function hbarAllowance(address owner, address spender)
    external
    returns (int64 responseCode, int256 amount);

    /// Allows spender to withdraw hbars from the owner account multiple times, up to the value amount. If this
    /// function is called again it overwrites the current allowance with the new amount.
    /// @param owner The owner of the hbars.
    /// @param spender the account address authorized to spend.
    /// @param amount the amount of tokens authorized to spend.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    function hbarApprove(
        address owner,
        address spender,
        int256 amount
    ) external returns (int64 responseCode);

    /// Returns the EVM address alias for the given Hedera account.
    /// @param accountNumAlias The Hedera account to get the EVM address alias for.
    /// @return responseCode The response code for the status of the request. SUCCESS is 22.
    /// @return evmAddressAlias The EVM address alias for the given Hedera account.
    function getEvmAddressAlias(address accountNumAlias) external
        returns (int64 responseCode, address evmAddressAlias);

    /// Returns the Hedera Account ID (as account num alias) for the given EVM address alias
    /// @param evmAddressAlias The EVM address alias to get the Hedera account for.
    /// @return responseCode The response code for the status of the request.  SUCCESS is 22.
    /// @return accountNumAlias The Hedera account's num for the given EVM address alias.
    function getHederaAccountNumAlias(address evmAddressAlias) external
        returns (int64 responseCode, address accountNumAlias);

    /// Returns true iff a Hedera account num alias or EVM address alias.
    /// @param addr Some 20-byte address.
    /// @return response true iff addr is a Hedera account num alias or an EVM address alias (and false otherwise).
    function isValidAlias(address addr) external returns (bool response);

    /// Determines if the signature is valid for the given message hash and account.
    /// It is assumed that the signature is composed of a single EDCSA or ED25519 key.
    /// @param account The account to check the signature against.
    /// @param messageHash The hash of the message to check the signature against.
    /// @param signature The signature to check.
    /// @return response True if the signature is valid, false otherwise.
    function isAuthorizedRaw(
        address account,
        bytes memory messageHash,
        bytes memory signature) external returns (bool response);

    /// Determines if the signature is valid for the given message  and account.
    /// It is assumed that the signature is composed of a possibly complex cryptographic key.
    /// @param account The account to check the signature against.
    /// @param message The message to check the signature against.
    /// @param signature The signature to check encoded as bytes.
    /// @return responseCode The response code for the status of the request.  SUCCESS is 22.
    /// @return response True if the signature is valid, false otherwise.
    function isAuthorized(
        address account,
        bytes memory message,
        bytes memory signature) external returns (int64 responseCode, bool response);
}
