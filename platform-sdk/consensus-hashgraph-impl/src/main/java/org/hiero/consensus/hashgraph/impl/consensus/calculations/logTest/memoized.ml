let clear = ref (fun () -> ())

let lastSeeMemo = Hashtbl.create 100
let () = clear := (let stored = !clear in (fun () -> Hashtbl.clear lastSeeMemo; stored()))

let stronglySeePMemo = Hashtbl.create 100
let () = clear := (let stored = !clear in (fun () -> Hashtbl.clear stronglySeePMemo; stored()))

let votingRoundMemo = Hashtbl.create 100
let () = clear := (let stored = !clear in (fun () -> Hashtbl.clear votingRoundMemo; stored()))

let firstSelfWitnessSMemo = Hashtbl.create 100
let () = clear := (let stored = !clear in (fun () -> Hashtbl.clear firstSelfWitnessSMemo; stored()))

let voteMemo = Hashtbl.create 100
let () = clear := (let stored = !clear in (fun () -> Hashtbl.clear voteMemo; stored()))

let curry1 f x1 = f x1

let curry2 f x1 x2 = f (x1, x2)

let curry3 f x1 x2 x3 = f (x1, x2, x3)

let curry4 f x1 x2 x3 x4 = f (x1, x2, x3, x4)

let curry5 f x1 x2 x3 x4 x5 = f (x1, x2, x3, x4, x5)

let curry6 f x1 x2 x3 x4 x5 x6 = f (x1, x2, x3, x4, x5, x6)

let memo_rec f =
  let h = Hashtbl.create 100 in
  let () = clear := (let stored = !clear in (fun () -> Hashtbl.clear h; stored())) in
  let rec g x =
    try Hashtbl.find h x
    with Not_found ->
      let y = f g x in
      Hashtbl.replace h x y;
      y
  in
  g


type __ = Obj.t
let __ = let rec f _ = Obj.repr f in Obj.repr f

