# redis-proxy

## How to run?
```
git clone https://github.com/nikhillopes/redis-proxy.git
cd redis-proxy
make test
```

## Caching Algorithm
The cache is a hash map with linked list between the nodes similar to LinkedHashMap in java,
in addition to that it also maintains a heap for key eviction on expiry.\
Eviction for both expiry and least recently used is lazy and only done when we need space in the cache or expired key is accessed.


Time Complexity:
- GET : O(1)
- SET : O(k) + log(n)
  - k = number of expired keys till a non expired key id found in eviction heap (Only when space is needed).
  - n = number of keys in eviction heap.
  
Space Complexity: O(n)
  - n = cache capacity
  
## Proxy Cache Implementation
The proxy maintains cache objects equal to the number of parallelism specified.\
Keys are assigned to the specific cache based on hash, specifically we're partitioning the key space to
obtain better multi threading performance. Additionally each cache also has a lock, which the accessing thread must use to read or write to the cache.

## Proxy to Client Interface
Clients query the proxy using redis protocol simple strings and proxy replies with the same.\
Supported commands : 
 - GET KEY
 - PING
 - EXIT
 
## The Test
Running ```make test``` will launch a redis instance, the proxy and a multi threaded client for rapid querying.

Each task in the tester will perform one of the following operations :
- Write a key and value to Jedis, bypassing the proxy
- Get a value from the proxy
- Update a key in redis

The proxy parallelism, proxy capacity, key expiry, querying rate, number of tasks, clients threads can be modified by changing environment variables in buildAndRunTests.sh.

The logs for the tester is at ```run/tester.log``` and for the proxy is at ```proxy.log```.

At the end of the test the make command will display stats for the tests, like number of queries, number of cache hits and missed. Stats from the tester a also displayed.

## Time Spent
- Understand requirements and coming up with design : 1 hr
- Cache Implementation : 30 mins
- Proxy Implementation single thread : 1 hr
- Proxy Implementation finalize : 1 hr
- Redis protocol using simple strings : 20 mins
- Try redis protocol with bulk string and bytes : 1 hr
- Write simple test org.n.test.CacheTester to test lru and expiry eviction : 10 mins
- Write multi threaded tester : 45 mins
- Dockerize : 1 hr
- Other optimizations and tinker with different parallelism, expiry etc. : 4 hrs
- Documentation : 30 mins

## Improvements
- Get a signal from master(redis) for key update and key deletion. I think this will significantly reduce return of stale values. 



