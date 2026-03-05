%stat=();

while (<>) {
  if (/^\w+[\:]\d+$/) {
    ($id, $counter)=split(/[\:]/,$_,2);
    $stat{"$id"}{"counter"}+=$counter;
  }
}
foreach $id (sort { $stat{$b}{"counter"} <=> $stat{$a}{"counter"} } keys (%stat) ) {
  printf("%-35s %10d\n",$id,$stat{$id}{"counter"});
}
