package com.redislabs.sa.ot.aajf;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.*;
import redis.clients.jedis.providers.PooledConnectionProvider;

import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisConnectionException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import redis.clients.jedis.providers.MultiClusterPooledConnectionProvider;
import redis.clients.jedis.MultiClusterClientConfig;
import redis.clients.jedis.MultiClusterClientConfig.ClusterConfig;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.MultiClusterClientConfig.Builder;

import java.util.function.Consumer;


/**
 You can run this program either with --failover true AND the full set of args:
 ie: --host xxx and --host2 yyy etc...  or you can leave out the --failover argument and only
 connect to a single Redis database
 mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host FIXME --port FIXME --password FIXME"
 mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host redis-12001.bamos1-tf-us-west-2-cluster.redisdemo.com --port 12001"

 If you wish to target a database that exposes the ClusterAPI you can add this flag:
 --useclusterapi true

 The default number of tasks per thread is 1    you can adjust this by using this argument:
 --taskcount 1000

 The default number of client threads for the test is 10  you can adjust that by using this
 --numclientthreads 100

 The client will output to the screen a mesage with the latency in milliseconds for
 tasks that round-trip take more than 3000 milliseconds by defautlt.  Adjust this by using:
 --latencythreshold 1000

 Other possible args:

 --connectiontimeoutmillis 2000

 --requesttimeoutmillis 400

 --isusinglua true

 --maxconnections 500

 below is an example of providing the args for a failover scenario:
 mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--failover true --host FIXME --port FIXME --password FIXME --host2 FIXME --port2 FIXME --password2 FIXME --maxconnections 100 --timebasedfailover false --numclientthreads 300 --taskcount 500"

 */
public class Main
{
    public static void main( String[] args ){
        /**
         Only UnifiedJedis allows for auto-failover behaviors
         If --failover true is NOT passed in, a JedisPooled will be created
         ^ this is good, and allows for the best pooled connection management while also allowing
         the creation of the failover-capable UnifiedJedis when that is desired
         **/
        System.out.println("Starting Tests...\n");

        int numberOfThreads = 10;
        int taskCountPerThread = 250;
        int latencyThreshold = 3000; // milliseconds as measured round trip from client
        boolean useClusterAPI=false;
        boolean isUsingLua=false;

        ArrayList<String> argList1 = new ArrayList<>(Arrays.asList(args));
        if (argList1.contains("--numclientthreads")) {
            int argIndex = argList1.indexOf("--numclientthreads");
            numberOfThreads = (Integer.parseInt(argList1.get(argIndex + 1)));
        }
        if (argList1.contains("--taskcount")) {
            int argIndex = argList1.indexOf("--taskcount");
            taskCountPerThread = (Integer.parseInt(argList1.get(argIndex + 1)));
        }
        if (argList1.contains("--latencythreshold")) {
            int argIndex = argList1.indexOf("--latencythreshold");
            latencyThreshold = (Integer.parseInt(argList1.get(argIndex + 1)));
        }
        if (argList1.contains("--useclusterapi")) {
            int argIndex = argList1.indexOf("--useclusterapi");
            useClusterAPI = (Boolean.parseBoolean(argList1.get(argIndex + 1)));
        }
        if (argList1.contains("--isusinglua")) {
            int argIndex = argList1.indexOf("--isusinglua");
            isUsingLua = (Boolean.parseBoolean(argList1.get(argIndex + 1)));
        }
        //JedisPooled defaultConnection = new JedisPooled(host,port);
        //TestMultiThread.fireTest(defaultConnection,numberOfThreads, numberOfThreads+":DefaultConnectionTest", taskCountPerThread, isUsingLua);

        if(useClusterAPI){
            //Begin ClusterAPI test:
            JedisCluster jcConnection = JedisConnectionHelper.getJedisClusterConnection(args);
            TestMultiThread.fireTest(jcConnection,numberOfThreads,numberOfThreads+":ClusteredTest",taskCountPerThread, latencyThreshold, isUsingLua);
            //end MultiThreadTest
        }else{
            // to match the CLusterAPI pool should have 3X default connections (24)
            // only use default if --useclusterapi false or non-existent:
            UnifiedJedis connection= JedisConnectionHelper.initRedisConnection(args);
            TestMultiThread.fireTest(connection,numberOfThreads, numberOfThreads+":DefaultConnectionTest", taskCountPerThread, latencyThreshold, isUsingLua);
        }

        //POSSIBLE FUTURE WORK:
        //Do some LUA calls using SortedSets: The class LittleLua is designed to allow this
    }
}

