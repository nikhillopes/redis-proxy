package org.n.test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.n.proxy.RedisProto;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Slf4j
public class MultiThreadedRapidTester {

  private InetSocketAddress proxyAddress;
  private InetSocketAddress redisAddress;

  private Map<String, String> store;

  private int GETyay, GETnay, SET;

  private Lock lock;

  private JedisPool jedisPool;

  private long sleepTimeAfterUpdate;
  private int parallelism;
  private int numberOfOperations;
  private boolean sleepAfterUpdate;
  private int maxKeys;
  private int numOfKeys;

  public MultiThreadedRapidTester(InetSocketAddress proxyAddress,
      InetSocketAddress redisAddress,
      int parallel,
      int numberOfOperations,
      long sleepTimeAfterUpdate,
      boolean sleepAfterUpdate,
      int maxKeys) {

    this.proxyAddress = proxyAddress;
    this.redisAddress = redisAddress;

    this.jedisPool = new JedisPool(
        new JedisPoolConfig(),
        redisAddress.getHostName(),
        redisAddress.getPort());

    this.store = new ConcurrentHashMap<>(maxKeys);

    this.lock = new ReentrantLock();

    this.sleepTimeAfterUpdate = sleepTimeAfterUpdate;

    this.parallelism = parallel;

    this.numberOfOperations = numberOfOperations;

    this.sleepAfterUpdate = sleepAfterUpdate;

    this.maxKeys = maxKeys;
  }

  public static void main(String[] args) throws InterruptedException, UnknownHostException {

    //proxy hostname:port
    String proxyHost = System.getenv().getOrDefault("PROXY_HOST", "127.0.0.2");
    String proxyPort = System.getenv().getOrDefault("PROXY_PORT", "55555");

    //redis hostname:port
    String redisHost = System.getenv().getOrDefault("REDIS_HOST", "172.18.0.2");
    String redisPort = System.getenv().getOrDefault("REDIS_PORT", "6379");

    //capacity of the cache
    int numberOfOperations = Integer.valueOf(System.getenv().getOrDefault("TEST_OPS", "5000"));

    //sleepTimeAfterUpdate of stored keys in ms
    long sleepTimeAfterUpdate = Long
        .valueOf(System.getenv().getOrDefault("TEST_SLEEP_TIME", "100000"));

    //number of parallel requests
    int parallelism = Integer.valueOf(System.getenv().getOrDefault("TEST_PARALLELISM", "13"));

    int maxKeys = Integer.valueOf(System.getenv().getOrDefault("TEST_KEYS", "3000"));

    log.info("proxy " + proxyHost + ":" + proxyPort);
    log.info("redis " + redisHost + ":" + redisPort);
    log.info("numberOfOperations " + numberOfOperations);
    log.info("sleepTimeAfterUpdate " + sleepTimeAfterUpdate);
    log.info("parallelism " + parallelism);
    log.info("sleepAfterUpdate " + (sleepTimeAfterUpdate > 0));

    InetSocketAddress proxyAddress = new InetSocketAddress(
        InetAddress.getByName(proxyHost), Integer.valueOf(proxyPort)
    );

    InetSocketAddress redisInstanceAddress = new InetSocketAddress(
        InetAddress.getByName(redisHost), Integer.valueOf(redisPort)
    );

    MultiThreadedRapidTester multiThreadedRapidTester = new MultiThreadedRapidTester(
        proxyAddress,
        redisInstanceAddress,
        parallelism,
        numberOfOperations,
        sleepTimeAfterUpdate,
        (sleepTimeAfterUpdate > 0),
        maxKeys);

    multiThreadedRapidTester.start();

    log.info("END");

  }

  private void start() throws InterruptedException {

    ExecutorService threadPool = Executors.newFixedThreadPool(parallelism);

    int id = 0;
    while (id < numberOfOperations) {

      int opTypeInt = ThreadLocalRandom.current().nextInt(10);
      //0-2 SET
      //3-3 UPDATE
      //4-8 UPDATE

      OP_TYPE opType;

      if (numOfKeys < parallelism) {
        opTypeInt = 0;
      } else if (numOfKeys >= maxKeys) {
        opTypeInt = ThreadLocalRandom.current().nextInt(3, 10);
      }

      if (opTypeInt <= 2) {
        opType = OP_TYPE.SET;
        numOfKeys++;
      } else if (opTypeInt <= 3) {
        opType = OP_TYPE.UPDATE;
      } else if (opTypeInt <= 9) {
        opType = OP_TYPE.GET;
      } else {
        //should not get here
        opType = null;
      }

      threadPool.submit(new Worker(id++, opType));
      if (sleepAfterUpdate) {
        Thread.sleep(ThreadLocalRandom.current().nextLong(0L, sleepTimeAfterUpdate * 2));
      }
    }
    threadPool.shutdown();
    threadPool.awaitTermination(Integer.MAX_VALUE, TimeUnit.MINUTES);

    lock.lock();
    log.info("SET : " + SET);
    log.info("GET yay : " + GETyay + " nay : " + GETnay);
    lock.unlock();
  }

