package com.redislabs.sa.ot.aajf;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.PipelineBase;

public class TestMultiThread implements Runnable{
    UnifiedJedis connectionInstance;
    int testThreadNumber = 0;
    int latencyThreshold=5000;//milliseconds as measured round trip from this client
    int numberOfTasks=1; // per thread
    String testType = "default";
    boolean isUsingLUA = false;
    static volatile boolean exceptionCaught=false;

    public TestMultiThread setTestThreadNumber(int testThreadNumber){
        this.testThreadNumber=testThreadNumber;
        return this;
    }

    public TestMultiThread setLatencyThreshold(int latencyThreshold){
        this.latencyThreshold=latencyThreshold;
        return this;
    }

    public TestMultiThread setNumberOfTasks(int numberOfTasks){
        this.numberOfTasks=numberOfTasks;
        return this;
    }

    public TestMultiThread setIsUsingLUA(boolean isUsingLUA){
        this.isUsingLUA = isUsingLUA;
        return this;
    }

    public TestMultiThread(UnifiedJedis ufJedis,String testType){
        this.connectionInstance = ufJedis;
        this.testType = testType;
    }

    public static void fireTest(UnifiedJedis ufJedis,int howManyThreads, String testType, int numberOfTasks, int latencyThreshold,boolean isUsingLUA){
        ufJedis.del("TestMultiThread:"+testType+"Threads:Complete");//cleanup for this test
        ufJedis.del("Z:TestMultiThread:ExpectedValues:"+testType);//cleanup for this test
        long startTime = System.currentTimeMillis();
        System.out.println("\n\tHere come the threads for test: "+testType+"\n");
        for(int x=0;x<howManyThreads;x++){
            System.out.print(x+" ");
            new Thread(new TestMultiThread(ufJedis,testType).setLatencyThreshold(latencyThreshold).setTestThreadNumber(x).setNumberOfTasks(numberOfTasks).setIsUsingLUA(isUsingLUA)).start();
        }
        int completedThreads = 0;
        while(completedThreads < howManyThreads){
            int completedThreadsMark = completedThreads;
            try {
                Thread.sleep(1000);
                if(ufJedis.exists("TestMultiThread:"+testType+"Threads:Complete")){
                    completedThreads = Integer.parseInt(ufJedis.get("TestMultiThread:"+testType+"Threads:Complete"));
                    if(completedThreads>completedThreadsMark){
                        System.out.println("\n"+completedThreads+" threads have completed their work...");
                    }
                }
            }catch(Throwable t){
                System.out.println("Exception caught while checking for all threads being complete: "+t.getMessage() + "  " + t.getClass().getName());
            }
        }
        System.out.println("\n\n*****************\n" +
                testType+":Test took :"+((System.currentTimeMillis()-startTime)/1000)+" seconds" +
                "\n"+testType+":TEST IS COMPLETE... ");
    }

    @Override
    public void run() {
        if(isUsingLUA){
            LittleLua ll = new LittleLua();
            for(long x=0;x<numberOfTasks;x++){
                //ll.playWithSortedSets(connectionInstance,x);
                int responseValue = ll.playWithLists(connectionInstance,x,this.testThreadNumber);
                x=x+responseValue;//if a failure occured -1 will be returned causing this task to reexecute
            }
            //cleanup with expiry of all keys used:
            for(int d=0;d<100;d++){
                String sKeyName = "testIncrString{"+d+"}";
                String zKeyName = "z:testIncrString{"+d+"}";
                String lKeyName = "sharedList{"+(d%100)+"}";
                connectionInstance.expire(sKeyName,300,redis.clients.jedis.args.ExpiryOption.NX);
                connectionInstance.expire(zKeyName,300,redis.clients.jedis.args.ExpiryOption.NX);
                connectionInstance.expire(lKeyName,300,redis.clients.jedis.args.ExpiryOption.NX);
            }
            connectionInstance.expire(connectionInstance+":key",300,redis.clients.jedis.args.ExpiryOption.NX);

            //announce end of this Thread's work:
            connectionInstance.incr("TestMultiThread:"+this.testType+"Threads:Complete");
        }else{
            long expectedTotalIncrValue = doIncrAndReadStuff();
            String keyName = "tmt:string:"+this.testThreadNumber;
            connectionInstance.incr("TestMultiThread:"+this.testType+"Threads:Complete");
            connectionInstance.zadd("Z:TestMultiThread:ExpectedValues:"+this.testType,Double.parseDouble(expectedTotalIncrValue+""),this.testThreadNumber+":"+connectionInstance.get(keyName));
        }
    }