class JedisConnectionHelper {
    final PooledConnectionProvider connectionProvider;
    final JedisPooled jedisPooled;
    final UnifiedJedis unifiedJedis;

    /**
     following private static attributes borrowed from Jedis github tests...
     */
    private static final int DEFAULT_TIMEOUT = 2000;
    private static final int DEFAULT_REDIRECTIONS = 5;
    private static final ConnectionPoolConfig DEFAULT_POOL_CONFIG = new ConnectionPoolConfig();
    private static final DefaultJedisClientConfig DEFAULT_CLIENT_CONFIG
            = DefaultJedisClientConfig.builder().build();

    private static JedisConnectionHelperSettings statSettings = null;

    static JedisConnectionHelperSettings assignArgsToSettings(JedisConnectionHelperSettings settings,String[] args){
        ArrayList<String> argList = new ArrayList<>(Arrays.asList(args));
        if (argList.contains("--host")) {
            int argIndex = argList.indexOf("--host");
            settings.setRedisHost(argList.get(argIndex + 1));
        }
        if (argList.contains("--port")) {
            int argIndex = argList.indexOf("--port");
            settings.setRedisPort(Integer.parseInt(argList.get(argIndex + 1)));
        }
        if (argList.contains("--connectiontimeoutmillis")) {
            int argIndex = argList.indexOf("--connectiontimeoutmillis");
            settings.setConnectionTimeoutMillis(Integer.parseInt(argList.get(argIndex + 1)));
        }
        if (argList.contains("--maxidleconnections")) {
            int argIndex = argList.indexOf("--maxidleconnections");
            settings.setPoolMaxIdle(Integer.parseInt(argList.get(argIndex + 1)));
        }
        if (argList.contains("--maxwaitminutes")) {
            int argIndex = argList.indexOf("--maxwaitminutes");
            settings.setNumberOfMinutesForWaitDuration(Integer.parseInt(argList.get(argIndex + 1)));
        }
        if (argList.contains("--minidleconnections")) {
            int argIndex = argList.indexOf("--minidleconnections");
            settings.setPoolMinIdle(Integer.parseInt(argList.get(argIndex + 1)));
        }
        if (argList.contains("--numberevictiontests")) {
            int argIndex = argList.indexOf("--numberevictiontests");
            settings.setNumTestsPerEvictionRun(Integer.parseInt(argList.get(argIndex + 1)));
        }
        if (argList.contains("--requesttimeoutmillis")) {
            int argIndex = argList.indexOf("--requesttimeoutmillis");
            settings.setRequestTimeoutMillis(Integer.parseInt(argList.get(argIndex + 1)));
        }
        if (argList.contains("--millisbetweenevictionruns")) {
            int argIndex = argList.indexOf("--millisbetweenevictionruns");
            settings.setTimeBetweenEvictionRunsMilliseconds(Integer.parseInt(argList.get(argIndex + 1)));
        }
        if (argList.contains("--millisminevictableidletime")) {
            int argIndex = argList.indexOf("--millisminevictableidletime");
            settings.setMinEvictableIdleTimeMilliseconds(Integer.parseInt(argList.get(argIndex + 1)));
        }
        if (argList.contains("--blockwhenexhausted")) {
            int argIndex = argList.indexOf("--blockwhenexhausted");
            settings.setBlockWhenExhausted(Boolean.parseBoolean(argList.get(argIndex + 1)));
        }
        if (argList.contains("--testwhileidle")) {
            int argIndex = argList.indexOf("--testwhileidle");
            settings.setTestWhileIdle(Boolean.parseBoolean(argList.get(argIndex + 1)));
        }
        if (argList.contains("--testonborrow")) {
            int argIndex = argList.indexOf("--testonborrow");
            settings.setTestOnBorrow(Boolean.parseBoolean(argList.get(argIndex + 1)));
        }
        if (argList.contains("--testonreturn")) {
            int argIndex = argList.indexOf("--testonreturn");
            settings.setTestOnReturn(Boolean.parseBoolean(argList.get(argIndex + 1)));
        }
        if (argList.contains("--testoncreate")) {
            int argIndex = argList.indexOf("--testoncreate");
            settings.setTestOnCreate(Boolean.parseBoolean(argList.get(argIndex + 1)));
        }
        if (argList.contains("--user")) {
            int argIndex = argList.indexOf("--user");
            settings.setUserName(argList.get(argIndex + 1));
        }
        if (argList.contains("--password")) {
            int argIndex = argList.indexOf("--password");
            settings.setPassword(argList.get(argIndex + 1));
            settings.setUsePassword(true);
        }
        if (argList.contains("--usessl")) {
            int argIndex = argList.indexOf("--usessl");
            boolean useSSL = Boolean.parseBoolean(argList.get(argIndex + 1));
            System.out.println("loading custom --usessl == " + useSSL);
            settings.setUseSSL(useSSL);
        }
        if (argList.contains("--cacertpath")) {
            int argIndex = argList.indexOf("--cacertpath");
            String caCertPath = argList.get(argIndex + 1);
            System.out.println("loading custom --cacertpath == " + caCertPath);
            settings.setCaCertPath(caCertPath);
        }
        if (argList.contains("--cacertpassword")) {
            int argIndex = argList.indexOf("--cacertpassword");
            String caCertPassword = argList.get(argIndex + 1);
            System.out.println("loading custom --cacertpassword == " + caCertPassword);
            settings.setCaCertPassword(caCertPassword);
        }
        if (argList.contains("--usercertpath")) {
            int argIndex = argList.indexOf("--usercertpath");
            String userCertPath = argList.get(argIndex + 1);
            System.out.println("loading custom --usercertpath == " + userCertPath);
            settings.setUserCertPath(userCertPath);
        }
        if (argList.contains("--usercertpass")) {
            int argIndex = argList.indexOf("--usercertpass");
            String userCertPassword = argList.get(argIndex + 1);
            System.out.println("loading custom --usercertpass == " + userCertPassword);
            settings.setUserCertPassword(userCertPassword);
        }
        if (argList.contains("--maxconnections")) {
            int argIndex = argList.indexOf("--maxconnections");
            settings.setMaxConnections(Integer.parseInt(argList.get(argIndex + 1)));
        }
        return settings;
    }

