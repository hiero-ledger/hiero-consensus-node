# Format of a log test .CSV file
The file shows the results of running the hashgraph consensus algorithm on a set of hashgraphs, including showing the results of all the internal memoized variables after each update of each event.

The file consists of multiple lines, each of which is a sequence of 'int64' numbers (Java 'long'), separated by commas. Each line ends with a number (not a comma), followed by a newline. The last line ends with a newline and is followed by an empty line.

The first number in each line tells its type:

- 0 = `RoundInfo`
- 1 = `RoundInfoPrev`
- 2 = `EventSigned` (immutable parameters in the signed event)
- 3 = `EventInfo` (memoized parameters recalculated when the event updates)
- 4 = `UpdateResults` (the consensus events and other information returned when `update()` reaches consensus)

If any field is an array, it is represented by its length followed by all elements in order. For stake, the elements are positive `int64` numbers. For an `EventInfo[]`, each element is the `eventID` of that `EventInfo`. The `eventID` for a `null` is -1. Every `boolean` is `0` for `false` and `1` for `true`. For an `Instant`, there are two `int64` numbers: the seconds since the start of the epoch, then the number of nanoseconds since the start of the second. Both are -1 if it is null (hasn't been assigned yet). The following gives all the fields for each type, in order:

```
  RoundInfoPrev (type 0)
    int64 pendingRound
    boolean prevJudgeCon1
    EventInfo[] prevJudges
    boolean prevJudgesCopied
    int64 prevMinNonAncientRound
    int64 prevNumCons
    int64 prevMinJudgeBirthRound

  RoundInfo (type 1)
    int64 pendingRound
    int64[] nodes // each of these is a nodeID, not an index
    int64[] stake
    int32 seeNum
    int32 seeDen
    boolean judgeCon1
    int32 coinInterval
    int32 targetNumRoundsNonAncient
    int32 numRoundsAddressBook

  EventSigned (type 2)
    int64 eventID
    Instant timeCreated
    int32 creator // this is an index into RoundInfo.nodes[], not a nodeID
    int64 birthRound
    int32 coin // this is a uniform random int32, not limited to [0,numNodes]
    EventInfo[] parentsSigned
    
  EventInfo (type 3)
    int64 eventID
    EventInfo selfParent
    int64 maxJudgeRound
    EventInfo[] parents
    int64 totalStake
    long minNonAncientRound
    int32 voteD // either 1 or 2
    boolean[] ancestorJudge
    long gen
    EventInfo[] lastSee
    EventInfo[] stronglySeeP
    long votingRound
    EventInfo firstSelfWitnessS
    EventInfo firstWitnessS
    EventInfo[] stronglySeeS1
    EventInfo[] voteE (EventInfo part of the vote() pair)
    boolean[] voteB (boolean part of the vote() pair)
    boolean isConsensus
    long consensusOrder
    Instant consensusTimestamp
	
  UpdateResults (type 4)
    EventInfo[] consensusEvents
    Instant roundTimestamp
    int32 voteD // either 1 or 2   
```

Compared to the paper, this format skips `roundInfoPrev` in `UpdateResults` (because it's a separate line), and skips `payload`, `parentBirthRounds`, `parentCreators`, and `signature` in `EventSigned` (because they don't affect consensus). It also adds fields for `EventID` and `PendingRound` to help identify the objects.

Each round starts by giving `RoundInfoPrev` then `RoundInfo`. If they have a `pendingRound == 1`, then it is starting over with a new hashgraph, and all previous events in the file should be ignored. 

It then gives the `EventInfo` for many events. Whenever an event appears for the first time for this hashgraph, it gives its `EventSigned` just before its `EventInfo`.

If an event reaches consensus (so the `update()` method returned a non-null result), then the next line will be the `UpdateResults` returned by that method. This is then followed by the new `RoundInfo` and `RoundInfoPrev` where the `pendingRound` is incremented by 1, followed by many events recalculated with that new pending round.

All events in every hashgraph will be valid events. So when an event is added to the hashgraph, its `parentsSigned` list has at most one parent for each creator, and lists the self-parent first (if there is one). Its `timeCreated` is greater than its self-parent. Its `birthRound` is greater than or equal to all its parents. Its `coin` will be a uniform random `int32` (rather than limited to the range 0 to n, for n nodes in the birth round). Nodes will always have nonnegative stake, with at least one node having positive stake.