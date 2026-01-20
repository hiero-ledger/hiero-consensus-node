// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./IHieroHook.sol";
import "../../contracts_16c/HederaTokenService/HederaTokenService.sol";
import "./IHieroAccountAllowanceHook.sol";

/// @title AutoAssociateHook
/// @notice If (and only if) msg.sender is allowlisted, this hook will
///         try to associate the hook owner (context.owner) to any HTS
///         tokens that the owner sends or receives in the proposed transfers.
///         Then it returns true. Otherwise returns false.
/// @dev Must be executed as a hook at the special 0x16d address (HIP-1195).
contract AutoAssociateHook is IHieroAccountAllowanceHook {
    /// HIP-1195 special hook address (0x...016d padded to 20 bytes)
    address constant HOOK_ADDR = address(uint160(0x16d));

    /// HTS precompile (system contract) address
    IHederaTokenService constant HTS = IHederaTokenService(address(uint160(0x167)));

    /// @dev Allowlist of senders (transaction payers) permitted to use this hook.
    /// IMPORTANT: When called as a hook, storage is the lambda's storage;
    ///            update these slots via LambdaSStore or your admin flow.
    mapping(address => bool) public isAllowedSender;

    /// @inheritdoc IHieroAccountAllowanceHook
    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external override payable returns (bool) {
        // Ensure this bytecode is running as an EVM hook
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");

        // Only allow if the payer (msg.sender in hook execution) is on the allowlist
        if (!isAllowedSender[msg.sender]) {
            return false;
        }

        // Target account for auto-associations is the hook owner
        address owner = context.owner;

        // 1) Direct transfers
        _associateOwnerForTransfers(owner, proposedTransfers.direct.tokens);

        // 2) Assessed custom-fee transfers
        _associateOwnerForTransfers(owner, proposedTransfers.customFee.tokens);

        // If we reached here, sender is allowlisted and we submitted associations
        return true;
    }

    /// @dev For each TokenTransferList, if the owner appears as a sender or receiver
    ///      (fungible or NFT), submit an association for that token.
    function _associateOwnerForTransfers(
        address owner,
        IHieroAccountAllowanceHook.TokenTransferList[] memory lists
    ) internal {
        uint256 n = lists.length;
        for (uint256 i = 0; i < n; i++) {
            IHieroAccountAllowanceHook.TokenTransferList memory ttl = lists[i];

            // Check fungible transfers where the owner appears
            bool ownerInvolved = false;
            IHederaTokenService.AccountAmount[] memory fts = ttl.transfers;
            for (uint256 j = 0; j < fts.length; j++) {
                if (fts[j].accountID == owner && fts[j].amount != 0) {
                    ownerInvolved = true;
                    break;
                }
            }

            // If not found in fungibles, check NFT movements
            if (!ownerInvolved) {
                IHederaTokenService.NftTransfer[] memory nfts = ttl.nftTransfers;
                for (uint256 j = 0; j < nfts.length; j++) {
                    if (
                        nfts[j].senderAccountID == owner ||
                        nfts[j].receiverAccountID == owner
                    ) {
                        ownerInvolved = true;
                        break;
                    }
                }
            }

            // Submit association for the owner if they are involved with this token
            if (ownerInvolved) {
                // We intentionally ignore the response code; if already associated,
                // HTS returns TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT.
                // Association will succeed when permitted by network rules.
                // (Precompile address: 0x167)
                HTS.associateToken(owner, ttl.token);
            }
        }
    }
}