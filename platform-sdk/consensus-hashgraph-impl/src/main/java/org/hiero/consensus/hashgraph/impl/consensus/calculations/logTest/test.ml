let usage_msg = "test [-opt <default=2>] [-exit <default=false>] <inputFile>"
let exitCheck = ref false
let optLevel = ref 2
let input_file = ref ""
let anon_fun filename = input_file := filename
let speclist =
  [
    ("-opt", Arg.Set_int optLevel, "Set optimization level, from 0 (less optimation) to 2 (more optimization, default).\n\tLower optimization is closer to the Rocq definition.\n\t0: Termination checking embedded into code\n\t1: Closest to documentation definition\n\t2: Memoization added for several functions");
    ("-exit", Arg.Set exitCheck, "If true, exists testing on first failure. Set to false by default");
  ]

let () = Arg.parse speclist anon_fun usage_msg

module type T = sig
  val prevJudgeCon1 : int -> bool
  val prevJudges : int -> int list
  val prevJudgesCopied : int -> bool
  val prevMinNonAncientRound : int -> int
  val prevNumCons : int -> int
  val prevMinJudgeBirthRound : int -> int

  val creator : int -> int

  val nodes : int -> int list
  val totalStake : int -> int
  val minNonAncientRound : int -> int
  val voteD : int -> int

  val selfParent : int -> int -> int option
  val maxJudgeRound : int -> int -> int
  val parents : int -> int -> int list
  val ancestorJudge : int -> int -> int -> bool
  val gen : int -> int -> int
  val lastSee : int -> int -> int -> int option
  val stronglySeeP : int -> int -> int -> int option
  val votingRound : int -> int -> int
  val firstSelfWitnessS : int -> int -> int
  val firstWitnessS : int -> int -> int option
  val stronglySeeS1 : int -> int -> int -> int option
  val vote : int -> int -> int -> int option * bool
  
  val timeCon : int -> int -> int -> int * int
  val before : int -> int -> int -> int -> bool
  val isConsensus : int -> int -> int -> bool
  val consensusOrder : int -> int -> int -> int
  val consensusTimestamp : int -> int -> int -> int * int
  val reachedConSet : int -> int -> int list

  val createRoundInfo :
    int ->
    coinInterval:int ->
    targetNumRoundsNonAncient:int ->
    judgeCon1:bool ->
    seeNum: int ->
    seeDen: int ->
    unit
  val createNode : int -> int -> stake:int -> unit
  val createEvent :
    int ->
    timeCreated:(int*int) ->
    creator:int ->
    birthRound:int ->
    selfParentSigned:(int option) ->
    otherParentsSigned:(int list) ->
    coin:(int option) ->
    unit

  val clear : (unit -> unit) ref

  val createDecider : int -> int -> unit
  val decider : int -> int
end

module M =
  (
  val match !optLevel with
  | 0 -> (module ExtractionNoOptimization : T)
  | 1 -> (module Extraction : T)
  | _ -> (module Memoized : T)
  )

open M

let latestRoundId = ref 0

let countList l =
  match l with
  | x :: l ->
    (List.take x l, List.drop x l)
  | _ -> ([],[])

let rec countListPairHelper l n =
  if n == 0 then ([],l) else
  match l with
  | a :: b :: l ->
    let (x,y) = countListPairHelper l (n-1) in
    ((a,b) :: x,y)
  | _ -> ([],[])

let countListPair l =
  match l with
  | x :: l ->
    countListPairHelper l x
  | _ -> ([],[])

let nullNat x =
  match x with
  | Some x -> x
  | None -> -1


let everythingPassed = ref true  
let exitFail () =
  everythingPassed := false;
  if !exitCheck then exit 0 else ()
let parseError () =
  print_string "There was a parse error\n";
  exit 0

let print_int_array l =
  let first = ref true in
  print_string "[";
  List.iter (fun x -> if !first then () else print_string " "; first:=false; print_int x) l;
  print_string "]" 

