package com.redislabs.sa.ot.aajf;
import redis.clients.jedis.*;

public class LittleLua{

    String jsonAsString = "{\"nc:PersonAgeMeasure\": {\"nc:MeasureIntegerValue\": 14,\"nc:TimeUnitCode\": \"ANN\"},\"j:PersonHairColorCode\": \"BRO\",\"nc:PersonName\": {\"nc:PersonGivenName\": \"Mortimer\",\"nc:PersonSurName\": \"Smith\",\"nc:PersonNameSuffixText\": \"Sr\",\"nc:PersonPreferredName\": \"Morty\"}}";
    /**
     Note that in this method two calls are made that report the length of the List used
     This is silly, (only one is needed) but, it allows for the use of Transactions which is a useful bit of instruction.
     */
    public int playWithLists(UnifiedJedis connection,long taskNumber,long testThreadNumber){
        int responseValue = 0;//0 means do not change anything -1 means a retry is necessary
        String sharedListName = "sharedList{"+(taskNumber%100)+"}";
        Transaction t = (Transaction)connection.multi();
        Response<Long> result1 = t.lpush(sharedListName,jsonAsString+""+taskNumber);
        Response<Long> listLength = t.llen(sharedListName);
        t.exec();
        if(listLength.get().longValue()!=result1.get().longValue()){
            System.out.println("\nmismatch of listLength of Redis list: "+listLength.get()+"  "+result1.get());
        }
        java.util.List<String> theList = null;
        try{
            theList=connection.lrange(sharedListName,(listLength.get()-2),listLength.get()+10);
            long listSize = 0;
            if(theList!=null){
                listSize=theList.size();
            }
            String lrangeResponse = theList.get((int)(listSize-1));
        }catch(Exception e){
            System.out.println("***> oops!  Thread# "+testThreadNumber+":  While working with the list key named: "+sharedListName);
            e.printStackTrace();
            System.out.println("The list key named: "+sharedListName+" has a length of: "+connection.llen(sharedListName));
            responseValue=-1;
        }
        t.close(); // returns the underlying connection to the pool
        return responseValue;
    }

    public void playWithSortedSets(UnifiedJedis connection, long x){
        //public ZRangeParams(double min, double max) <-- byscore is implicit with this constructor
        double min = x; double max = 5000000;
        redis.clients.jedis.params.ZRangeParams params = null;
        try{
            params = new redis.clients.jedis.params.ZRangeParams(min,max);
            connection.zadd(connection+":key",x,connection+":"+x);
            connection.zrange(connection+":key",params);
            String routerVal = ""+(x%100);
            luaIncrement(connection,"testIncrString",routerVal,""+System.nanoTime());
        }catch(Throwable ste){
            ste.printStackTrace();
            try {
                Thread.sleep(20);
            } catch(Throwable t) {
                t.printStackTrace();
            }
        }
    }

    public void luaIncrement(UnifiedJedis jedis,String stringKeyName, String routingValue, String uuid) {
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