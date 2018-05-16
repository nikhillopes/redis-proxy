package org.n.proxy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.n.proxy.cache.Cache;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Slf4j
public class Proxy {

  private static boolean stop = false;
  private InetSocketAddress listeningAddress;
  private InetSocketAddress redisInstanceAddress;
  private int maxConnections;
  private JedisPool jedisPool;

  private Map<Integer, Cache> cache;

  public Proxy(
      InetSocketAddress listeningAddress,
      InetSocketAddress redisInstanceAddress,
      int cacheCapacity,
      long entryExpiry,
      int maxConnections) {

    this.maxConnections = maxConnections;
    this.listeningAddress = listeningAddress;
    this.redisInstanceAddress = redisInstanceAddress;

    this.cache = new ConcurrentHashMap<>(maxConnections);

    for (int i = 0; i < maxConnections; i++) {
      this.cache.put(i, new Cache(cacheCapacity, entryExpiry, i));
    }

  }

  private static synchronized void end() {
    stop = true;
  }

  private static synchronized boolean ended() {
    return stop;
  }

  private int getBucket(String key) {
    return Math.abs(key.hashCode() % maxConnections);
  }

  private void addShutdownHook() {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      log.info("GOT SIGINT");

      end();

      AtomicInteger cacheMiss = new AtomicInteger();
      AtomicInteger cacheHit = new AtomicInteger();
      AtomicInteger expired = new AtomicInteger();
      AtomicInteger update = new AtomicInteger();
      AtomicInteger write = new AtomicInteger();

      cache.forEach((key, value) -> {
        value.lock.lock();
        log.info("Cache " + key + " stats");
        value.display(true);
        value.clear();
        cacheMiss.set(cacheMiss.get() + value.cacheMiss);
        cacheHit.set(cacheHit.get() + value.cacheHit);
        expired.set(expired.get() + value.expired);
        update.set(update.get() + value.update);
        write.set(write.get() + value.write);
        value.lock.unlock();
      });

      log.info("* Aggregated Cache Stats");
      log.info("* GET : " + (cacheHit.get() + cacheMiss.get() + expired.get()));
      log.info("* GET HIT : " + (cacheHit.get()));
      log.info("* GET MISS : " + (cacheMiss.get()));
      log.info("* GET EXPIRED : " + (expired.get()));
      log.info("* SET ALL : " + (write.get() + update.get()));
      log.info("* SET WRITE : " + (write.get()));
      log.info("* SET UPDATE : " + (update).get());
    }));


  }

  public void startProxy() throws IOException, InterruptedException {

    log.info("Connecting to redis at : " + redisInstanceAddress.getAddress().toString() + ":" +
        redisInstanceAddress.getPort());
    this.jedisPool = new JedisPool(
        new JedisPoolConfig(),
        redisInstanceAddress.getHostName(),
        redisInstanceAddress.getPort());

    ServerSocket listener = new ServerSocket(
        listeningAddress.getPort(),
        maxConnections,
        listeningAddress.getAddress());

    addShutdownHook();

    ExecutorService threadPool = Executors.newFixedThreadPool(this.maxConnections);
    int id = 0;
    while (!ended()) {
      Socket clientSocket;
      try {
        log.info("Waiting for connection on " + listener.getInetAddress() + ":" + listener
            .getLocalPort());
        clientSocket = listener.accept();
      } catch (IOException e) {
        throw new RuntimeException(
            "Error accepting client connection", e);
      }

      log.info("Starting worker " + id++);
      threadPool.submit(new Worker(clientSocket, id));
    }

    threadPool.awaitTermination(Integer.MAX_VALUE, TimeUnit.MINUTES);

    listener.close();
  }

  private class Worker implements Runnable {

    private Socket incoming;
    private int id;

    Worker(Socket incoming, int id) {
      this.incoming = incoming;
      this.id = id;
    }

    @Override
    public void run() {

      log.info(id + "Connected from : " + incoming.getRemoteSocketAddress());
      try (
          BufferedReader br = new BufferedReader(new InputStreamReader(incoming.getInputStream()));
          BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(incoming.getOutputStream()))
      ) {

        while (!incoming.isClosed() && !ended()) {

          String command;

          String read = br.readLine();

          if (read != null && read.startsWith("+")) {

            log.info(id + "GOT : " + read);

          } else {
            log.info(id + "Invalid request " + read);
            bw.write(RedisProto.encodeErrorString("unsupported"));
            continue;
          }

          command = read + RedisProto.CRLF;

          log.info(id + "Received : " + command.replaceAll(RedisProto.CRLF, "\\r\\n"));
          String decoded = RedisProto.decode(command);

          log.info(id + "Decoded : " + decoded);

          if (decoded.startsWith("GET") || decoded.startsWith("get")) {

            String[] decodedT = decoded.split(" ");

            if (decodedT.length != 2 || decodedT[1] == null || decodedT[1].isEmpty()) {
              log.info(id + "Invalid get");
              bw.write(RedisProto.encodeErrorString("unknown"));
            }

            String key = decodedT[1];

            int bucketForKey = getBucket(key);
            log.info(id + "key : " + key + " bucket : " + bucketForKey);

            Cache innerCache = cache.get(bucketForKey);

            innerCache.lock.lock();
            String value = innerCache.get(key);
            innerCache.lock.unlock();

            boolean cacheMiss = false;

            if (value == null) {

              cacheMiss = true;
              log.info(id + "cache miss for key " + key);
              try (Jedis jedis = jedisPool.getResource()) {
                value = jedis.get(key);
              }
            } else {
              log.info(id + "cache hit for key " + key);
            }

            log.info(id + "Answer : key " + key + " value " + value);
            bw.write(RedisProto.encodeSimpleString(value));

            if (value != null && cacheMiss) {
              log.info(id + "Caching key " + key + " value " + value + " bucket " + bucketForKey);

              innerCache.lock.lock();
              innerCache.set(key, value);
              innerCache.lock.unlock();

            } else if (value == null) {

              log.info(id + "key " + key + " not found");
            }

          } else if (decoded.equalsIgnoreCase("EXIT")) {

            bw.write(RedisProto.encodeSimpleString("BYE"));
            break;

          } else if (decoded.equalsIgnoreCase("EXITPROXY")) {

            bw.write(RedisProto.encodeSimpleString("BYEBYEALL"));
            bw.flush();
            end();
            break;

          } else if (decoded.equalsIgnoreCase("PING")) {

            bw.write(RedisProto.encodeSimpleString("PONG"));

          } else if (decoded.equalsIgnoreCase("CACHE")) {

            bw.write(RedisProto.encodeSimpleString("DONE"));

            for (Entry<Integer, Cache> entry : cache.entrySet()) {

              entry.getValue().lock.lock();
              entry.getValue().display();
              entry.getValue().lock.unlock();

            }

          } else {

            bw.write(RedisProto.encodeErrorString("unknown"));
          }

          bw.flush();

        }


      } catch (IOException e) {
        log.error(id + "Error", e);
      } finally {

        if (incoming != null) {
          try {
            incoming.close();
          } catch (IOException e) {
            log.error(id + "Error", e);
          }
        }
      }
    }
  }
}
