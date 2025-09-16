#!/usr/bin/env python3

#
# Copyright 2016-2022 Hedera Hashgraph, LLC
#
# This software is the confidential and proprietary information of
# Hedera Hashgraph, LLC. ("Confidential Information"). You shall not
# disclose such Confidential Information and shall use it only in
# accordance with the terms of the license agreement you entered into
# with Hedera Hashgraph.
#
# HEDERA HASHGRAPH MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
# THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
# TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
# PARTICULAR PURPOSE, OR NON-INFRINGEMENT. HEDERA HASHGRAPH SHALL NOT BE LIABLE FOR
# ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
# DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
#

# This program reads from standard in repeats to standard out, removing spammy log lines.
# An example of this log is shown below. It is a known issue without a fix. It is harmless, but annoying.

# 2023-06-27 11:22:58.383 java[64808:1357823] Bad JNI lookup accessibilityHitTest
# 2023-06-27 11:22:58.386 java[64808:1357823] (
# 	0   libawt_lwawt.dylib                  0x0000000150ecdccd -[CommonComponentAccessibility accessibilityHitTest:] + 173
# 	1   libawt_lwawt.dylib                  0x0000000150e86d83 -[AWTView accessibilityHitTest:] + 179
# 	2   AppKit                              0x00007ff80ca55673 -[NSWindow(NSWindowAccessibility) accessibilityHitTest:] + 302
# 	3   AppKit                              0x00007ff80c524b3a -[NSApplication(NSApplicationAccessibility) accessibilityHitTest:] + 285
# 	4   AppKit                              0x00007ff80c4e5abb CopyElementAtPosition + 138
# 	5   HIServices                          0x00007ff80e917efa _AXXMIGCopyElementAtPosition + 399
# 	6   HIServices                          0x00007ff80e93a128 _XCopyElementAtPosition + 355
# 	7   HIServices                          0x00007ff80e8f74c9 mshMIGPerform + 182
# 	8   CoreFoundation                      0x00007ff80910ae2d __CFRUNLOOP_IS_CALLING_OUT_TO_A_SOURCE1_PERFORM_FUNCTION__ + 41
# 	9   CoreFoundation                      0x00007ff80910ad70 __CFRunLoopDoSource1 + 536
# 	10  CoreFoundation                      0x00007ff809109a00 __CFRunLoopRun + 2698
# 	11  CoreFoundation                      0x00007ff80910891c CFRunLoopRunSpecific + 560
# 	12  HIToolbox                           0x00007ff81304cdad RunCurrentEventLoopInMode + 292
# 	13  HIToolbox                           0x00007ff81304cbbe ReceiveNextEventCommon + 657
# 	14  HIToolbox                           0x00007ff81304c918 _BlockUntilNextEventMatchingListInModeWithFilter + 64
# 	15  AppKit                              0x00007ff80c0fc5d0 _DPSNextEvent + 858
# 	16  AppKit                              0x00007ff80c0fb47a -[NSApplication(NSEvent) _nextEventMatchingEventMask:untilDate:inMode:dequeue:] + 1214
# 	17  libosxapp.dylib                     0x000000015013d4fa -[NSApplicationAWT nextEventMatchingMask:untilDate:inMode:dequeue:] + 122
# 	18  AppKit                              0x00007ff80c0edae8 -[NSApplication run] + 586
# 	19  libosxapp.dylib                     0x000000015013d2c9 +[NSApplicationAWT runAWTLoopWithApp:] + 185
# 	20  libawt_lwawt.dylib                  0x0000000150eee908 +[AWTStarter starter:headless:] + 520
# 	21  libosxapp.dylib                     0x000000015013f00f +[ThreadUtilities invokeBlockCopy:] + 15
# 	22  Foundation                          0x00007ff809f14793 __NSThreadPerformPerform + 177
# 	23  CoreFoundation                      0x00007ff80910a906 __CFRUNLOOP_IS_CALLING_OUT_TO_A_SOURCE0_PERFORM_FUNCTION__ + 17
# 	24  CoreFoundation                      0x00007ff80910a8a9 __CFRunLoopDoSource0 + 157
# 	25  CoreFoundation                      0x00007ff80910a686 __CFRunLoopDoSources0 + 217
# 	26  CoreFoundation                      0x00007ff80910930a __CFRunLoopRun + 916
# 	27  CoreFoundation                      0x00007ff80910891c CFRunLoopRunSpecific + 560
# 	28  libjli.dylib                        0x000000010a866e82 CreateExecutionEnvironment + 402
# 	29  libjli.dylib                        0x000000010a8626a8 JLI_Launch + 1496
# 	30  java                                0x00000001022d1c0e main + 414
# 	31  dyld                                0x00000002026ae41f start + 1903
# )
# Exception in thread "AppKit Thread" java.lang.NoSuchMethodError: accessibilityHitTest


from sys import stdin
from sys import stdout

start_of_spam = "] Bad JNI lookup accessibilityHitTest"
end_of_spam = "Exception in thread \"AppKit Thread\" java.lang.NoSuchMethodError: accessibilityHitTest"

in_spam = False
next_line_is_spam = False

def line_is_spam(line):
  global in_spam
  global next_line_is_spam

  if not next_line_is_spam:
    in_spam = False

  if not in_spam:
    if start_of_spam in line:
      in_spam = True
      next_line_is_spam = True
  else:
    if end_of_spam in line:
      # current line is spam, but the next one isn't (as far as we know at this moment)
      next_line_is_spam = False

  return in_spam

try:
  for line in stdin:
    if line_is_spam(line):
      continue
    print(line, end='')
    stdout.flush()
except KeyboardInterrupt:
  pass