let print_intPair_array l =
  let first = ref true in
  print_string "[";
  List.iter (fun (x,y) -> if !first then () else print_string ", "; first:=false;
    print_string "(";
    print_int x;
    print_string ",";
    print_int y;
    print_string ")";
  ) l;
  print_string "]" 

let checkInt a b =
  if a == b then (print_string "pass: "; print_int b; print_string "\n") else (
    print_string "fail: ";
    print_int b;
    print_string " should be ";
    print_int a;
    print_string "\n";
    exitFail ()
  )

let checkIntPair (a1,a2) (b1,b2) =
  if a1 == b1 && a2 == b2 then (print_string "pass: "; print_int b1; print_string " "; print_int b2; print_string "\n") else (
    print_string "fail: ";
    print_int b1; print_string " "; print_int b2;
    print_string " should be ";
    print_int a1; print_string " "; print_int a2;
    print_string "\n";
    exitFail ()
  )

let checkBool a b =
  let b' = (b != 0) in
  if a == b' then (print_string "pass: "; print_int b; print_string "\n") else (
    print_string "fail: ";
    print_int b;
    print_string " should be ";
    print_int (if a then 1 else 0);
    print_string "\n";
    exitFail ()
  )

let checkListInt a b =
  if List.length a != List.length b then (
    print_string "fail: ";
    print_int_array b;
    print_string " should be ";
    print_int_array a;
    print_string "\n";
    exitFail ()
  ) else
  let pairList = List.combine a b in
  match List.find_index (fun (x,y) -> x != y) pairList with
  | None -> (print_string "pass: "; print_int_array b; print_string "\n")
  | Some i ->
    print_string "fail: ";
    print_int_array b;
    print_string " should be ";
    print_int_array a;
    print_string "\n";
    exitFail ()

let checkListBool a b =
  let b' = List.map (fun b -> b != 0) b in
  if List.length a != List.length b' then (
    print_string "fail: ";
    print_int_array b;
    print_string " should be ";
    print_int_array (List.map (fun x -> if x then 1 else 0) a);
    print_string "\n";
    exitFail ()
  ) else
  let pairList = List.combine a b' in
  match List.find_index (fun (x,y) -> x != y) pairList with
  | None -> (print_string "pass: "; print_int_array b; print_string "\n")
  | Some i ->
    print_string "fail: ";
    print_int_array b;
    print_string " should be ";
    print_int_array (List.map (fun x -> if x then 1 else 0) a);
    print_string "\n";
    exitFail ()

let checkListPair a b =
  if List.length a != List.length b then (
    print_string "fail: ";
    print_intPair_array b;
    print_string " should be ";
    print_intPair_array a;
    print_string "\n";
    exitFail ()
  ) else
  let pairList = List.combine a b in
  match List.find_index (fun ((x1,x2),(y1,y2)) -> x1 != y1 || x2 != y2) pairList with
  | None -> (print_string "pass: "; print_intPair_array b; print_string "\n")
  | Some i ->
    print_string "fail: ";
    print_intPair_array b;
    print_string " should be ";
    print_intPair_array a;
    print_string "\n";
    exitFail ()

let processNewHashgraph l =
  (!clear) ();
  print_string "NewHashgraph\n";
  match l with
  | hashgraphIdIn :: softwareVersionIn :: randomSeedIn :: yearIn :: monthIn :: dayIn :: hourIn :: minIn :: secIn :: nanoIn :: [] ->
    print_string "\thashgraphId: ";
    print_int hashgraphIdIn;
    print_string "\n";
    print_string "\tsoftwareVersion: ";
    print_int softwareVersionIn;
    print_string "\n";
    print_string "\trandomSeed: ";
    print_int randomSeedIn;
    print_string "\n";
    Printf.printf "\ttime: %d-%d-%d %d:%d:%d:%d\n" yearIn monthIn dayIn hourIn minIn secIn nanoIn
  | _ -> parseError()


