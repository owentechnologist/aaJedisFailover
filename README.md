There are multiple strategies for failing over using client-side logic.
Concerns when performing failovers include:

* Failing Back
* Encouraging agreement regarding a healthy endpoint across multiple client instances within a JVM as well as across multiple VMs
* Reducing time taken to Failover/ Failback to accomodate use cases with many mutations to keys 

There are currently two strategies to enable failing over using client-side logic:
1. using the MultiClusterPooledConnectionProvider included in Jedis (this example)
2. using an additional library based on Guava's strategy for determining agreeable service endpoints (not part of this example) For more information on this failback-ready solution contact the Redis team, or check out the Guava framework here and build your own: https://github.com/google/guava/blob/2076b8ecbf78625bfa2cdb3c56d14020371ad692/guava-tests/test/com/google/common/util/concurrent/ServiceManagerTest.java#L60 


This example showcases the use of the MultiClusterPooledConnectionProvider which does not include failback and also does not as of 2025-01-01 utilize the Availability API for Redis.  

This example shows how Jedis can be configured to failover between two instances of Redis.  It also tests running multiple client Threads against one or more Redis endpoints.

The Main class executes a thread where it reads and writes to some keys repeatedly.  If the redis instance it is talking to becomes unavailable, it  switches to a back up instance after a configured # of retries.

At the moment of the switch, a callback method is invoked which prints out the message indicating that the switch has ocurred.

The configuration is done in code that creates a connectionHelper instance using two arguments - each being a configuration object of the type: JedisConnectionHelperSettings.
Each of the JedisConnectionHelperSettings objects holds the information to connect to a particular redis endpoint:

```
    public JedisConnectionHelper(JedisConnectionHelperSettings bs, JedisConnectionHelperSettings bs2){
        System.out.println("Creating JedisConnectionHelper for failover with "+bs+" \nand\n "+bs2);
        JedisClientConfig config = DefaultJedisClientConfig.builder().user(bs.getUserName()).password(bs.getPassword()).build();
        JedisClientConfig config2 = DefaultJedisClientConfig.builder().user(bs2.getUserName()).password(bs2.getPassword()).build();

        ClusterConfig[] clientConfigs = new ClusterConfig[2];
        clientConfigs[0] = new ClusterConfig(new HostAndPort(bs.getRedisHost(), bs.getRedisPort()), config);
        clientConfigs[1] = new ClusterConfig(new HostAndPort(bs2.getRedisHost(), bs2.getRedisPort()), config2);

        MultiClusterClientConfig.Builder builder = new MultiClusterClientConfig.Builder(clientConfigs);
        builder.circuitBreakerSlidingWindowSize(10);
        builder.circuitBreakerSlidingWindowMinCalls(1);
        builder.circuitBreakerFailureRateThreshold(50.0f);

        MultiClusterPooledConnectionProvider provider = new MultiClusterPooledConnectionProvider(builder.build());

        FailoverReporter reporter = new FailoverReporter();
        provider.setClusterFailoverPostProcessor(reporter);

        this.unifiedJedis = new UnifiedJedis(provider);

        this.connectionProvider = null;
        this.jedisPooled = null;
    }  
```
At the end of the non-LUA test you can check for the values each thread has assigned to their String keys and the expected value:

In the SortedSet for each non-LUA test we see the entries in the SortedSet as "<thread#>:<actualValueReadFromRedis>"

Below each entry in the SortedSet we see the expected value calculated within that thread with a local variable

```
> ZRANGE Z:TestMultiThread:ExpectedValues:10:DefaultConnectionTest 0 -1 WITHSCORES
1) "0:900"
2) "900"
3) "1:900"
4) "900"
5) "2:900"
6) "900"
7) "3:900"
8) "900"
9) "4:900"
10) "900"
11) "5:900"
12) "900"
13) "6:900"
14) "900"
15) "7:900"
16) "900"
17) "8:900"
18) "900"
19) "9:900"
20) "900"
```

A sample run of this program where two instances of Redis are utilized would be:

``` 
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--failover true --host mydb.host.1.org --port 10900 --password pass1 --host2 mydb.host.2.org --port2 10900 --password2 pass2 --timebasedfailover false --maxconnections 30"
```
And with additional logging of the underlying circuitBreaker logic:
``` 
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--failover true --host mydb.host.1.org --port 10900 --host2 mydb.host.2.org --port2 10900" -Dorg.slf4j.simpleLogger.log.redis.clients.jedis=TRACE
```

Note that the multThreaded non-LUA test publishes to a pubsub channel it is good to listen in and watch as it pauses and resumes during a failover event
* from redis-cli (once you are connected to the backup database) issue:
``` 
subscribe ps:messages
```
Then, disable, kill the first database and watch as the messages pause and then pick up again as the client threads failover