(** val fst : ('a1 * 'a2) -> 'a1 **)

let fst = function
| (x, _) -> x

(** val snd : ('a1 * 'a2) -> 'a2 **)

let snd = function
| (_, y) -> y



type 'a sig0 = 'a
  (* singleton inductive, whose constructor was exist *)



(** val sub : int -> int -> int **)

let rec sub = fun n m -> Stdlib.max 0 (n-m)



module type TotalLeBool' =
 sig
  type t

  val leb : t -> t -> bool
 end

(** val in_dec : ('a1 -> 'a1 -> bool) -> 'a1 -> 'a1 list -> bool **)

let rec in_dec h a = function
| [] -> false
| a0 :: l0 -> let s = h a0 a in if s then true else in_dec h a l0

(** val remove : ('a1 -> 'a1 -> bool) -> 'a1 -> 'a1 list -> 'a1 list **)

let rec remove eq_dec x = function
| [] -> []
| y :: tl ->
  if eq_dec x y then remove eq_dec x tl else y :: (remove eq_dec x tl)

(** val list_eq_dec : ('a1 -> 'a1 -> bool) -> 'a1 list -> 'a1 list -> bool **)

let rec list_eq_dec eq_dec l l' =
  match l with
  | [] -> (match l' with
           | [] -> true
           | _ :: _ -> false)
  | a :: l0 ->
    (match l' with
     | [] -> false
     | a0 :: l1 -> if eq_dec a a0 then list_eq_dec eq_dec l0 l1 else false)

(** val fold_left : ('a1 -> 'a2 -> 'a1) -> 'a2 list -> 'a1 -> 'a1 **)

let rec fold_left f l a0 =
  match l with
  | [] -> a0
  | b :: l0 -> fold_left f l0 (f a0 b)

(** val list_max : int list -> int **)

let list_max l =
  (fun f init l -> List.fold_right f l init) Stdlib.max 0 l

module Sort =
 functor (X:TotalLeBool') ->
 struct
  (** val merge : X.t list -> X.t list -> X.t list **)

  let rec merge l1 l2 =
    let rec merge_aux l3 =
      match l1 with
      | [] -> l3
      | a1 :: l1' ->
        (match l3 with
         | [] -> l1
         | a2 :: l2' ->
           if X.leb a1 a2 then a1 :: (merge l1' l3) else a2 :: (merge_aux l2'))
    in merge_aux l2

  (** val merge_list_to_stack :
      X.t list option list -> X.t list -> X.t list option list **)

  let rec merge_list_to_stack stack l =
    match stack with
    | [] -> (Some l) :: []
    | y :: stack' ->
      (match y with
       | Some l' -> None :: (merge_list_to_stack stack' (merge l' l))
       | None -> (Some l) :: stack')

  (** val merge_stack : X.t list option list -> X.t list **)

  let rec merge_stack = function
  | [] -> []
  | y :: stack' ->
    (match y with
     | Some l -> merge l (merge_stack stack')
     | None -> merge_stack stack')

  (** val iter_merge : X.t list option list -> X.t list -> X.t list **)

  let rec iter_merge stack = function
  | [] -> merge_stack stack
  | a :: l' -> iter_merge (merge_list_to_stack stack (a :: [])) l'

  (** val sort : X.t list -> X.t list **)

  let sort =
    iter_merge []

  (** val flatten_stack : X.t list option list -> X.t list **)

  let rec flatten_stack = function
  | [] -> []
  | o :: stack' ->
    (match o with
     | Some l -> List.append l (flatten_stack stack')
     | None -> flatten_stack stack')
 end

(** val annotate : 'a1 list -> 'a1 list **)

let rec annotate = function
| [] -> []
| x :: xs -> x :: (List.map (fun x0 -> x0) (annotate xs))

(** val list_min_or_0 : int list -> int **)

let list_min_or_0 l =
  (fun f init l -> List.fold_right f l init) Stdlib.min (list_max l) l

(** val firstMax : 'a1 list -> ('a1 -> 'a1 -> bool) -> 'a1 option **)

let rec firstMax l leq =
  match l with
  | [] -> None
  | y :: l0 ->
    (match firstMax l0 leq with
     | Some y0 -> if leq y0 y then Some y else Some y0
     | None -> Some y)

(** val nodupOrder : ('a1 -> 'a1 -> bool) -> 'a1 list -> 'a1 list **)

let rec nodupOrder eq_dec = function
| [] -> []
| x :: l0 -> x :: (remove eq_dec x (nodupOrder eq_dec l0))

(** val lastOpt : 'a1 list -> 'a1 option **)

let rec lastOpt = function
| [] -> None
| a :: l' -> (match l' with
              | [] -> Some a
              | _ :: _ -> lastOpt l')

(** val two_thirds : int -> int **)

let two_thirds n =
  (/) (( * ) (Stdlib.succ (Stdlib.succ 0)) n) (Stdlib.succ (Stdlib.succ
    (Stdlib.succ 0)))

(** val sumweight : ('a1 -> int) -> 'a1 list -> int **)

let sumweight f l =
  (fun f init l -> List.fold_right f l init) (fun x m -> (+) (f x) m) 0 l

type sample = unit

type roundInfo = int

(** val prevInfo : roundInfo -> roundInfo option **)

let prevInfo = fun x -> if Int.pred x > 0 then Some (Int.pred x) else None

(** val pendingRound : roundInfo -> int **)

let rec pendingRound x =
  match prevInfo x with
  | Some r' -> Stdlib.succ (pendingRound r')
  | None -> Stdlib.succ 0

type node = int

(** val nodes : roundInfo -> node list **)

let nodesTable : (roundInfo, node list) Hashtbl.t = Hashtbl.create 100
let nodes (r : roundInfo) =
  Hashtbl.find nodesTable r

(** val stake : roundInfo -> node -> int **)

let stakeTable : (roundInfo, (node, int) Hashtbl.t) Hashtbl.t = Hashtbl.create 100
let stake (r : roundInfo) (m : node) =
  Hashtbl.find (Hashtbl.find stakeTable r) m

(** val totalStake : roundInfo -> int **)

let totalStake r =
  sumweight (stake r) (nodes r)

type time = int * int

(** val lebTime : time -> time -> bool **)

let lebTime = (fun (x1,y1) (x2, y2) ->
x1 < x2 || (x1 = x2 && y1 <= y2)
)

(** val timeNatAdd : time -> int -> time **)

let timeNatAdd = (fun (x,y) z -> (x,y+z))

(** val timeNatSub : time -> int -> time **)

let timeNatSub = (fun (x,y) z -> (x,y-z))

type event = int

(** val timeCreated : event -> time **)

let timeCreatedTable : (event, time) Hashtbl.t = Hashtbl.create 100
let timeCreated (e : event) =
  Hashtbl.find timeCreatedTable e

(** val creator : event -> node **)

let creatorTable : (event, node) Hashtbl.t = Hashtbl.create 100
let creator (e : event) =
  Hashtbl.find creatorTable e

(** val birthRound : event -> int **)

let birthRoundTable : (event, int) Hashtbl.t = Hashtbl.create 100
let birthRound (e : event) =
  Hashtbl.find birthRoundTable e

(** val selfParentSigned : event -> event option **)

let selfParentSignedTable : (event, event option) Hashtbl.t = Hashtbl.create 100
let selfParentSigned (e : event) =
  Hashtbl.find selfParentSignedTable e

(** val otherParentsSigned : event -> event list **)

let otherParentsSignedTable : (event, event list) Hashtbl.t = Hashtbl.create 100
let otherParentsSigned (e : event) =
  Hashtbl.find otherParentsSignedTable e

(** val prevJudges : roundInfo -> event list **)

let prevJudgesTable : (roundInfo, event list) Hashtbl.t = Hashtbl.create 100
let prevJudges (r : roundInfo) =
  if r == 1 then [] else
  Hashtbl.find prevJudgesTable r

(** val coinInterval : roundInfo -> int **)

let coinIntervalTable : (roundInfo, int) Hashtbl.t = Hashtbl.create 100
let coinInterval (r : roundInfo) =
  Hashtbl.find coinIntervalTable r

(** val targetNumRoundsNonAncient : roundInfo -> int **)

let targetNumRoundsNonAncientTable : (roundInfo, int) Hashtbl.t = Hashtbl.create 100
let targetNumRoundsNonAncient (r : roundInfo) =
  Hashtbl.find targetNumRoundsNonAncientTable r

(** val prevJudgesCopied : roundInfo -> bool **)

let prevJudgesCopied r =
  match prevInfo r with
  | Some r' ->
    if list_eq_dec (=) (prevJudges r) (prevJudges r') then true else false
  | None -> false

(** val judgeCon1 : roundInfo -> bool **)

let judgeCon1Table : (roundInfo, bool) Hashtbl.t = Hashtbl.create 100
let judgeCon1 (r : roundInfo) =
  Hashtbl.find judgeCon1Table r

(** val prevJudgeCon1 : roundInfo -> bool **)

let prevJudgeCon1 r =
  match prevInfo r with
  | Some r' -> judgeCon1 r'
  | None -> false

(** val seeNum : roundInfo -> int **)

let seeNumTable : (roundInfo, int) Hashtbl.t = Hashtbl.create 100
let seeNum (r : roundInfo) =
  Hashtbl.find seeNumTable r

(** val seeDen : roundInfo -> int **)

let seeDenTable : (roundInfo, int) Hashtbl.t = Hashtbl.create 100
let seeDen (r : roundInfo) =
  Hashtbl.find seeDenTable r

(** val coin : event -> node option **)

let coinTable : (event, node option) Hashtbl.t = Hashtbl.create 100
let coin (e : event) =
  Hashtbl.find coinTable e

(** val exist_In_dec : 'a1 list -> ('a1 -> __ -> bool) -> bool **)

let rec exist_In_dec l hin =
  match l with
  | [] -> false
  | a :: l0 ->
    let s = exist_In_dec l0 (fun x _ -> hin x __) in
    if s then true else hin a __

(** val prevMinJudgeBirthRound : roundInfo -> int **)

let prevMinJudgeBirthRound r =
  list_min_or_0 (List.map birthRound (prevJudges r))

(** val minNonAncientRound : roundInfo -> int **)

let rec minNonAncientRound r =
  Stdlib.max (prevMinNonAncientRound r)
    (sub (prevMinJudgeBirthRound r) (targetNumRoundsNonAncient r))

(** val prevMinNonAncientRound : roundInfo -> int **)

and prevMinNonAncientRound r =
  match prevInfo r with
  | Some r' -> minNonAncientRound r'
  | None -> Stdlib.succ 0

(** val isAncient : roundInfo -> event -> bool **)

let isAncient r x =
  (<) (birthRound x) (minNonAncientRound r)

(** val selfParentNonAncient : roundInfo -> event -> event option **)

let selfParentNonAncient =
let rec selfParentNonAncientRaw self (r,x) =
  match selfParentSigned x with
  | Some y -> if isAncient r y then None else Some y
  | None -> None
in
curry2 (memo_rec selfParentNonAncientRaw)

(** val otherParentNonAncient : roundInfo -> event -> event list **)

let otherParentNonAncient =
let rec otherParentNonAncientRaw self (r,x) =
  List.filter (fun y -> not (isAncient r y)) (otherParentsSigned x)
in
curry2 (memo_rec otherParentNonAncientRaw)

(** val parentsNonAncient : roundInfo -> event -> event list **)

let parentsNonAncient =
let rec parentsNonAncientRaw self (r,e) =
  match selfParentNonAncient r e with
  | Some sp -> sp :: (otherParentNonAncient r e)
  | None -> otherParentNonAncient r e
in
curry2 (memo_rec parentsNonAncientRaw)

(** val ancestorNonAncientE : roundInfo -> event -> event -> bool **)

let rec ancestorNonAncientE =
let rec ancestorNonAncientERaw self (r,x,y) =
  (||) ((=) x y)
    (List.exists (fun z -> (curry3 self) r z y) (parentsNonAncient r x))
in
curry3 (memo_rec ancestorNonAncientERaw)

(** val ancestorsNonAncient : roundInfo -> event -> event list **)

let rec ancestorsNonAncient =
let rec ancestorsNonAncientRaw self (r,x) =
  List.append
    (List.concat_map (fun z -> (curry2 self) r z)
      (parentsNonAncient r x))
    (x :: [])
in
curry2 (memo_rec ancestorsNonAncientRaw)

(** val maxJudgeRound : roundInfo -> event -> int **)

let rec maxJudgeRound =
let rec maxJudgeRoundRaw self (r,x) =
  if in_dec (=) x (prevJudges r)
  then sub (pendingRound r) (Stdlib.succ 0)
  else list_max (List.map ((curry2 self) r) (parentsNonAncient r x))
in
curry2 (memo_rec maxJudgeRoundRaw)

(** val ancestorJudge : roundInfo -> event -> event -> bool **)

let rec ancestorJudge =
let rec ancestorJudgeRaw self (r,x,y) =
  (&&) (if in_dec (=) y (prevJudges r) then true else false)
    ((||) ((=) x y)
      (List.exists (fun z -> (curry3 self) r z y) (parentsNonAncient r x)))
in
curry3 (memo_rec ancestorJudgeRaw)

(** val selfParent : roundInfo -> event -> event option **)

let selfParent =
let rec selfParentRaw self (r,x) =
  match selfParentNonAncient r x with
  | Some y ->
    if (=) (maxJudgeRound r y) (sub (pendingRound r) (Stdlib.succ 0))
    then Some y
    else None
  | None -> None
in
curry2 (memo_rec selfParentRaw)

(** val otherParent : roundInfo -> event -> event list **)

let otherParent =
let rec otherParentRaw self (r,x) =
  List.filter (fun y ->
    (=) (maxJudgeRound r y) (sub (pendingRound r) (Stdlib.succ 0)))
    (otherParentNonAncient r x)
in
curry2 (memo_rec otherParentRaw)

(** val isOrphan : roundInfo -> event -> bool **)

let isOrphan r x =
  match selfParent r x with
  | Some _ -> false
  | None -> (match otherParent r x with
             | [] -> true
             | _ :: _ -> false)

(** val parents : roundInfo -> event -> event list **)

let parents =
let rec parentsRaw self (r,e) =
  match selfParent r e with
  | Some sp -> sp :: (otherParent r e)
  | None -> otherParent r e
in
curry2 (memo_rec parentsRaw)

(** val event_parent_ind_type :
    roundInfo -> (event -> (event -> __ -> 'a1) -> 'a1) -> event -> 'a1 **)

let rec event_parent_ind_type r node_case e =
  node_case e (fun y _ -> event_parent_ind_type r node_case y)

(** val ancestor_dec : roundInfo -> event -> event -> bool **)

let ancestor_dec r x y =
  event_parent_ind_type r (fun y0 iH x0 ->
    let s = (=) x0 y0 in
    if s then true else exist_In_dec (parents r y0) (fun p _ -> iH p __ x0))
    y x

(** val strict_ancestor_dec : roundInfo -> event -> event -> bool **)

let strict_ancestor_dec r x y =
  let s = ancestor_dec r x y in
  if s then let s0 = (=) x y in if s0 then false else true else false

(** val event_eqEO : event option -> event option -> bool **)

let event_eqEO x y =
  match x with
  | Some x0 -> (match y with
                | Some y0 -> (=) x0 y0
                | None -> false)
  | None -> (match y with
             | Some _ -> false
             | None -> true)

(** val eqbTime : time -> time -> bool **)

let eqbTime x y =
  (&&) (lebTime x y) (lebTime y x)

(** val ltbTime : time -> time -> bool **)

let ltbTime x y =
  (&&) (lebTime x y) (not (lebTime y x))

(** val gen : roundInfo -> event -> int **)

let rec gen =
let rec genRaw self (r,x) =
  Stdlib.succ (list_max (List.map ((curry2 self) r) (parents r x)))
in
curry2 (memo_rec genRaw)

(** val sum_over_nodes : roundInfo -> (node -> int) -> int **)

let sum_over_nodes r f =
  sumweight f (nodes r)

(** val supermajority : roundInfo -> int -> bool **)

let supermajority r n =
  (<) (two_thirds (totalStake r)) n

(** val voteD : roundInfo -> int **)

let voteD r =
  if (||)
       ((||) (prevJudgesCopied r)
         ((&&) (prevJudgeCon1 r) (not (judgeCon1 r))))
       (not
         (supermajority r
           (sum_over_nodes r (fun m ->
             if in_dec (=) m (List.map creator (prevJudges r))
             then stake r m
             else 0))))
  then Stdlib.succ (Stdlib.succ 0)
  else Stdlib.succ 0

(** val lastSee : roundInfo -> event -> node -> event option **)

let rec lastSee r x m =
try Hashtbl.find lastSeeMemo (r,x,m)
with Not_found ->
  let answer =
    if (=) m (creator x)
  then Some x
  else let s1 = List.filter_map (fun y -> lastSee r y m) (parents r x) in
       if (=) (List.length s1) 0
       then None
       else let k = list_max (List.map (votingRound r) s1) in
            let s2 = List.filter (fun y -> (=) (votingRound r y) k) s1 in
            (match s2 with
             | [] -> None
             | s3 :: _ ->
               let w = firstSelfWitnessS r s3 in
               let s4 =
                 List.filter (fun y -> (=) (firstSelfWitnessS r y) w) s2
               in
               firstMax s4 (fun y z -> (<=) (gen r y) (gen r z)))
  in
  Hashtbl.replace lastSeeMemo (r,x,m) answer; answer

(** val seeThru : roundInfo -> event -> node -> node -> event option **)

and seeThru r x m m' =
  if (&&) ( ((=) m' (creator x))) ( ((=) m m'))
  then (match selfParent r x with
        | Some sp -> Some (firstSelfWitnessS r sp)
        | None -> None)
  else (match lastSee r x m' with
        | Some y ->
          (match lastSee r y m with
           | Some z -> Some (firstSelfWitnessS r z)
           | None -> None)
        | None -> None)

(** val parentRound : roundInfo -> event -> int **)

and parentRound r x =
  Stdlib.max (sub (pendingRound r) (Stdlib.succ 0))
    (list_max (List.map (votingRound r) (parents r x)))

(** val stronglySeeP : roundInfo -> event -> node -> event option **)

and stronglySeeP r x m =
try Hashtbl.find stronglySeePMemo (r,x,m)
with Not_found ->
  let answer =
    match seeThru r x m m with
  | Some stx ->
    if (&&) ((=) (votingRound r stx) (parentRound r x))
         (supermajority r
           (sum_over_nodes r (fun m' ->
             if match seeThru r x m m' with
                | Some j -> (=) stx j
                | None -> false
             then stake r m'
             else 0)))
    then Some stx
    else None
  | None -> None
  in
  Hashtbl.replace stronglySeePMemo (r,x,m) answer; answer

(** val votingRound : roundInfo -> event -> int **)

and votingRound r x =
try Hashtbl.find votingRoundMemo (r,x)
with Not_found ->
  let answer =
    if (=) (Stdlib.succ (parentRound r x)) (pendingRound r)
  then if List.for_all (fun y -> (&&) (not ((=) x y)) (ancestorJudge r x y))
            (prevJudges r)
       then Stdlib.succ (parentRound r x)
       else parentRound r x
  else if (&&)
            ((&&) ((=) (parentRound r x) (pendingRound r))
              ((=) (voteD r) (Stdlib.succ 0)))
            ((<) (( * ) (totalStake r) (seeNum r))
              (( * ) (seeDen r)
                (sum_over_nodes r (fun m ->
                  if if (=) m (creator x)
                     then (match selfParent r x with
                           | Some sp ->
                             (=) (votingRound r sp) (parentRound r x)
                           | None -> false)
                     else (match lastSee r x m with
                           | Some ls ->
                             (=) (votingRound r ls) (parentRound r x)
                           | None -> false)
                  then stake r m
                  else 0))))
       then Stdlib.succ (parentRound r x)
       else if supermajority r
                 (sum_over_nodes r (fun m ->
                   match stronglySeeP r x m with
                   | Some _ -> stake r m
                   | None -> 0))
            then Stdlib.succ (parentRound r x)
            else parentRound r x
  in
  Hashtbl.replace votingRoundMemo (r,x) answer; answer

(** val firstSelfWitnessS : roundInfo -> event -> event **)

and firstSelfWitnessS r x =
try Hashtbl.find firstSelfWitnessSMemo (r,x)
with Not_found ->
  let answer =
    match selfParent r x with
  | Some sp ->
    if (<) (votingRound r sp) (votingRound r x)
    then x
    else firstSelfWitnessS r sp
  | None -> x
  in
  Hashtbl.replace firstSelfWitnessSMemo (r,x) answer; answer

(** val firstWitnessS : roundInfo -> event -> result **)

let rec firstWitnessS =
let rec firstWitnessSRaw self (r,x) =
  if isOrphan r x
  then None
  else if (=) (votingRound r x) (parentRound r x)
       then (match List.filter (fun y ->
                     (=) (votingRound r y) (votingRound r x)) (parents r x) with
             | [] -> Some x
             | y :: _ -> (curry2 self) r y)
       else Some x
in
curry2 (memo_rec firstWitnessSRaw)

(** val stronglySeeS1 : roundInfo -> event -> result **)

let stronglySeeS1 =
let rec stronglySeeS1Raw self (r,x,m) =
  match firstWitnessS r x with
  | Some y -> stronglySeeP r y m
  | None -> None
in
curry3 (memo_rec stronglySeeS1Raw)

(** val witness : roundInfo -> event -> result **)

let witness r x =
  match selfParent r x with
  | Some sp -> (<) (votingRound r sp) (votingRound r x)
  | None -> true

(** val firstVote : roundInfo -> event -> result **)

let firstVote r x m =
  if (=) (voteD r) (Stdlib.succ (Stdlib.succ 0))
  then (match List.filter_map (fun e -> stronglySeeS1 r e m)
                (List.filter_map (stronglySeeS1 r x) (nodes r)) with
        | [] -> None
        | y :: _ -> Some y)
  else (match lastSee r x m with
        | Some z ->
          let v = firstSelfWitnessS r z in
          if (=) ((+) (votingRound r v) (Stdlib.succ 0)) (votingRound r x)
          then Some v
          else (match selfParent r v with
                | Some y ->
                  if (=) ((+) (votingRound r y) (Stdlib.succ 0))
                       (votingRound r x)
                  then Some (firstSelfWitnessS r y)
                  else None
                | None -> None)
        | None -> None)

(** val stakeAgrees : roundInfo -> event -> node -> node -> int **)

let rec stakeAgrees r x m m' =
  sum_over_nodes r (fun m'' ->
    if match stronglySeeS1 r x m' with
       | Some sss1xm' ->
         (match stronglySeeS1 r x m'' with
          | Some sss1xm'' ->
            event_eqEO (fst (vote r sss1xm' m)) (fst (vote r sss1xm'' m))
          | None -> false)
       | None -> false
    then stake r m''
    else 0)

(** val topVote : roundInfo -> event -> node -> event option * bool **)

and topVote r x m =
  match firstMax (nodes r) (fun m' m'' ->
          (<=) (stakeAgrees r x m m') (stakeAgrees r x m m'')) with
  | Some c ->
    (match stronglySeeS1 r x c with
     | Some ss ->
       ((fst (vote r ss m)), (supermajority r (stakeAgrees r x m c)))
     | None -> (None, false))
  | None -> (None, false)

(** val vote : roundInfo -> event -> node -> event option * bool **)

and vote r x m =
try Hashtbl.find voteMemo (r,x,m)
with Not_found ->
  let answer =
    if (||) (not (witness r x))
       ((<) (votingRound r x) ((+) (pendingRound r) (voteD r)))
  then (None, false)
  else if (=) (votingRound r x) ((+) (pendingRound r) (voteD r))
       then ((firstVote r x m), false)
       else let (v, b) = topVote r x m in
            let q =
              (=)
                ((mod) (sub (votingRound r x) (pendingRound r))
                  (coinInterval r))
                0
            in
            if not q
            then (v, b)
            else if b
                 then (v, false)
                 else (match coin x with
                       | Some m' ->
                         if (=) (pendingRound r) (birthRound x)
                         then (match stronglySeeS1 r x m' with
                               | Some w -> ((fst (vote r w m)), false)
                               | None -> (None, false))
                         else (None, false)
                       | None -> (None, false))
  in
  Hashtbl.replace voteMemo (r,x,m) answer; answer

(** val judges : roundInfo -> event -> event list **)

let judges =
let rec judgesRaw self (r,x) =
  if not (List.for_all (fun m -> snd (vote r x m)) (nodes r))
  then []
  else let s = List.filter_map (fun m -> fst (vote r x m)) (nodes r) in
       if supermajority r
            (sum_over_nodes r (fun m ->
              if in_dec (=) m (List.map creator s) then stake r m else 0))
       then s
       else prevJudges r
in
curry2 (memo_rec judgesRaw)

module TOrder =
 struct
  type t = event

  (** val leb : event -> event -> bool **)

  let leb x y =
    lebTime (timeCreated x) (timeCreated y)
 end

module TSort = Sort(TOrder)

(** val select :
    (roundInfo -> event -> int) -> roundInfo -> event list -> int -> time **)

let rec select wt r l n =
  match l with
  | [] -> (-1,-1)
  | x :: l' ->
    if (<=) n (wt r x) then timeCreated x else select wt r l' (sub n (wt r x))

(** val weightedMedianWt :
    (roundInfo -> event -> int) -> roundInfo -> event list -> time **)

let weightedMedianWt wt r l =
  select wt r
    (List.sort (fun a b -> Pair.compare Int.compare Int.compare (timeCreated a) (timeCreated b))
      l)
    ((/) (Stdlib.succ (sumweight (wt r) l)) (Stdlib.succ (Stdlib.succ 0)))

(** val weightedMedian : roundInfo -> event list -> time **)

let weightedMedian r l =
  weightedMedianWt (fun r0 w -> stake r0 (creator w)) r l

(** val ancestorsNonAncientOptimized :
    roundInfo -> event -> event list -> event list **)

let ancestorsNonAncientOptimized a a0 b =
  let rec fix_F x =
    let r = let pr1,_ = x in pr1 in
    let x0 = let pr1,_ = let _,pr2 = x in pr2 in pr1 in
    let l = let _,pr2 = let _,pr2 = x in pr2 in pr2 in
    if in_dec (=) x0 l
    then l
    else List.append
           (fold_left (fun l0 z -> let y = r,(z,l0) in fix_F y)
             (annotate (parentsNonAncient r x0)) l)
           (x0 :: [])
  in fix_F (a,(a0,b))

(** val ancestorsNonAncientOfList : roundInfo -> event list -> event list **)

let ancestorsNonAncientOfList r l =
  fold_left (fun l0 x -> ancestorsNonAncientOptimized r x l0) l []

(** val recievedEvent : roundInfo -> event -> event -> event option **)

let rec recievedEvent r x y =
  match selfParentNonAncient r y with
  | Some p ->
    if ancestorNonAncientE r p x
    then recievedEvent r x p
    else if ancestorNonAncientE r y x then Some y else None
  | None -> if ancestorNonAncientE r y x then Some y else None

(** val isReceived : roundInfo -> event -> event list -> bool -> bool **)

let isReceived r x j b =
  (&&) (not ((=) (List.length j) 0))
    ((||)
      ((&&) (not b) (List.for_all (fun z -> ancestorNonAncientE r z x) j))
      ((&&) b (List.exists (fun z -> ancestorNonAncientE r z x) j)))

(** val reachedCon : roundInfo -> event -> event -> bool **)

let reachedCon r d x =
  (&&) (isReceived r x (judges r d) (judgeCon1 r))
    (not (isReceived r x (prevJudges r) (prevJudgeCon1 r)))

(** val timeCon : roundInfo -> event -> event -> time **)

let timeCon r d x =
  if reachedCon r d x
  then if judgeCon1 r
       then timeNatAdd (weightedMedian r (judges r d)) (gen r x)
       else weightedMedian r
              (List.filter_map (recievedEvent r x) (judges r d))
  else (-1,-1)

(** val isConsensus : roundInfo -> event -> event -> bool **)

let isConsensus =
let rec isConsensusRaw self (r,d,x) =
  isReceived r x (judges r d) (judgeCon1 r)
in
curry3 (memo_rec isConsensusRaw)

(** val reachedConSet : roundInfo -> event -> event list **)

let reachedConSet r d =
  List.filter (fun y -> reachedCon r d y)
    (if judgeCon1 r
     then ancestorsNonAncientOfList r (judges r d)
     else (match lastOpt (judges r d) with
           | Some j -> ancestorsNonAncientOptimized r j []
           | None -> []))

(** val tiebreaker : roundInfo -> event -> event -> event -> bool **)

let tiebreaker r d x y =
  match (fun eq x l -> List.find_index (fun y -> eq x y) l) (=) x
          (reachedConSet r d) with
  | Some xn ->
    (match (fun eq x l -> List.find_index (fun y -> eq x y) l) (=) y
             (reachedConSet r d) with
     | Some yn -> (<=) xn yn
     | None -> false)
  | None -> false

(** val before : roundInfo -> event -> event -> event -> bool **)

let before r d x y =
  if (||) (not (reachedCon r d x)) (not (reachedCon r d y))
  then false
  else if not (eqbTime (timeCon r d x) (timeCon r d y))
       then ltbTime (timeCon r d x) (timeCon r d y)
       else if not ((=) (gen r x) (gen r y))
            then (<) (gen r x) (gen r y)
            else tiebreaker r d x y

(** val decider : roundInfo -> event **)

let deciderTable : (roundInfo, event) Hashtbl.t = Hashtbl.create 100
let decider (r : roundInfo) =
  Hashtbl.find deciderTable r

(** val prevNumCons : roundInfo -> int **)

let rec prevNumCons r =
  match prevInfo r with
  | Some r' ->
    (+) (List.length (reachedConSet r' (decider r'))) (prevNumCons r')
  | None -> 0

(** val consensusOrder : roundInfo -> event -> event -> int **)

let consensusOrder =
let rec consensusOrderRaw self (r,d,x) =
  if not (reachedCon r d x)
  then 0
  else (+) (prevNumCons r)
         (List.length
           (List.filter (fun y -> before r d y x) (reachedConSet r d)))
in
curry3 (memo_rec consensusOrderRaw)

(** val consensusTimestamp : roundInfo -> event -> event -> time **)

let consensusTimestamp =
let rec consensusTimestampRaw self (r,d,x) =
  if not (reachedCon r d x)
  then (-1,-1)
  else if not (judgeCon1 r)
       then timeCon r d x
       else timeNatAdd (timeNatSub (timeCon r d x) (gen r x))
              (sub (consensusOrder r d x) (prevNumCons r))
in
curry3 (memo_rec consensusTimestampRaw)


let createRoundInfo id ~coinInterval ~targetNumRoundsNonAncient ~judgeCon1 ~seeNum ~seeDen =
  let () = match Hashtbl.find_opt stakeTable id with
  | Some _ -> ()
  | None -> Hashtbl.replace stakeTable id (Hashtbl.create 100)
  in
  Hashtbl.replace coinIntervalTable id coinInterval;
  Hashtbl.replace targetNumRoundsNonAncientTable id targetNumRoundsNonAncient;
  Hashtbl.replace judgeCon1Table id judgeCon1;
  Hashtbl.replace seeNumTable id seeNum;
  Hashtbl.replace seeDenTable id seeDen

let createDecider roundId eventId =
  let judges = judges roundId eventId in
  if (List.length judges) == 0 then () else
  Hashtbl.replace prevJudgesTable (roundId + 1) judges;
  Hashtbl.replace deciderTable roundId eventId

let createNode roundInfo id ~stake =
  let table =
    match Hashtbl.find_opt stakeTable roundInfo with
    | Some t -> t
    | None ->
      let t = Hashtbl.create 100 in
      Hashtbl.replace stakeTable roundInfo t;
      t
  in
  let () = Hashtbl.replace table id stake in
  let list =
    match Hashtbl.find_opt nodesTable roundInfo with
    | Some l -> l
    | None -> []
  in
  Hashtbl.replace nodesTable roundInfo (List.append list (id :: []))


let createEvent id ~timeCreated ~creator ~birthRound ~selfParentSigned ~otherParentsSigned ~coin =
  Hashtbl.replace timeCreatedTable id timeCreated;
  Hashtbl.replace creatorTable id creator;
  Hashtbl.replace birthRoundTable id birthRound;
  Hashtbl.replace selfParentSignedTable id selfParentSigned;
  Hashtbl.replace otherParentsSignedTable id otherParentsSigned;
  Hashtbl.replace coinTable id coin

let () = clear := let stored = !clear in (fun () ->
  Hashtbl.clear nodesTable;
  Hashtbl.clear stakeTable;
  Hashtbl.clear timeCreatedTable;
  Hashtbl.clear creatorTable;
  Hashtbl.clear birthRoundTable;
  Hashtbl.clear selfParentSignedTable;
  Hashtbl.clear otherParentsSignedTable;
  Hashtbl.clear prevJudgesTable;
  Hashtbl.clear coinIntervalTable;
  Hashtbl.clear targetNumRoundsNonAncientTable;
  Hashtbl.clear judgeCon1Table;
  Hashtbl.clear seeNumTable;
  Hashtbl.clear seeDenTable;
  Hashtbl.clear coinTable;
  Hashtbl.clear deciderTable;
  stored()
)