let processRoundInfoPrev l =
  match l with
  | pendingRoundIn :: prevJudgeCon1In :: l ->
    let (prevJudgesIn, l) = countList l in
    (match l with
    | prevJudgesCopiedIn :: prevMinNonAncientRoundIn :: prevNumConsIn :: prevMinJudgeBirthRoundIn :: [] ->

      print_string "RoundInfoPrev\n";
      print_string "\tpendingRound: ";
      print_int pendingRoundIn;
      print_string "\n";
      print_string "\tprevJudgeCon1 ";
      checkBool (prevJudgeCon1 pendingRoundIn) prevJudgeCon1In;
      print_string "\tprevJudges ";
      checkListInt (prevJudges pendingRoundIn) prevJudgesIn;
      print_string "\tprevJudgesCopied ";
      checkBool (prevJudgesCopied pendingRoundIn) prevJudgesCopiedIn;
      print_string "\tprevMinNonAncientRound ";
      checkInt (prevMinNonAncientRound pendingRoundIn) prevMinNonAncientRoundIn;
      print_string "\tprevNumCons ";
      checkInt (prevNumCons pendingRoundIn) prevNumConsIn;
      print_string "\tprevMinJudgeBirthRound ";
      checkInt (prevMinJudgeBirthRound pendingRoundIn) prevMinJudgeBirthRoundIn;
      ()

    | _ -> parseError() 
    )
  | _ -> parseError()

let processRoundInfo l =
  match l with
  | roundId :: l ->
    let (nodes, l) = countList l in
    let (stakes, l) = countList l in
    (match l with
    | seeNum :: seeDen :: judgeCon1 :: coinInterval :: targetNumRoundsNonAncient :: numRoundsAddressBook :: [] ->
      createRoundInfo roundId
      ~coinInterval:coinInterval
      ~targetNumRoundsNonAncient:targetNumRoundsNonAncient
      ~judgeCon1:(judgeCon1 != 0)
      ~seeNum:seeNum
      ~seeDen:seeDen;
      List.iter (fun (nodeId,stake) -> createNode roundId nodeId ~stake:stake) (List.combine nodes stakes);
      print_string "RoundInfo\n";
      print_string "\tpendingRound: ";
      print_int roundId;
      print_string "\n";
      print_string "\tnodes: ";
      print_int_array nodes;
      print_string "\n";
      print_string "\tstakes: ";
      print_int_array stakes;
      print_string "\n";
      print_string "\tseeNum: ";
      print_int seeNum;
      print_string "\n";
      print_string "\tseeDen: ";
      print_int seeDen;
      print_string "\n";
      print_string "\tjudgeCon1: ";
      print_int judgeCon1;
      print_string "\n";
      print_string "\tcoinInterval: ";
      print_int coinInterval;
      print_string "\n";
      print_string "\ttargetNumRoundsNonAncient: ";
      print_int targetNumRoundsNonAncient;
      print_string "\n";
      print_string "\tnumRoundsAddressBook: ";
      print_int numRoundsAddressBook;
      print_string "\n";
      latestRoundId := roundId
    | _ -> parseError()
    )
  | _ -> parseError()

let splitParents creatorIn parents =
  match parents with
  | sp :: l ->
    if creator sp == creatorIn then (Some sp, l) else (None, sp :: l)
  | _ -> (None, [])

let processEventSigned l =
  match l with
  | eventId :: timeCreated1 :: timeCreated2 :: creator :: birthRound :: coin :: parents ->
    let (parents,l) = (countList parents) in
    if List.length l != 0 then parseError() else
    let (selfParent,otherParents) = splitParents creator parents in
    createEvent eventId
    ~timeCreated:(timeCreated1,timeCreated2)
    ~creator:creator
    ~birthRound:birthRound
    ~selfParentSigned:selfParent
    ~otherParentsSigned:otherParents
    ~coin:(List.find_opt (fun x -> x == coin) (nodes birthRound));
    print_string "EventSigned\n";
    print_string "\teventId: ";
    print_int eventId;
    print_string "\n";
    print_string "\ttimeCreated: ";
    print_int timeCreated1;
    print_string " ";
    print_int timeCreated2;
    print_string "\n";
    print_string "\tcreator: ";
    print_int creator;
    print_string "\n";
    print_string "\tbirthRound: ";
    print_int birthRound;
    print_string "\n";
    print_string "\tcoin: ";
    print_int coin;
    print_string "\n";
    print_string "\tparents: ";
    print_int_array parents;
    print_string "\n";
    ()
  | _ -> parseError()