If you wish to target a database that exposes the ClusterAPI you can add this flag:
``` 
--useclusterapi true
``` 

The default number of tasks per thread is 1    you can adjust this by using this argument:
``` 
--taskcount 1000
``` 

The default number of client threads for the test is 10  you can adjust that by using this
``` 
--numclientthreads 100
``` 
The client will output to the screen a mesage with the latency in milliseconds for
tasks that round-trip take more than 3000 milliseconds by defautlt.  Adjust this by using:
``` 
--latencythreshold 1000
``` 

See the Main class for more args...

Below are many sample test runs designed to illustrate impact of Jedis settings:

```
*** Jedis Pool Settings showcase:

Poor results / failure because socketConnection is too short:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host FIXME.remote.com --port 10000  --password FIXME.remote.com --maxconnections 10 --requesttimeoutmillis 20 --maxwaitminutes 1 --latencythreshold 100 --taskcount 5 --numclientthreads 3 --minidleconnections 0 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow false --testonreturn false --testoncreate true --testwhileidle false --connectiontimeoutmillis 1"

readTimeout 20 millis fails even as socketConnection timeout good at 50 millis:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host FIXME.remote.com --port 10000  --password FIXME --maxconnections 10 --maxwaitminutes 1 --latencythreshold 100 --taskcount 5 --numclientthreads 3 --minidleconnections 0 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow false --testonreturn false --testoncreate true --testwhileidle false --connectiontimeoutmillis 75 --requesttimeoutmillis 20"

Fixed readTimeout and socketConnection timeout but need co-location:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host FIXME.remote.com --port 10000  --password FIXME --maxconnections 10 --maxwaitminutes 1 --latencythreshold 100 --taskcount 5 --numclientthreads 3 --minidleconnections 0 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow false --testonreturn false --testoncreate true --testwhileidle false  --connectiontimeoutmillis 75 --requesttimeoutmillis 50"

*** Co-location!!! ***

Can still have Poor results /failures because readTimeout is 4 millis:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host redis-10900.re-cluster1.ps-redislabs.org --port 10900 --maxconnections 2 --maxwaitminutes 1 --latencythreshold 90 --taskcount 5 --numclientthreads 3 --minidleconnections 0 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow true --testonreturn true --testoncreate true --testWhileIdle false --connectiontimeoutmillis 10 --requesttimeoutmillis 4"

Broken because blockWhenExhausted is false and there are more threads than connections in pool:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host redis-10900.re-cluster1.ps-redislabs.org --port 10900 --maxwaitminutes 1 --latencythreshold 90 --taskcount 5 --minidleconnections 0 --numberevictiontests 1 --millisminevictableidletime 2 --testonborrow false --testonreturn false --testoncreate true --testWhileIdle false --connectiontimeoutmillis 10 --requesttimeoutmillis 20 --maxconnections 2 --numclientthreads 3 --blockwhenexhausted false"

Broken because maxwaitminutes is 0 and blockWhenExhausted is true: 
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host redis-10900.re-cluster1.ps-redislabs.org --port 10900 --latencythreshold 90 --taskcount 5 --minidleconnections 0 --numberevictiontests 1 --millisminevictableidletime 2 --testonborrow false --testonreturn false --testoncreate true --testWhileIdle false --connectiontimeoutmillis 10 --requesttimeoutmillis 20 --maxconnections 2 --numclientthreads 3 --blockwhenexhausted true --maxwaitminutes 0"

Fixed because, blockWhenExhausted is true and maxwaitminutes is 1
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host redis-10900.re-cluster1.ps-redislabs.org --port 10900 --latencythreshold 90 --taskcount 5 --minidleconnections 0 --numberevictiontests 1 --millisminevictableidletime 2 --testonborrow false --testonreturn false --testoncreate true --testWhileIdle false --connectiontimeoutmillis 10 --requesttimeoutmillis 20 --maxconnections 2 --numclientthreads 3 --blockwhenexhausted true --maxwaitminutes 1"

Slower results because testOnBorrow and return is true:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host redis-10900.re-cluster1.ps-redislabs.org --port 10900 --connectiontimeoutmillis 10 --maxconnections 2 --requesttimeoutmillis 20 --maxwaitminutes 1 --latencythreshold 90 --taskcount 5 --numclientthreads 3 --minidleconnections 0 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow true --testonreturn true --testoncreate true"

Even Slower results because only 1 connection and test on return and borrow:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host redis-10900.re-cluster1.ps-redislabs.org --port 10900 --connectiontimeoutmillis 10 --requesttimeoutmillis 20 --maxwaitminutes 1 --latencythreshold 90 --taskcount 5 --numclientthreads 3 --minidleconnections 0 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow true --testonreturn true --testoncreate true --testwhileidle false --maxconnections 1"

Faster results because only 1 connection but no testOnBorrow or return:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host redis-10900.re-cluster1.ps-redislabs.org --port 10900 --connectiontimeoutmillis 10 --requesttimeoutmillis 20 --maxwaitminutes 1 --latencythreshold 90 --taskcount 5 --numclientthreads 3 --minidleconnections 0 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow false --testonreturn false --testoncreate true --testwhileidle false --maxconnections 1"

Fast results because 10 connections and only test on create:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host redis-10900.re-cluster1.ps-redislabs.org --port 10900 --connectiontimeoutmillis 10 --requesttimeoutmillis 20 --maxwaitminutes 1 --latencythreshold 90 --taskcount 5 --numclientthreads 3 --minidleconnections 0 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow false --testonreturn false --testoncreate true --testwhileidle false --maxconnections 10"

Testing with 50 millis latency threshold: ( have been using 90 millis ):
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host redis-10900.re-cluster1.ps-redislabs.org --port 10900  --connectiontimeoutmillis 10 --requesttimeoutmillis 20 --maxwaitminutes 1 --taskcount 5 --numclientthreads 3 --minidleconnections 0 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow false --testonreturn false --testoncreate true --testwhileidle false --maxconnections 10 --latencythreshold 50"

Same as above but now not testing on Create: (not really noticeable)
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host redis-10900.re-cluster1.ps-redislabs.org --port 10900  --connectiontimeoutmillis 10 --requesttimeoutmillis 20 --maxwaitminutes 1 --taskcount 5 --numclientthreads 3 --minidleconnections 0 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow false --testonreturn false --testoncreate false --testwhileidle false --maxconnections 10 --latencythreshold 50"

*** OSS CLusterAPI ossredirections == silent retries ***

SLOW: With 10 connections per endpoint 500 threads:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--clusterhost redis-12000.re-cluster1.ps-redislabs.org --clusterport 12000  --connectiontimeoutmillis 10 --maxconnections 10 --requesttimeoutmillis 20 --maxwaitminutes 1 --latencythreshold 750 --taskcount 5 --numclientthreads 500 --minidleconnections 16 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow false --testonreturn false --testoncreate false --testwhileidle true --useclusterapi true --ossredirections 10"

FAST: With 100 connections per endpoint 500 threads:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--clusterhost redis-12000.re-cluster1.ps-redislabs.org --clusterport 12000  --connectiontimeoutmillis 10 --maxconnections 100 --requesttimeoutmillis 20 --maxwaitminutes 1 --latencythreshold 750 --taskcount 5 --numclientthreads 500 --minidleconnections 10 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow false --testonreturn false --testoncreate false --testwhileidle true --useclusterapi true --ossredirections 3"

FAST: (moved retries [ossredirections] has no impact when healthy): 
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--clusterhost redis-12000.re-cluster1.ps-redislabs.org --clusterport 12000  --connectiontimeoutmillis 10 --maxconnections 100 --requesttimeoutmillis 20 --maxwaitminutes 1 --latencythreshold 750 --taskcount 5 --numclientthreads 500 --minidleconnections 10 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow false --testonreturn false --testoncreate false --testwhileidle true --useclusterapi true --ossredirections 100"


Remote OSS ClusterAPI - 10 connections Socket Timeout evidenced with small retry:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--clusterhost ClusterAPI.FIXME.com --clusterport 10000 --password FIXME --maxconnections 10 --maxwaitminutes 1 --latencythreshold 750 --taskcount 5 --numclientthreads 50 --minidleconnections 10 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow false --testonreturn false --testoncreate true --testwhileidle true --useclusterapi true --ossredirections 3 --connectiontimeoutmillis 40 --requesttimeoutmillis 30"

Remote OSS ClusterAPI - 10 connections Socket Timeout hidden with large retry:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--clusterhost ClusterAPI.FIXME.com --clusterport 10000 --password FIXME --maxconnections 10 --maxwaitminutes 1 --latencythreshold 750 --taskcount 5 --numclientthreads 50 --minidleconnections 10 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow false --testonreturn false --testoncreate true --testwhileidle true --useclusterapi true --ossredirections 30 --connectiontimeoutmillis 40 --requesttimeoutmillis 30"

Remote OSS ClusterAPI - 100 connections Socket Timeout hidden with large retry:
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--clusterhost ClusterAPI.FIXME.com --clusterport 10000 --password FIXME --maxconnections 100 --maxwaitminutes 1 --latencythreshold 750 --taskcount 5 --numclientthreads 50 --minidleconnections 10 --numberevictiontests 1 --millisminevictableidletime 2 --blockwhenexhausted true --testonborrow false --testonreturn false --testoncreate true --testwhileidle true --useclusterapi true --ossredirections 30 --connectiontimeoutmillis 40 --requesttimeoutmillis 30"
```