    //connection establishment
    static UnifiedJedis initRedisConnection(String[] args){
        boolean isFailover = false;
        int maxConnections = 10;
        JedisConnectionHelperSettings settings = new JedisConnectionHelperSettings();
        JedisConnectionHelperSettings settings2 = null; // in case we are failing over
        settings = assignArgsToSettings(settings,args);

        ArrayList<String> argList = new ArrayList<>(Arrays.asList(args));
        // when turning on failover add --failover true
        if (argList.contains("--failover")) {
            int argIndex = argList.indexOf("--failover");
            isFailover = Boolean.parseBoolean(argList.get(argIndex + 1));
        }
        //setting statSettings to the first settings object in case we are not using two sets:
        statSettings=settings;
        if (isFailover){
            settings2 = new JedisConnectionHelperSettings();
            settings2 = assignArgsToSettings(settings2,args);
            if (argList.contains("--host2")) {
                int argIndex = argList.indexOf("--host2");
                settings2.setRedisHost(argList.get(argIndex + 1));
            }
            if (argList.contains("--port2")) {
                int argIndex = argList.indexOf("--port2");
                settings2.setRedisPort(Integer.parseInt(argList.get(argIndex + 1)));
            }
            if (argList.contains("--user2")) {
                int argIndex = argList.indexOf("--user2");
                settings2.setUserName(argList.get(argIndex + 1));
            }
            if (argList.contains("--password2")) {
                int argIndex = argList.indexOf("--password2");
                settings2.setPassword(argList.get(argIndex + 1));
                settings2.setUsePassword(true);
            }
            boolean timeBasedFailover = false;
            if (argList.contains("--timebasedfailover")) {
                int argIndex = argList.indexOf("--timebasedfailover");
                timeBasedFailover = Boolean.parseBoolean(argList.get(argIndex + 1));
            }

            JedisConnectionHelper failoverHelper = null;
            try{
                // only use a single connection based on the hostname (not ipaddress) if possible
                failoverHelper = new JedisConnectionHelper(settings,settings2, timeBasedFailover);
            }catch(Throwable t){
                t.printStackTrace();
                try{
                    Thread.sleep(4000);
                }catch(InterruptedException ie){}
                // give it another go - in case the first attempt was just unlucky:
                // only use a single connection based on the hostname (not ipaddress) if possible
                failoverHelper = new JedisConnectionHelper(settings,settings2, timeBasedFailover);
            }
            return failoverHelper.getUnifiedJedis();
        }
        else {
            JedisConnectionHelper connectionHelper = null;
            try{
                // only use a single connection based on the hostname (not ipaddress) if possible
                connectionHelper = new JedisConnectionHelper(settings);
            }catch(Throwable t){
                t.printStackTrace();
                try{
                    Thread.sleep(4000);
                }catch(InterruptedException ie){}
                // give it another go - in case the first attempt was just unlucky:
                // only use a single connection based on the hostname (not ipaddress) if possible
                connectionHelper = new JedisConnectionHelper(settings);
            }
            return (UnifiedJedis) connectionHelper.getPooledJedis();
        }
    }

