package com.redislabs.sa.ot.aajf;
import redis.clients.jedis.*;

public class LittleLua{

    public void playWithSortedSets(UnifiedJedis connection){
        //public ZRangeParams(double min, double max) <-- byscore is implicit with this constructor
        double min = 0; double max = 5000;
        redis.clients.jedis.params.ZRangeParams params = null;
        long opssCounter = 0;
        long targetOppsCount = 200;
        long startTime = System.currentTimeMillis();

        for (int x = 1; x<(targetOppsCount+1); x++){
            try{
                min = x;
                params = new redis.clients.jedis.params.ZRangeParams(min,max);
                connection.zadd(connection+":key",x,connection+":"+x);
                //System.out.println( );
                connection.zrange(connection+":key",params);
                safeIncrement(connection,"testIncrString","1",""+System.nanoTime());
                safeIncrement(connection,"testIncrString","2",""+System.nanoTime());
                opssCounter++;
                // throw a DataException to cause failover See lines 353-357 or so where DataException is added
                /* you may not want that behavior...
                if(opssCounter==1000){
                    connection.set("x", "y");
                    connection.incr("x");
                }*/
            }catch(Throwable ste){
                ste.printStackTrace();
                try {
                    Thread.sleep(2000);
                } catch(Throwable t) {
                    t.printStackTrace();
                }
            }
        }
        System.out.println("\n\nTime taken to execute "+opssCounter + " lua calls was "+((System.currentTimeMillis()-startTime)/1000)+" seconds");
    }

    public void safeIncrement(UnifiedJedis jedis,String stringKeyName, String routingValue, String uuid) {
        //SortedSet API offers ZCARD and ZCOUNT:
        //stringKeyName is the String being incremented
        //routingValue is the value added to the string keyname to route it to a slot in redis
        // a co-located SortedSet key is derived from that string keyname and routingValue
        //if string keyname is bob and routingValue is 1 sortedSet keyname is z:bob{1}
        //args to script are:
        //routingValue, (used to route execution of the script to a shard)
        //stringKeyName,
        //uuid for current update attempt,
        //incr_amnt (in case we don't just want to add a single integer to the string counter)
        //this script removed any entries stored in the SortedSet that are older than
        //current time in seconds - 100 seconds
        String luaScript =
                "local stringKeyNameWithRouting = ARGV[1]..'{'..KEYS[1]..'}' "+
                        "local ssname = 'z:'..stringKeyNameWithRouting "+
                        "local uuid = ARGV[2] "+
                        "local incr_amnt = ARGV[3] "+
                        "local ts_score = redis.call('TIME')[1] "+
                        "local result = 'duplicate [fail]' "+
                        "if redis.call('ZINCRBY',ssname,ts_score,uuid) == ts_score then "+
                        "redis.call('incrby',stringKeyNameWithRouting,incr_amnt) "+
                        "redis.call('ZREMRANGEBYRANK', ssname, (ts_score-100), 0) "+
                        "result = 'success' end return {ssname,result}";
        long timestamp = System.currentTimeMillis();
        Object luaResponse = jedis.eval(luaScript,1,routingValue,stringKeyName,""+uuid,"100");
        //System.out.println("\nResults from Lua: [SortedSetKeyName] [result]  \n"+luaResponse);
        //System.out.println("\n\nrunning the lua script with dedup and incr logic took "+(System.currentTimeMillis()-timestamp+" milliseconds"));
    }


}