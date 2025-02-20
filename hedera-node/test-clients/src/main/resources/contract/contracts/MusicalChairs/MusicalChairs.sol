// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.6.12;

contract MusicalChairs {

    address dj;
    bool playing;

    uint256 public constant seatCount = 4;
    address[seatCount] public seats;
    uint256 public hotSeat = 0;

    constructor(address _dj) public {
        dj = _dj;
    }

    function startMusic() external {
        require(msg.sender == dj, "Only the DJ can start the music");
        require(!playing, "Music is already playing");
        playing = true;
    }

    function stopMusic() external {
        require(msg.sender == dj, "Only the DJ can stop the music");
        require(playing, "Music is not playing");
        playing = false;

        //TODO reward winners
    }

    function sitDown()  external {
        require(playing, "The Music is not Playing");
        require(dj != msg.sender, "The DJ cannot play");
        require(seats[0] != msg.sender && seats[1] != msg.sender&& seats[2] != msg.sender&& seats[3] != msg.sender, "You are already seated.");

        seats[hotSeat] = msg.sender;
        hotSeat = (hotSeat+1)%seatCount;
    }

    function whoIsOnTheBubble()   external view returns (address hotSeatAddress){
        hotSeatAddress = seats[hotSeat];
    }

}