    static JedisCluster getJedisClusterConnection(String[] args){
        String host = "redis-12000.re-cluster2.ps-redislabs.org";
        int port = 12000;
        int redirections = 5;

        ArrayList<String> argList = new ArrayList<>(Arrays.asList(args));
        if (argList.contains("--clusterhost")) {
            int argIndex = argList.indexOf("--clusterhost");
            host=(argList.get(argIndex + 1));
        }
        if (argList.contains("--clusterport")) {
            int argIndex = argList.indexOf("--clusterport");
            port=(Integer.parseInt(argList.get(argIndex + 1)));
        }
        if (argList.contains("--ossredirections")) {
            int argIndex = argList.indexOf("--ossredirections");
            redirections=(Integer.parseInt(argList.get(argIndex + 1)));
        }

        HostAndPort hostAndport = new HostAndPort(host,port);
        // Attempt to use same pool settings as the statSettings for closer comparison:
        //ConnectionPoolConfig statConfig = new ConnectionPoolConfig();
        JedisConnectionHelperSettings bs = new JedisConnectionHelperSettings();
        bs = assignArgsToSettings(bs,args);
        GenericObjectPoolConfig<Connection> poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxIdle(bs.getPoolMaxIdle());
        poolConfig.setMaxTotal(bs.getMaxConnections());
        poolConfig.setMinIdle(bs.getPoolMinIdle());
        poolConfig.setMaxWait(Duration.ofMinutes(bs.getNumberOfMinutesForWaitDuration()));
        poolConfig.setTestOnCreate(bs.isTestOnCreate());
        poolConfig.setTestOnBorrow(bs.isTestOnBorrow());
        poolConfig.setTestWhileIdle(bs.isTestWhileIdle());
        poolConfig.setTestOnReturn(bs.isTestOnReturn());
        poolConfig.setNumTestsPerEvictionRun(bs.getNumTestsPerEvictionRun());
        poolConfig.setBlockWhenExhausted(bs.isBlockWhenExhausted());
        poolConfig.setMinEvictableIdleTime(Duration.ofMillis(bs.getMinEvictableIdleTimeMilliseconds()));
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofMillis(bs.getTimeBetweenEvictionRunsMilliseconds()));


        DefaultJedisClientConfig cConfig=null;
        if (argList.contains("--password")){
            cConfig = DefaultJedisClientConfig.builder().password(bs.getPassword()).build();
        }else{
            cConfig = DefaultJedisClientConfig.builder().build();
        }
        JedisCluster jc = new JedisCluster(hostAndport, cConfig, redirections,
                poolConfig);
        System.out.println("***>  This many nodes in this cluster: "+jc.getClusterNodes().size());
        for(String item : jc.getClusterNodes().keySet()) {
            System.out.println("***>  "+item);
        }

