// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "./Ownable.sol";

interface IHederaTokenService {
    function associateToken(address account, address token) external returns (int);
    function mintToken(address token, int64 amount, bytes[] calldata metadata) external returns (int);
}

library HederaResponseCodes {
    // Minimal subset of response codes used by this contract.
    int public constant SUCCESS = 22;
    int public constant TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT = 103;
}

/**
 * @title HushSense Token Manager
 * @notice This contract MANAGES a native Hedera HTS token.
 * It is NOT an ERC-20 token itself.
 * It is given the Supply Key of the HTS token so it can mint rewards.
 */
contract HushSenseManager is Ownable {

    /// @notice The address of the HTS token this contract manages
    address public htsTokenId;

    /// @notice Emitted when tokens are minted as rewards
    event RewardMinted(address indexed to, uint256 amount);

    constructor() {
        // Contract is deployed with you as the owner
    }

    /**
     * @notice Links this contract to the HTS token it will manage.
     * This can only be called ONCE by the owner.
     * @param _htsTokenId The address (Token ID) of the HTS token.
     */
    function initialize(address _htsTokenId) external onlyOwner {
        require(htsTokenId == address(0), "Contract already initialized");
        htsTokenId = _htsTokenId;
    }

    /**
     * @notice Allows the owner (your backend) to mint new tokens as rewards.
     * This function calls the native HTS precompile.
     * @param to The recipient's EVM address.
     * @param amount The number of tokens to mint (using 0 decimals, as defined).
     */
    function mintReward(address to, int64 amount) external onlyOwner {
        require(htsTokenId != address(0), "HTS token not initialized");

        IHederaTokenService hts = IHederaTokenService(address(0x0000000000000000000000000000000000000167));

        // 1. Associate the user with the token if they aren't already
        // This is a "best-effort" call and is safe to run even if already associated.
        // This requires the RECIPIENT to have 'automatic token associations' enabled
        int associationResponse = hts.associateToken(to, htsTokenId);
        if (associationResponse != HederaResponseCodes.SUCCESS &&
            associationResponse != HederaResponseCodes.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT) {
            revert("HTS: Token association failed");
        }

        // 2. Mint the tokens
        int response = hts.mintToken(htsTokenId, amount, new bytes[](0));
        if (response != HederaResponseCodes.SUCCESS) {
            revert("HTS: Mint failed");
        }

        emit RewardMinted(to, uint256(int256(amount)));
    }
}