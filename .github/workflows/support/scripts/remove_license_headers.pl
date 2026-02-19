#!/usr/bin/env perl

use strict;
use warnings;
use v5.18;

my $isInCommentBlock = 0;
my $commentBlockBuffer = undef;
my $isInCopyrightBlock = 0;

# Read contents of Standard Input a single line at a time
while (my $line = <STDIN>) {
    # Trim the newlines
    chomp($line);

    # If currently within a comment block and have encountered a closing delimiter
    if (($line =~ /(-->|\*\/)/gi) && $isInCommentBlock == 1) {
        $isInCommentBlock = 0;

        # Print out the comment if it is not a copyright block and we have a buffer
        if (defined($commentBlockBuffer) && $isInCopyrightBlock == 0) {
            print "$commentBlockBuffer";
            print "$line\n";

            $commentBlockBuffer = undef;
        }

        next;
    }

    # Detect a comment open tag excluding single line comments and javadoc style comments
    if ($line =~ /(<!--|\/\*)\s*$/gi) {
        $isInCommentBlock = 1;
        $commentBlockBuffer = "$line\n";

        next;
    }

    if ($isInCommentBlock == 0) {
        # Print the line since we are not within a comment block
        print "$line\n";
    }
    else {
        # Detect the presence of a copyright declaration
        if ($line =~ /(\(c\)\s+[0-9\-]{2,9}|copyright|This\sfile\sis\spublic\sdomain\.)/gi) {
            $isInCopyrightBlock = 1;
        }

        # Buffer the comment block
        $commentBlockBuffer .= "$line\n";
    }
}