        return jc;
    }

    /**
     * Used when you want to send a batch of commands to the Redis Server
     * @return Pipeline
     */
    public Pipeline getPipeline(){
        return  new Pipeline(jedisPooled.getPool().getResource());
    }


    /**
     * Assuming use of Jedis 4.3.1:
     * https://github.com/redis/jedis/blob/82f286b4d1441cf15e32cc629c66b5c9caa0f286/src/main/java/redis/clients/jedis/Transaction.java#L22-L23
     * @return Transaction
     */
    public Transaction getTransaction(){
        return new Transaction(getPooledJedis().getPool().getResource());
    }

    /**
     * Obtain the default object used to perform Redis commands
     * @return JedisPooled
     */
    public JedisPooled getPooledJedis(){
        return jedisPooled;
    }

    /**
     * Obtain the default object used to perform Redis commands
     * @return UnifiedJedis
     */
    public UnifiedJedis getUnifiedJedis(){
        return unifiedJedis;
    }

    /**
     * Use this to build the URI expected in this classes' Constructor
     * @param host
     * @param port
     * @param username
     * @param password
     * @return
     */
    public static URI buildURI(String host, int port, String username, String password){
        URI uri = null;
        try {
            if (!("".equalsIgnoreCase(password))) {
                uri = new URI("redis://" + username + ":" + password + "@" + host + ":" + port);
            } else {
                uri = new URI("redis://" + host + ":" + port);
            }
        } catch (URISyntaxException use) {
            use.printStackTrace();
            System.exit(1);
        }
        return uri;
    }

    private static SSLSocketFactory createSslSocketFactory(
            String caCertPath, String caCertPassword, String userCertPath, String userCertPassword)
            throws IOException, GeneralSecurityException {

        KeyStore keyStore = KeyStore.getInstance("pkcs12");
        keyStore.load(new FileInputStream(userCertPath), userCertPassword.toCharArray());

        KeyStore trustStore = KeyStore.getInstance("jks");
        trustStore.load(new FileInputStream(caCertPath), caCertPassword.toCharArray());

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X509");
        trustManagerFactory.init(trustStore);

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("PKIX");
        keyManagerFactory.init(keyStore, userCertPassword.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);

        return sslContext.getSocketFactory();
    }

    public JedisConnectionHelper(JedisConnectionHelperSettings bs,
                                 JedisConnectionHelperSettings bs2, boolean timeBasedFailover) {
        System.out.println("Creating JedisConnectionHelper for failover with "+bs+" \nand\n "+bs2);
        // no SSL yet
        JedisClientConfig config = null;
        JedisClientConfig config2 = null;
        if(bs.isUsePassword()){
            config = DefaultJedisClientConfig.builder().user(bs.getUserName()).password(bs.getPassword()).build();
            config2 = DefaultJedisClientConfig.builder().user(bs2.getUserName()).password(bs2.getPassword()).build();
        }else{
            config = DefaultJedisClientConfig.builder().user(bs.getUserName()).build();
            config2 = DefaultJedisClientConfig.builder().user(bs2.getUserName()).build();
        }
        redis.clients.jedis.MultiClusterClientConfig.ClusterConfig[] clientConfigs = new redis.clients.jedis.MultiClusterClientConfig.ClusterConfig[2];
        clientConfigs[0] = new redis.clients.jedis.MultiClusterClientConfig.ClusterConfig(new HostAndPort(bs.getRedisHost(), bs.getRedisPort()), config);
        clientConfigs[1] = new redis.clients.jedis.MultiClusterClientConfig.ClusterConfig(new HostAndPort(bs2.getRedisHost(), bs2.getRedisPort()), config2);

        Builder builder = new Builder(clientConfigs);

        if (timeBasedFailover) {
            builder.circuitBreakerSlidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);
            builder.circuitBreakerSlidingWindowSize(1); // SLIDING WINDOW SIZE IN SECONDS
            builder.circuitBreakerSlidingWindowMinCalls(1);
            builder.circuitBreakerFailureRateThreshold(10.0f); // percentage of failures to trigger circuit breaker
        } else {
            builder.circuitBreakerSlidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
            builder.circuitBreakerSlidingWindowSize(10); // SLIDING WINDOW SIZE IN CALLS
            builder.circuitBreakerSlidingWindowMinCalls(1);
            builder.circuitBreakerFailureRateThreshold(10.0f); // percentage of failures to trigger circuit breaker
        }

        java.util.List<java.lang.Class> circuitBreakerList = new ArrayList<java.lang.Class>();
        circuitBreakerList.add(JedisConnectionException.class);
        //circuitBreakerList.add(JedisDataException.class);
        //circuitBreakerList.add(java.net.SocketTimeoutException.class);
        builder.circuitBreakerIncludedExceptionList(circuitBreakerList);
        builder.retryWaitDuration(10);
        builder.retryMaxAttempts(1); //edited by Owen back to 1 Aug 8 8:39 AM Central
        builder.retryWaitDurationExponentialBackoffMultiplier(1);

        MultiClusterPooledConnectionProvider provider = new MultiClusterPooledConnectionProvider(builder.build());
        FailoverReporter reporter = new FailoverReporter();
        provider.setClusterFailoverPostProcessor(reporter);
        provider.setActiveMultiClusterIndex(1);

        this.unifiedJedis = new UnifiedJedis(provider);

        this.connectionProvider = null;
        this.jedisPooled = null;
    }



    public JedisConnectionHelper(JedisConnectionHelperSettings bs){
        System.out.println("Creating JedisConnectionHelper with "+bs);
        URI uri = buildURI(bs.getRedisHost(), bs.getRedisPort(), bs.getUserName(),bs.getPassword());
        HostAndPort address = new HostAndPort(uri.getHost(), uri.getPort());
        JedisClientConfig clientConfig = null;
        if(bs.isUsePassword()){
            String user = uri.getAuthority().split(":")[0];
            String password = uri.getAuthority().split(":")[1];
            password = password.split("@")[0];
            System.out.println("\n\nUsing user: "+user+" / password l!3*^rs@"+password);
            clientConfig = DefaultJedisClientConfig.builder().user(user).password(password)
                    .connectionTimeoutMillis(bs.getConnectionTimeoutMillis()).socketTimeoutMillis(bs.getRequestTimeoutMillis()).build(); // timeout and client settings

        }
        else {
            clientConfig = DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(bs.getConnectionTimeoutMillis()).socketTimeoutMillis(bs.getRequestTimeoutMillis()).build(); // timeout and client settings
        }
        if(bs.isUseSSL()){ // manage client-side certificates to allow SSL handshake for connections
            SSLSocketFactory sslFactory = null;
            try{
                sslFactory = createSslSocketFactory(
                        bs.getCaCertPath(),
                        bs.getCaCertPassword(), // use the password you specified for keytool command
                        bs.getUserCertPath(),
                        bs.getUserCertPassword() // use the password you specified for openssl command
                );
            }catch(Throwable sslStuff){
                sslStuff.printStackTrace();
                System.exit(1);
            }
            clientConfig = DefaultJedisClientConfig.builder().user(bs.getUserName()).password(bs.getPassword())
                    .connectionTimeoutMillis(bs.getConnectionTimeoutMillis()).socketTimeoutMillis(bs.getRequestTimeoutMillis())
                    .sslSocketFactory(sslFactory) // key/trust details
                    .ssl(true).build();
        }
        GenericObjectPoolConfig<Connection> poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxIdle(bs.getPoolMaxIdle());
        poolConfig.setMaxTotal(bs.getMaxConnections());
        poolConfig.setMinIdle(bs.getPoolMinIdle());
        poolConfig.setMaxWait(Duration.ofMinutes(bs.getNumberOfMinutesForWaitDuration()));
        poolConfig.setTestOnCreate(bs.isTestOnCreate());
        poolConfig.setTestOnBorrow(bs.isTestOnBorrow());
        poolConfig.setTestWhileIdle(bs.isTestWhileIdle());
        poolConfig.setTestOnReturn(bs.isTestOnReturn());
        poolConfig.setNumTestsPerEvictionRun(bs.getNumTestsPerEvictionRun());
        poolConfig.setBlockWhenExhausted(bs.isBlockWhenExhausted());
        poolConfig.setMinEvictableIdleTime(Duration.ofMillis(bs.getMinEvictableIdleTimeMilliseconds()));
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofMillis(bs.getTimeBetweenEvictionRunsMilliseconds()));

        this.connectionProvider = new PooledConnectionProvider(new ConnectionFactory(address, clientConfig), poolConfig);
        this.jedisPooled = new JedisPooled(connectionProvider);
        this.unifiedJedis = null;
    }
}