let lastEvent = ref 0

let processEventInfo l =
  match l with
  | eventId :: creatorIndexIn :: selfParentIn :: maxJudgeRoundIn :: l ->
    let (parentsIn, l) = countList l in
    (match l with
    | totalStakeIn :: minNonAncientRoundIn :: voteDIn :: l ->
      let (ancestorJudgeIn, l) = countList l in
      (match l with
      | genIn :: l ->
        let (lastSeeIn, l) = countList l in
        let (stronglySeePIn, l) = countList l in
        (match l with
        | votingRoundIn :: firstSelfWitnessSIn :: firstWitnessSIn :: l ->
          let (stronglySeeS1In, l) = countList l in
          let (voteEIn, l) = countList l in
          let (voteBIn, l) = countList l in
          if List.length l != 0 then parseError() else
          let () = (lastEvent := eventId) in
          print_string "EventInfo\n";
          print_string "\teventId: ";
          print_int eventId;
          print_string "\n";
          print_string "\tselfParent ";
          checkInt (nullNat (selfParent (!latestRoundId) eventId)) selfParentIn;
          print_string "\tmaxJudgeRoundIn ";
          checkInt (maxJudgeRound (!latestRoundId) eventId) maxJudgeRoundIn;
          print_string "\tparents ";
          checkListInt (parents (!latestRoundId) eventId) parentsIn;
          print_string "\ttotalStake ";
          checkInt (totalStake (!latestRoundId)) totalStakeIn;
          print_string "\tminNonAncientRound ";
          checkInt (minNonAncientRound (!latestRoundId)) minNonAncientRoundIn;
          print_string "\tvoteD ";
          checkInt (voteD (!latestRoundId)) voteDIn;
          print_string "\tancestorJudge ";
          checkListBool (List.map (fun j -> ancestorJudge (!latestRoundId) eventId j) (prevJudges (!latestRoundId))) ancestorJudgeIn;
          print_string "\tgen ";
          checkInt (gen (!latestRoundId) eventId) genIn;
          print_string "\tlastSee ";
          checkListInt (List.map (fun m -> nullNat (lastSee (!latestRoundId) eventId m)) (nodes (!latestRoundId))) lastSeeIn;
          print_string "\tstronglySeeP ";
          checkListInt (List.map (fun m -> nullNat (stronglySeeP (!latestRoundId) eventId m)) (nodes (!latestRoundId))) stronglySeePIn;
          print_string "\tvotingRound ";
          checkInt (votingRound (!latestRoundId) eventId) votingRoundIn;
          print_string "\tfirstSelfWitnessS ";
          checkInt (firstSelfWitnessS (!latestRoundId) eventId) firstSelfWitnessSIn;
          print_string "\tfirstWitnessS ";
          checkInt (nullNat (firstWitnessS (!latestRoundId) eventId)) firstWitnessSIn;
          print_string "\tstronglySeeS1 ";
          checkListInt (List.map (fun m -> nullNat (stronglySeeS1 (!latestRoundId) eventId m)) (nodes (!latestRoundId))) stronglySeeS1In;
          print_string "\tvoteE ";
          checkListInt (List.map (fun m -> let (a,b) = (vote (!latestRoundId) eventId m) in nullNat a) (nodes (!latestRoundId))) voteEIn;
          print_string "\tvoteB ";
          checkListBool (List.map (fun m -> let (a,b) = (vote (!latestRoundId) eventId m) in b) (nodes (!latestRoundId))) voteBIn;
          ()
        | _ -> parseError()
        )
      | _ -> parseError()
      )
    | _ -> parseError()
    )
  | _ -> parseError()