  class Worker implements Runnable {

    int id;
    OP_TYPE opType;

    Worker(int id, OP_TYPE opType) {
      this.id = id;
      this.opType = opType;
    }

    @Override
    public void run() {

      log.info(this.id + "queryOrWrite : " + opType);

      if (opType == OP_TYPE.SET || opType == OP_TYPE.UPDATE) {
        //write or update

        String key;
        String value = UUID.randomUUID().toString();

        boolean update = opType == OP_TYPE.UPDATE;
        Map.Entry<String, String> randomEntry = null;

        if (update) {
          randomEntry = getRandomEntry();
          key = randomEntry.getKey();
        } else {
          key = UUID.randomUUID().toString();
        }

        try (Jedis jedis = jedisPool.getResource()) {
          jedis.set(key, value);
        }

        store.put(key, value);
        if (update) {
          log.info(this.id + "UPDATED " + randomEntry + " = " + value);
        } else {
          log.info(this.id + "SET " + key + " = " + value);
        }

        lock.lock();
        SET++;
        lock.unlock();

        return;

      }

      Socket client = new Socket();
      try {
        client.connect(proxyAddress);
      } catch (IOException e) {
        e.printStackTrace();
      }

      try (

          BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream()));
          BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()))
      ) {

        String toSend = RedisProto.encodeSimpleString("PING");
        log.info(this.id + "Sending : " + toSend.replaceAll(RedisProto.CRLF, "CRLF"));
        bw.write(toSend);
        bw.flush();
        String reply = br.readLine() + RedisProto.CRLF;
        log.info(this.id + "GOT : " + reply.replaceAll(RedisProto.CRLF, "CRLF"));

        if (opType == OP_TYPE.GET) {
          //Query random key in store
          Map.Entry<String, String> randomEntry = getRandomEntry();

          log.info(this.id + "Querying + " + randomEntry);

          toSend = RedisProto.encodeSimpleString("GET " + randomEntry.getKey());
          log.info(this.id + "Sending : " + toSend.replaceAll(RedisProto.CRLF, "CRLF"));
          bw.write(toSend);
          bw.flush();
          reply = br.readLine() + RedisProto.CRLF;
          log.info(this.id + "GET " + randomEntry.getKey() + "GOT : " + reply
              .replaceAll(RedisProto.CRLF, "CRLF"));

          if (RedisProto.decode(reply).equals(randomEntry.getValue())) {

            lock.lock();
            GETyay++;
            lock.unlock();
            log.info(this.id + "YAY" +
                " GET " + randomEntry.getKey() +
                " GOT : " + reply.replaceAll(RedisProto.CRLF, "CRLF") +
                " ACT " + randomEntry.getValue());
          } else {

            lock.lock();
            GETnay++;
            lock.unlock();
            log.info(this.id + "NAY" +
                " GET " + randomEntry.getKey() +
                " GOT : " + reply.replaceAll(RedisProto.CRLF, "CRLF") +
                " ACT " + randomEntry.getValue());

          }

        }

        toSend = RedisProto.encodeSimpleString("EXIT");
        log.info(this.id + "Sending : " + toSend.replaceAll(RedisProto.CRLF, "CRLF"));
        bw.write(toSend);
        bw.flush();
        reply = br.readLine() + RedisProto.CRLF;
        log.info(this.id + "GOT : " + reply.replaceAll(RedisProto.CRLF, "CRLF"));

      } catch (IOException e1) {

        log.error(id + "failed", e1);
      }
    }

    private Map.Entry<String, String> getRandomEntry() {
      List<Map.Entry<String, String>> storeList = new ArrayList<>(store.entrySet());
      Collections.shuffle(storeList);

      return storeList.get(ThreadLocalRandom.current().nextInt(storeList.size()));
    }
  }

  private enum OP_TYPE {
    GET,
    SET,
    UPDATE
  }
}