class JedisConnectionHelperSettings {
    private String redisHost = "FIXME";
    private int redisPort = 6379;
    private String userName = "default";
    private String password = "";
    private int maxConnections = 10; // these are best shared
    private int connectionTimeoutMillis = 1000;
    private int requestTimeoutMillis = 200;
    private int poolMaxIdle = 5;
    private int poolMinIdle = 0;
    private int numberOfMinutesForWaitDuration = 1;
    private boolean testOnCreate = true;
    private boolean testOnBorrow = true;
    private boolean testOnReturn = true;
    private boolean testWhileIdle = true;
    private int numTestsPerEvictionRun = 3;
    private boolean useSSL = false;
    private boolean usePassword = false;
    private long minEvictableIdleTimeMilliseconds = 30000;
    private long timeBetweenEvictionRunsMilliseconds = 1000;
    private boolean blockWhenExhausted = true;
    private String trustStoreFilePath = "";
    private String trustStoreType = "";
    private String caCertPath = "./truststore.jks";
    private String caCertPassword = "FIXME";
    private String userCertPath = "./redis-user-keystore.p12";
    private String userCertPassword = "FIXME";

    public String getRedisHost() {
        return redisHost;
    }

    public void setRedisHost(String redisHost) {
        this.redisHost = redisHost;
    }

