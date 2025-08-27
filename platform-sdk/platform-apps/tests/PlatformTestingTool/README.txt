


VM parameters
=============

	Parameters related with payload generation
	
		-Dseq=true   enable insert sequence number
		-Dsig=true   enable append signature after payload
		-Dvary=true  enable varied payload size, min value defined by -Dsize=xxx,  max value defined by -Dmax=yyy (inclusive)
		
		-Dptype=0    defien payload type, 0 or default is random bytes payload
		             1 is file system test payload
		             2 is database system test payload (not implemented yet)
	          
    
	    The following parameters used to control the payload distribution
	    
	    -Dsizes=100,300,400
	    -Dtypes=0,0,1
	    -Dratio=90,5,5
	    
	                 Generate 90% payload type 0 (random bytes) with size 100 byte
	                 Generate 5% payload type 0 (random bytes) with size 300 byte
	                 Generate 5% payload type 1 (file creation payload) with size 400 byte

	    -Dpause=100  pause transaction submission for a while, after 100 transaction, then resume  

	Parameter related with submitting transaction speed control
		
		-Dtarget=0              system metric target try to achieve, use -Dlimit to define target value
		
					        0,  BYTES_PER_SECOND,
							1,  TRANS_PER_SECOND,
							2,  EVENTS_PER_SECOND,
							3,  ROUNDS_PER_SECOND,
							4,  TRANS_PER_EVENT,
							5,  C2C_LATENCY
		             
		-Dlimit=10000000               
	
	    -Dslow=10             slow down application, every time handleTranscation is called put thread sleep for 10 millisecond
	                         or any specific value defined
	
For example  

		java  -Dtarget=1 -Dlimit=10 -jar swirlds.jar

	Try to control submit speed at 10 transaction per second


		java  -Dtarget=1 -Dlimit=10 -Dseq=true -Dvary=true -Dmax=200 -jar swirlds.jar

	Also insert sequence number and generating payload size varies between 100 bytes and 200 bytes



	Parameter related with submitting file transaction payload

		-Ddebug=1      0 turn off debug message printing,  1 turn on debug message printing
		-Damount=100   amount of files created
		-Ddir=100      amount of directory created
		-Dsize=200     file creation size


	When testing file system, there are two test scenario.
	
	One is flat directory with lots of files under directory /shard0/realm0, only creating files don't create directories
	so please set -Ddir as 0, also use -Dreal=true to indicate only creating files under /shard0/realm0 directory
	
	
			java -Dptype=1 -Dtarget=1 -Dlimit=5 -Ddir=0 -Damount=2000  -Dsize=3000 -Drealm=true -jar swirlds.jar
			
			Test new file system file creating, submission speed is 5 transaction per second per node. Each node creating 2000 file,
			each file is 3000 bytes
			
			
	Second test scenario is generating many sub-directories /c_xxx or /d_yyy under /shard0/realm0
	then under each sub-directories generating 5~10 files, results look like this:
	
		/shard0/realm0/c_x/File*
		/shard0/realm0/d_x/File*		
	
			java -Dptype=1 -Dtarget=1 -Dlimit=5 -Ddir=300 -Damount=1500  -Dsize=1000 -jar swirlds.jar
			
			With this command, every node creating 300 directories /shard0/realm0/c_x and 300 directories /shard0/realm0/d_x,
			then random creating 1000 files and also 1000 meta files under newly created 600 sub-directories.



Standalone mode
===============

java  -Ddebug=1 -Damount=10 -Dseed=100  -classpath  swirlds.jar:data/apps/PlatformTestingDemo.jar com/swirlds/platform/fs/stresstest/FileStressTest


supported parameter

debug    0 turn off debug message printing,  1 turn on debug message printing
amount   amount of files or directories created
size     file creation size

test=0   file creation test
test=1   file delete test
test=2   file read test
test=3   file append test
test=4   file insert test
test=5   file chop test
test=6   file modification test, mix of append, insert and chop

check=no  skip checking after file creation or modification
repeat=?  repeat how many times

