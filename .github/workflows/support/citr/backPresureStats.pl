$max=0;
$sum=0;
$count=0;

while (<>) {
  if (/VirtualPipeline[\:] Total size backpressure[\:]\s+(\d+)\s+ms/ ) {
    $sum+=$1;
    $count++;
    if ($max < $1) {
      $max=$1;
    }
  }
}

print "BackPressure stats: Count=$count Sum=$sum Max=$max\n";