    public int getRedisPort() {
        return redisPort;
    }

    public void setRedisPort(int redisPort) {
        this.redisPort = redisPort;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public void setMaxConnectionsWithDerivedMaxMinIdleSideEffects(int maxConnections) {
        this.maxConnections = maxConnections;
        this.setPoolMaxIdle(Math.round(maxConnections/2));
        this.setPoolMinIdle(Math.round(maxConnections/10));
    }

    public int getConnectionTimeoutMillis() {
        return connectionTimeoutMillis;
    }

    public void setConnectionTimeoutMillis(int connectionTimeoutMillis) {
        this.connectionTimeoutMillis = connectionTimeoutMillis;
    }

    public int getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    public void setRequestTimeoutMillis(int requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    public int getPoolMaxIdle() {
        return poolMaxIdle;
    }

    public void setPoolMaxIdle(int poolMaxIdle) {
        this.poolMaxIdle = poolMaxIdle;
    }

    public int getPoolMinIdle() {
        return poolMinIdle;
    }

    public void setPoolMinIdle(int poolMinIdle) {
        this.poolMinIdle = poolMinIdle;
    }

    public int getNumberOfMinutesForWaitDuration() {
        return numberOfMinutesForWaitDuration;
    }

    public void setNumberOfMinutesForWaitDuration(int numberOfMinutesForWaitDuration) {
        this.numberOfMinutesForWaitDuration = numberOfMinutesForWaitDuration;
    }

    public boolean isTestWhileIdle() {
        return testWhileIdle;
    }

    public void setTestWhileIdle(boolean testWhileIdle) {
        this.testWhileIdle = testWhileIdle;
    }

    public boolean isTestOnCreate() {
        return testOnCreate;
    }

    public void setTestOnCreate(boolean testOnCreate) {
        this.testOnCreate = testOnCreate;
    }

    public boolean isTestOnBorrow() {
        return testOnBorrow;
    }

    public void setTestOnBorrow(boolean testOnBorrow) {
        this.testOnBorrow = testOnBorrow;
    }

    public boolean isTestOnReturn() {
        return testOnReturn;
    }

    public void setTestOnReturn(boolean testOnReturn) {
        this.testOnReturn = testOnReturn;
    }

    public int getNumTestsPerEvictionRun() {
        return numTestsPerEvictionRun;
    }

    public void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
        this.numTestsPerEvictionRun = numTestsPerEvictionRun;
    }

    public boolean isUseSSL() {
        return useSSL;
    }

    public void setUseSSL(boolean useSSL) {
        this.useSSL = useSSL;
    }

    public boolean isUsePassword() {
        return usePassword;
    }

    public void setUsePassword(boolean usePassword) {
        this.usePassword = usePassword;
    }

    public long getMinEvictableIdleTimeMilliseconds() {
        return minEvictableIdleTimeMilliseconds;
    }

    public void setMinEvictableIdleTimeMilliseconds(long minEvictableIdleTimeMilliseconds) {
        this.minEvictableIdleTimeMilliseconds = minEvictableIdleTimeMilliseconds;
    }

    public long getTimeBetweenEvictionRunsMilliseconds() {
        return timeBetweenEvictionRunsMilliseconds;
    }

    public void setTimeBetweenEvictionRunsMilliseconds(long timeBetweenEvictionRunsMilliseconds) {
        this.timeBetweenEvictionRunsMilliseconds = timeBetweenEvictionRunsMilliseconds;
    }

    public boolean isBlockWhenExhausted() {
        return blockWhenExhausted;
    }

    public void setBlockWhenExhausted(boolean blockWhenExhausted) {
        this.blockWhenExhausted = blockWhenExhausted;
    }

    public String toString(){
        return "\nRedisUserName = "+getUserName()+"\nUsePassword = "+isUsePassword()+"\nUseSSL = "+isUseSSL()+ "\nRedisHost = "+getRedisHost()+
                "\nRedisPort = "+getRedisPort()+"\nMaxConnections = "+getMaxConnections()+
                "\nRequestTimeoutMilliseconds = "+getRequestTimeoutMillis()+"\nConnectionTimeOutMilliseconds = "+
                getConnectionTimeoutMillis()+"\ntestOnCreate = "+isTestOnCreate()+"\ntestOnBorrow = "+isTestOnBorrow()+
                "\ntestOnReturn = "+isTestOnReturn()+"\nblockWhenExhausted = "+isBlockWhenExhausted()
                +"\nminIdleConnections = "+getPoolMinIdle()
                +"\ntimeBetweenEvictionRunsMilliseconds = "+getTimeBetweenEvictionRunsMilliseconds()
                +"\nnumberEvictionTests = "+getNumTestsPerEvictionRun()
                +"\nminEvictableIdleTimeMilliseconds = "+getMinEvictableIdleTimeMilliseconds();
    }

    public String getTrustStoreFilePath() {
        return trustStoreFilePath;
    }

    public void setTrustStoreFilePath(String trustStoreFilePath) {
        this.trustStoreFilePath = trustStoreFilePath;
    }

    public String getTrustStoreType() {
        return trustStoreType;
    }

    public void setTrustStoreType(String trustStoreType) {
        this.trustStoreType = trustStoreType;
    }

    public String getCaCertPath() {
        return caCertPath;
    }

    public void setCaCertPath(String caCertPath) {
        this.caCertPath = caCertPath;
    }

    public String getCaCertPassword() {
        return caCertPassword;
    }

    public void setCaCertPassword(String caCertPassword) {
        this.caCertPassword = caCertPassword;
    }

    public String getUserCertPath() {
        return userCertPath;
    }

    public void setUserCertPath(String userCertPath) {
        this.userCertPath = userCertPath;
    }

    public String getUserCertPassword() {
        return userCertPassword;
    }

    public void setUserCertPassword(String userCertPassword) {
        this.userCertPassword = userCertPassword;
    }
}


class FailoverReporter implements Consumer<String> {
    String currentClusterName = "not set";

    public String getCurrentClusterName(){
        return currentClusterName;
    }

    @Override
    public void accept(String clusterName) {
        this.currentClusterName=clusterName;
        System.out.println("<< FailoverReporter >>\nJedis failover to cluster: " + clusterName+"\n<< FailoverReporter >>");
    }
}