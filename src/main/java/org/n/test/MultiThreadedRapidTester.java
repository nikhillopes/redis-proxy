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
  private int GETyay, GETnay, UPDyay,  UPDstale, SET;
  private Lock lock;
  private JedisPool jedisPool;
  private long sleepTimeAfterUpdate;
  private int parallelism;
  private int numberOfOperations;
  private boolean sleepAfterUpdate;

  public MultiThreadedRapidTester(InetSocketAddress proxyAddress,
      InetSocketAddress redisAddress,
      int parallel,
      int keys,
      long sleepTimeAfterUpdate,
      boolean sleepAfterUpdate) {

    this.proxyAddress = proxyAddress;
    this.redisAddress = redisAddress;

    this.jedisPool = new JedisPool(
        new JedisPoolConfig(),
        redisAddress.getHostName(),
        redisAddress.getPort());

    this.store = new ConcurrentHashMap<>(keys);

    this.lock = new ReentrantLock();

    this.sleepTimeAfterUpdate = sleepTimeAfterUpdate;

    this.parallelism = parallel;

    this.numberOfOperations = keys;

    this.sleepAfterUpdate = sleepAfterUpdate;
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
    long sleepTimeAfterUpdate = Long.valueOf(System.getenv().getOrDefault("TEST_SLEEP_TIME", "100000"));

    //number of parallel requests
    int parallelism = Integer.valueOf(System.getenv().getOrDefault("TEST_PARALLELISM", "13"));

    boolean sleepAfterUpdate =
        Boolean.valueOf(System.getenv().getOrDefault("TEST_SLEEP", "false"));

    log.info("proxy " + proxyHost + ":" + proxyPort);
    log.info("redis " + redisHost + ":" + redisPort);
    log.info("numberOfOperations " + numberOfOperations);
    log.info("sleepTimeAfterUpdate " + sleepTimeAfterUpdate);
    log.info("parallelism " + parallelism);
    log.info("sleepAfterUpdate " + sleepAfterUpdate);

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
        sleepAfterUpdate);

    multiThreadedRapidTester.start();

    log.info("END");

  }

  private void start() throws InterruptedException {

    ExecutorService threadPool = Executors.newFixedThreadPool(parallelism);

    int id = 0;
    while (id < numberOfOperations) {
      threadPool.submit(new Worker(id++));
      if (sleepAfterUpdate) {
        Thread.sleep(ThreadLocalRandom.current().nextLong(0L, sleepTimeAfterUpdate * 2));
      }
    }
    threadPool.shutdown();
    threadPool.awaitTermination(10, TimeUnit.MINUTES);

    lock.lock();
    log.info("SET : " + SET);
    log.info("GET yay : " + GETyay + " nay : " + GETnay);
    log.info("UPD yay : " + UPDyay + " UPD stale : " + UPDstale);
    lock.unlock();
  }

  class Worker implements Runnable {

    int id;

    Worker(int id) {
      this.id = id;
    }

    @Override
    public void run() {

      int queryOrWrite = ThreadLocalRandom.current().nextInt(10);
      log.info(this.id + "queryOrWrite : " + queryOrWrite);

    if (queryOrWrite <= 2 || id < parallelism) {
      //write

      String value = UUID.randomUUID().toString();
      String key = UUID.randomUUID().toString();

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.set(key, value);
      }

      store.put(key, value);
      log.info(this.id + "SET " + key + " = " + value);
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


        if (queryOrWrite <= 6) {
          //Query random key in store
          List<Map.Entry<String, String>> storeList = new ArrayList<>(store.entrySet());
          Collections.shuffle(storeList);

          Map.Entry<String, String> randomEntry =
              storeList.get(ThreadLocalRandom.current().nextInt(storeList.size()));

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

          } else {

            lock.lock();
            GETnay++;
            lock.unlock();
            log.info(this.id + "NAY" +
                " GET " + randomEntry.getKey() +
                " GOT : " + reply.replaceAll(RedisProto.CRLF, "CRLF") +
                " ACT " + randomEntry.getValue());

          }

        } else if (queryOrWrite <= 9) {
          //Update random key in store

          List<Map.Entry<String, String>> storeList = new ArrayList<>(store.entrySet());
          Collections.shuffle(storeList);
          Map.Entry<String, String> randomEntry =
              storeList.get(ThreadLocalRandom.current().nextInt(storeList.size()));

          String newValue = UUID.randomUUID().toString();

          log.info(this.id + "Updating " + randomEntry + " new value " + newValue);

          try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(randomEntry.getKey(), newValue);
          }

          store.put(randomEntry.getKey(), newValue);
          log.info(this.id + "UPDATED " + randomEntry.getKey() + " = " + newValue);

          if (sleepAfterUpdate) {
            Thread.sleep(ThreadLocalRandom.current().nextLong(0L, sleepTimeAfterUpdate * 2));
          }

          toSend = RedisProto.encodeSimpleString("GET " + randomEntry.getKey());
          log.info(this.id + "Sending : " + toSend.replaceAll(RedisProto.CRLF, "CRLF"));
          bw.write(toSend);
          bw.flush();
          reply = br.readLine() + RedisProto.CRLF;
          log.info(this.id + "GOT : " + reply.replaceAll(RedisProto.CRLF, "CRLF"));


          if (RedisProto.decode(reply).equals(newValue)) {
            lock.lock();
            UPDyay++;
            lock.unlock();
          } else {
            log.info(this.id + "STALE" +
                " GET " + randomEntry.getKey() +
                " GOT : " + reply.replaceAll(RedisProto.CRLF, "CRLF") +
                " OLD : " + randomEntry.getValue() +
                " NEW : " + newValue);
            lock.lock();
            UPDstale++;
            lock.unlock();
          }


          log.info(this.id + "Keys : " + store.size());
        }

        /*if (ThreadLocalRandom.current().nextInt(0, 19) == 5) {
          toSend = RedisProto.encodeSimpleString("CACHE");
          log.info(this.id + "Sending : " + toSend.replaceAll(RedisProto.CRLF, "CRLF"));
          bw.write(toSend);
          bw.flush();
          reply = br.readLine() + RedisProto.CRLF;
          log.info(this.id + "GOT : " + reply.replaceAll(RedisProto.CRLF, "CRLF"));
        }*/

        toSend = RedisProto.encodeSimpleString("EXIT");
        log.info(this.id + "Sending : " + toSend.replaceAll(RedisProto.CRLF, "CRLF"));
        bw.write(toSend);
        bw.flush();
        reply = br.readLine() + RedisProto.CRLF;
        log.info(this.id + "GOT : " + reply.replaceAll(RedisProto.CRLF, "CRLF"));

      } catch (IOException | InterruptedException e1) {

        log.error(id + "failed", e1);
      }
    }
  }
}
