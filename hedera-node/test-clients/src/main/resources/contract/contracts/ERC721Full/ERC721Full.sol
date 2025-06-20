// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.16;

import "./ERC721.sol";

/**
 * @title Full ERC721 Token
 * This implementation includes all the required and some optional functionality of the ERC721 standard
 * Moreover, it includes approve all functionality using operator terminology
 * @dev see https://eips.ethereum.org/EIPS/eip-721
 */
contract ERC721Full is ERC721 {
    constructor () public {
        super._mint(msg.sender, 2);
        super._mint(msg.sender, 3);
        super._mint(msg.sender, 5);
        super._mint(msg.sender, 8);
        super._mint(msg.sender, 13);
    }
}

