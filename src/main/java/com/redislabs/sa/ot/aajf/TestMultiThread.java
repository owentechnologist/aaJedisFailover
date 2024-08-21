package com.redislabs.sa.ot.aajf;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.PipelineBase;

public class TestMultiThread implements Runnable{
    UnifiedJedis connectionInstance;
    int testThreadNumber = 0;
    int latencyThreshold=5000;//milliseconds as measured round trip from this client
    int numberOfTasks=1; // per thread
    String testType = "default";
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

    public TestMultiThread(UnifiedJedis ufJedis,String testType){
        this.connectionInstance = ufJedis;
        this.testType = testType;
    }

    public static void fireTest(UnifiedJedis ufJedis,int howManyThreads, String testType, int numberOfTasks, int latencyThreshold){
        ufJedis.del("TestMultiThread:"+testType+"Threads:Complete");//cleanup for this test
        long startTime = System.currentTimeMillis();
        System.out.println("\n\tHere come the threads for test: "+testType+"\n");
        for(int x=0;x<howManyThreads;x++){
            System.out.print(x+" ");
            new Thread(new TestMultiThread(ufJedis,testType).setLatencyThreshold(latencyThreshold).setTestThreadNumber(x).setNumberOfTasks(numberOfTasks)).start();
        }
        int completedThreads = 0;
        while(completedThreads < howManyThreads){
            int completedThreadsMark = completedThreads;
            try {
                Thread.sleep(1000);
                if(ufJedis.exists("TestMultiThread:"+testType+"Threads:Complete")){
                    completedThreads = Integer.parseInt(ufJedis.get("TestMultiThread:"+testType+"Threads:Complete"));
                    if(completedThreads>completedThreadsMark){
                        System.out.println(completedThreads+" threads have completed their work...");
                    }
                }
            }catch(Throwable t){
                System.out.println("checking for all threads being complete: "+t.getMessage());
            }
        }
        System.out.println("\n\n*****************\n" +
                testType+":Test took :"+((System.currentTimeMillis()-startTime)/1000)+" seconds" +
                "\n"+testType+":TEST IS COMPLETE... ");
    }

    @Override
    public void run() {
        boolean shouldAnounceDeltaBetweenException = false;
        long exceptionTimeStamp = 0;
        long taskExecutionStartTime = 0;
        for(long x=0;x<numberOfTasks;x++){
            String connectionName = ""+connectionInstance;
            taskExecutionStartTime = System.currentTimeMillis();
            try{
                //connectionInstance.publish("ps:Messages",connectionName+":testThread# "+this.testThreadNumber+" message #"+x);
                String keyName = "tmt:string:"+this.testThreadNumber;
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
                    //System.out.println("pipeline done...");
                }else{
                    for(int t = 0;t<100;t++){
                        connectionInstance.get(keyName);
                    }
                }
                connectionInstance.publish("ps:messages","THREAD "+this.testThreadNumber+" task # "+x+" completed");

                if (shouldAnounceDeltaBetweenException) {
                    //System.out.println("THREAD "+this.testThreadNumber+" First Succesful write after exception delay in millis was...: "+(System.currentTimeMillis()-exceptionTimeStamp));
                    shouldAnounceDeltaBetweenException=false;
                }
                long taskDuration = System.currentTimeMillis()-taskExecutionStartTime;
                if(taskDuration>latencyThreshold){ //allow X milliseconds to limit log/output noise
                    System.out.println("\tTask duration in millis: "+taskDuration);
                }
            }catch(redis.clients.jedis.exceptions.JedisException e){
                //System.out.println("THREAD "+this.testThreadNumber+" CAUGHT: Exception "+e);
                x--; // keep trying to do the next thing
                shouldAnounceDeltaBetweenException=true;
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
        connectionInstance.incr("TestMultiThread:"+this.testType+"Threads:Complete");
    }
}