let processUpdateResults l =
  match l with
  | pendingRoundIn :: l ->
    createDecider pendingRoundIn (!lastEvent);
    let (searchOrderIn, l) = countList l in
    let (consensusEventsIn, l) = countList l in
    let (timeConIn, l) = countListPair l in
    let (genIn, l) = countList l in
    (match l with
    | roundTimestamp1In :: roundTimestamp2In :: voteDIn :: usedCoinIn :: [] ->
      let searchOrder = (reachedConSet pendingRoundIn (!lastEvent)) in
      let consensusEvents =  (List.stable_sort (fun a b -> if a == b then 0 else (if (before pendingRoundIn (!lastEvent) a b) then -1 else 1)) searchOrder) in
      print_string "UpdateResults\n";
      print_string "\tpendingRound: ";
      print_int pendingRoundIn;
      print_string "\n";
      print_string "\tsearchOrder ";
      checkListInt searchOrder searchOrderIn;
      print_string "\tconsensusEvents ";
      checkListInt consensusEvents consensusEventsIn;
      print_string "\ttimeCon ";
      checkListPair (List.map (timeCon pendingRoundIn (!lastEvent)) consensusEvents) timeConIn;
      print_string "\tgen ";
      checkListInt (List.map (gen pendingRoundIn) consensusEvents) genIn;
      print_string "\troundTimestamp: ";
      print_int roundTimestamp1In;
      print_string " ";
      print_int roundTimestamp2In;
      print_string "\n";
      print_string "\tvoteD: ";
      print_int voteDIn;
      print_string "\n";
      print_string "\tusedCoin: ";
      print_int usedCoinIn;
      print_string "\n";
      ()
    | _ -> parseError())
  | _ -> parseError()

let processEventInfoConsensus l =
  match l with
  | eventId :: isConsensusIn :: consensusOrderIn :: consensusTimestamp1In :: consensusTimestamp2In :: [] ->
    let deciderId = decider (!latestRoundId) in
    print_string "EventInfoConsensus\n";
    print_string "\teventId: ";
    print_int eventId;
    print_string "\n";
    print_string "\tisConsensus ";
    checkBool (isConsensus (!latestRoundId) deciderId eventId) isConsensusIn;
    print_string "\tconsensusOrder ";
    checkInt (consensusOrder (!latestRoundId) deciderId eventId) consensusOrderIn;
    print_string "\tconsensusTimestamp ";
    checkIntPair (consensusTimestamp (!latestRoundId) deciderId eventId) (consensusTimestamp1In,consensusTimestamp2In);
    print_string "\tgen (expected): ";
    print_int (gen (!latestRoundId) eventId);
    print_string "\n";
    print_string "\ttimeCon (expected): ";
    let (a,b) = (timeCon (!latestRoundId) deciderId eventId) in
    print_int a;
    print_string " ";
    print_int b;
    print_string "\n";
    ()
  | _ -> parseError()

let process_line index line =
  let data = List.map int_of_string (String.split_on_char ',' line) in
  print_int (1+index);
  print_string " ";
  match data with
  | 0 :: l -> processNewHashgraph l
  | 1 :: l -> processRoundInfoPrev l
  | 2 :: l -> processRoundInfo l
  | 3 :: l -> processEventSigned l
  | 4 :: l -> processEventInfo l
  | 5 :: l -> processUpdateResults l
  | 6 :: l -> processEventInfoConsensus l
  | _ -> parseError()

let () = if String.length (!input_file) == 0 then (print_string "Error: No input file\n"; exit 0) else ()
let file = !input_file
let ic = In_channel.open_text file
let lines = In_channel.input_lines ic
let () = List.iteri process_line lines

let () = if !everythingPassed then print_string "\nEverything Passed!\n" else print_string "\n A mismatch occurred\n"