    long doIncrAndReadStuff(){
        //cleanup old keys to help measure results:
        String keyName = "tmt:string:"+this.testThreadNumber;
        long expectedTotalIncrValue = 0; // update this during test
        try{
            connectionInstance.del(keyName);
        }catch(redis.clients.jedis.exceptions.JedisException jce){
            if(jce.getMessage().equalsIgnoreCase("Could not get a resource from the pool")){
                System.out.println("Thread# "+this.testThreadNumber+" --> Could not get a resource from the pool\n"+
                        "Going to retry that command...");
                connectionInstance.del(keyName);
            }else{
                jce.printStackTrace();
            }
        }

        //NOTE - set this as a startup parameter:
        boolean shouldAnounceDeltaBetweenException = false;
        String lastException = null;

        long exceptionTimeStamp = 0;
        long taskExecutionStartTime = 0;
        for(long x=0;x<numberOfTasks;x++){
            String connectionName = null;
            try{
                connectionName = ""+((JedisPooled)connectionInstance).getPool();
            }catch(java.lang.ClassCastException cce){
                //connectionName = ((JedisCluster)getConnectionFromSlot(0)).toIdentityString();
                connectionName = "ossClusterAPIConnection";
            }
            taskExecutionStartTime = System.currentTimeMillis();
            try{

                //connectionInstance.publish("ps:Messages",connectionName+":testThread# "+this.testThreadNumber+" message #"+x);
                boolean pipelined=false;
                if(x%20==0){
                    pipelined=true;
                }
                if(pipelined){
                    //System.out.println("about to pipeline...");
                    redis.clients.jedis.PipelineBase p =null;
                    try{
                        p = ((JedisPooled)connectionInstance).pipelined();
                    }catch(java.lang.ClassCastException e){
                        p = ((UnifiedJedis)connectionInstance).pipelined();
                    }

                    p.incrBy(keyName, x);
                    p.expire(keyName,300,redis.clients.jedis.args.ExpiryOption.NX);
                    p.sync();
                    p.close();
                    expectedTotalIncrValue=expectedTotalIncrValue+x;
                    //System.out.println("pipeline done...");
                }else{
                    for(int t = 0;t<100;t++){
                        connectionInstance.get(keyName);
                    }
                }
                if(x%10==0){ // reduce the noise...
                    connectionInstance.publish("ps:Messages","THREAD "+this.testThreadNumber+" task # "+x+" completed using "+connectionName);
                }
                if (shouldAnounceDeltaBetweenException) {
                    System.out.println("THREAD "+this.testThreadNumber+" recovered from Exception: ("+lastException+") First Succesful write after exception delay in millis was...: "+(System.currentTimeMillis()-exceptionTimeStamp));
                    shouldAnounceDeltaBetweenException=false;
                }
                long taskDuration = System.currentTimeMillis()-taskExecutionStartTime;
                if(taskDuration>latencyThreshold){ //allow X milliseconds to limit log/output noise
                    System.out.println("\tTask duration in millis: "+taskDuration);
                }
            }catch(redis.clients.jedis.exceptions.JedisException e){
                if(e.getMessage().equalsIgnoreCase("Could not get a resource from the pool")){
                    System.out.println("\nThread# "+this.testThreadNumber+" --> Could not get a resource from the pool\n"+
                            "Going to retry that command...\n");
                    try{
                        connectionInstance.publish("ps:Messages","\n 'Could not get a resource from the pool' \npool now looks like this: \n"+((JedisPooled)connectionInstance).getPool());
                    }catch(Throwable g){
                        connectionInstance.publish("ps:Messages","\nThread# "+this.testThreadNumber+"  'Could not get a resource from the pool' "+connectionName);
                    }
                }else{
                    e.printStackTrace();
                }

                //System.out.println("THREAD "+this.testThreadNumber+" CAUGHT: Exception "+e+"\n"+e.getMessage()+"\nThis Thread will retry the tasks...");
                x--; // keep trying to do the next thing
                shouldAnounceDeltaBetweenException=true;
                lastException=e.getClass().getName()+": "+e.getMessage();
                exceptionTimeStamp=System.currentTimeMillis();
            }
            /*  The following sleep is to give the client VM a rest when running many threads
            try{
                Thread.sleep(20);
            }catch(Throwable t){
                //do nothing
            }*/
            // use the following to throw JedisDataException:
            //connectionInstance.set("tmt:string", "y");
            //connectionInstance.incr("tmt:string");
        }
        return expectedTotalIncrValue;
    }
}
