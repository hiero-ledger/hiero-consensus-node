
type __ = Obj.t

val fst : ('a1 * 'a2) -> 'a1

val snd : ('a1 * 'a2) -> 'a2



type 'a sig0 = 'a
  (* singleton inductive, whose constructor was exist *)



val sub : int -> int -> int



module type TotalLeBool' =
 sig
  type t

  val leb : t -> t -> bool
 end

val in_dec : ('a1 -> 'a1 -> bool) -> 'a1 -> 'a1 list -> bool

val remove : ('a1 -> 'a1 -> bool) -> 'a1 -> 'a1 list -> 'a1 list

val list_eq_dec : ('a1 -> 'a1 -> bool) -> 'a1 list -> 'a1 list -> bool

val fold_left : ('a1 -> 'a2 -> 'a1) -> 'a2 list -> 'a1 -> 'a1

val list_max : int list -> int

module Sort :
 functor (X:TotalLeBool') ->
 sig
  val merge : X.t list -> X.t list -> X.t list

  val merge_list_to_stack :
    X.t list option list -> X.t list -> X.t list option list

  val merge_stack : X.t list option list -> X.t list

  val iter_merge : X.t list option list -> X.t list -> X.t list

  val sort : X.t list -> X.t list

  val flatten_stack : X.t list option list -> X.t list
 end

val annotate : 'a1 list -> 'a1 list

val list_min_or_0 : int list -> int

val firstMax : 'a1 list -> ('a1 -> 'a1 -> bool) -> 'a1 option

val nodupOrder : ('a1 -> 'a1 -> bool) -> 'a1 list -> 'a1 list

val lastOpt : 'a1 list -> 'a1 option

val two_thirds : int -> int

val sumweight : ('a1 -> int) -> 'a1 list -> int

type sample = unit

type roundInfo = int

val prevInfo : roundInfo -> roundInfo option

val pendingRound : roundInfo -> int

type node = int

val nodes : roundInfo -> node list

val stake : roundInfo -> node -> int

val totalStake : roundInfo -> int

type time = int * int

val lebTime : time -> time -> bool

val timeNatAdd : time -> int -> time

val timeNatSub : time -> int -> time

type event = int

val timeCreated : event -> time

val creator : event -> node

val birthRound : event -> int

val selfParentSigned : event -> event option

val otherParentsSigned : event -> event list

val prevJudges : roundInfo -> event list

val coinInterval : roundInfo -> int

val targetNumRoundsNonAncient : roundInfo -> int

val prevJudgesCopied : roundInfo -> bool

val judgeCon1 : roundInfo -> bool

val prevJudgeCon1 : roundInfo -> bool

val seeNum : roundInfo -> int

val seeDen : roundInfo -> int

val coin : event -> node option

val exist_In_dec : 'a1 list -> ('a1 -> __ -> bool) -> bool

val prevMinJudgeBirthRound : roundInfo -> int

val minNonAncientRound : roundInfo -> int

val prevMinNonAncientRound : roundInfo -> int

val isAncient : roundInfo -> event -> bool

val selfParentNonAncient : roundInfo -> event -> event option

val otherParentNonAncient : roundInfo -> event -> event list

val parentsNonAncient : roundInfo -> event -> event list

val ancestorNonAncientE : roundInfo -> event -> event -> bool

val ancestorsNonAncient : roundInfo -> event -> event list

val maxJudgeRound : roundInfo -> event -> int

val ancestorJudge : roundInfo -> event -> event -> bool

val selfParent : roundInfo -> event -> event option

val otherParent : roundInfo -> event -> event list

val isOrphan : roundInfo -> event -> bool

val parents : roundInfo -> event -> event list

val event_parent_ind_type :
  roundInfo -> (event -> (event -> __ -> 'a1) -> 'a1) -> event -> 'a1

val ancestor_dec : roundInfo -> event -> event -> bool

val strict_ancestor_dec : roundInfo -> event -> event -> bool

val event_eqEO : event option -> event option -> bool

val eqbTime : time -> time -> bool

val ltbTime : time -> time -> bool

val gen : roundInfo -> event -> int

val sum_over_nodes : roundInfo -> (node -> int) -> int

val supermajority : roundInfo -> int -> bool

val voteD : roundInfo -> int

type pick =
| ParentRoundF
| LastSeeF
| SeeThruF
| StronglySeePF
| VotingRoundF
| FirstSelfWitnessSF
| FirstWitnessSF
| StronglySeeS1F
| WitnessF
| FirstVoteF
| StakeAgreesF
| TopVoteF
| VoteF

type result = __

val allFunctions : roundInfo -> pick -> event -> result

val lastSee : roundInfo -> event -> node -> event option

val seeThru : roundInfo -> event -> node -> node -> event option

val parentRound : roundInfo -> event -> int

val stronglySeeP : roundInfo -> event -> node -> event option

val votingRound : roundInfo -> event -> int

val firstSelfWitnessS : roundInfo -> event -> event

val firstWitnessS : roundInfo -> event -> result

val stronglySeeS1 : roundInfo -> event -> result

val witness : roundInfo -> event -> result

val firstVote : roundInfo -> event -> result

val stakeAgrees : roundInfo -> event -> node -> node -> int

val topVote : roundInfo -> event -> node -> event option * bool

val vote : roundInfo -> event -> node -> event option * bool

val judges : roundInfo -> event -> event list

module TOrder :
 sig
  type t = event

  val leb : event -> event -> bool
 end

module TSort :
 sig
  val merge : event list -> event list -> event list

  val merge_list_to_stack :
    event list option list -> event list -> event list option list

  val merge_stack : event list option list -> event list

  val iter_merge : event list option list -> event list -> event list

  val flatten_stack : event list option list -> event list
 end

val select :
  (roundInfo -> event -> int) -> roundInfo -> event list -> int -> time

val weightedMedianWt :
  (roundInfo -> event -> int) -> roundInfo -> event list -> time

val weightedMedian : roundInfo -> event list -> time

val ancestorsNonAncientOptimized :
  roundInfo -> event -> event list -> event list

val ancestorsNonAncientOfList : roundInfo -> event list -> event list

val recievedEvent : roundInfo -> event -> event -> event option

val isReceived : roundInfo -> event -> event list -> bool -> bool

val reachedCon : roundInfo -> event -> event -> bool

val timeCon : roundInfo -> event -> event -> time

val isConsensus : roundInfo -> event -> event -> bool

val reachedConSet : roundInfo -> event -> event list

val reachedConSetExtraction : roundInfo -> event -> event list

val tiebreaker : roundInfo -> event -> event -> event -> bool

val before : roundInfo -> event -> event -> event -> bool

val decider : roundInfo -> event

val prevNumCons : roundInfo -> int

val consensusOrder : roundInfo -> event -> event -> int

val consensusTimestamp : roundInfo -> event -> event -> time

val maxJudgeRoundExtraction : roundInfo -> event -> int

val ancestorJudgeExtraction : roundInfo -> event -> event -> bool

val minNonAncientRoundExtraction : roundInfo -> int

val ancestorNonAncientEExtraction : roundInfo -> event -> event -> bool

val ancestorsNonAncientExtraction : roundInfo -> event -> event list

val genExtraction : roundInfo -> event -> int

val parentRoundExtraction : roundInfo -> event -> int

val lastSeeExtraction : roundInfo -> event -> node -> event option

val seeThruExtraction : roundInfo -> event -> node -> node -> event option

val stronglySeePExtraction : roundInfo -> event -> node -> event option

val votingRoundExtraction : roundInfo -> event -> int

val firstSelfWitnessSExtraction : roundInfo -> event -> event

val firstWitnessSExtraction : roundInfo -> event -> event option

val witnessExtraction : roundInfo -> event -> bool

val stronglySeeS1Extraction : roundInfo -> event -> node -> event option

val firstVoteExtraction : roundInfo -> event -> node -> event option

val stakeAgreesExtraction : roundInfo -> event -> node -> node -> int

val topVoteExtraction : roundInfo -> event -> node -> event option * bool

val voteExtraction : roundInfo -> event -> node -> event option * bool

val recievedEventExtraction : roundInfo -> event -> event -> event option

val prevNumConsExtraction : roundInfo -> int
