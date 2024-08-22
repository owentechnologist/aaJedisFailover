This example showcases how Jedis can be configured to failover between two instances of Redis.  It also tests running multiple client Threads against one or more Redis endpoints.

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