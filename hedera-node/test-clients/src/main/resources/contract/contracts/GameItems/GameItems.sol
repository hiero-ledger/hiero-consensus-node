// contracts/GameItems.sol
// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

import "@openzeppelin/contracts/token/ERC1155/ERC1155.sol";

contract GameItems is ERC1155 {
    uint256 public constant GOLD = 0;
    uint256 public constant SILVER = 1;

    constructor() ERC1155("https://game.example/api/item/{id}.json") {

    }

    function mintToken(uint256 tokenType, uint256 amount, address recipient) public {
        _mint(recipient, tokenType, amount, "");
    }

}
