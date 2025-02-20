// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.0;

contract Dynamite {
    address payable target;
    uint32 stickId;

    event Boom(uint32 id);

    constructor(address out, uint32 id) public {
        target = address(uint160(out));
        stickId = id;
    }

    function explode() public {
        emit Boom(stickId); 
        selfdestruct(target);
    }  
}

contract Fuse {
    Dynamite[3] explosives;

    constructor() public {
        explosives[0] = new Dynamite(address(this), uint32(0));
        explosives[1] = new Dynamite(address(this), uint32(1));
        explosives[2] = new Dynamite(address(this), uint32(2));
    }

    function light() public {
        explosives[0].explode();
        explosives[1].explode();
        explosives[2].explode();
    } 
}

