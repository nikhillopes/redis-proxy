package org.n.proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Launcher {

  public static void main(String[] args) throws UnknownHostException {

    //proxy hostname:port
    String proxyHost = System.getenv().getOrDefault("PROXY_HOST", "127.0.0.2");
    String proxyPort = System.getenv().getOrDefault("PROXY_PORT", "55555");

    //redis hostname:port
    String redisHost = System.getenv().getOrDefault("REDIS_HOST", "172.18.0.2");
    String redisPort = System.getenv().getOrDefault("REDIS_PORT", "6379");

    //capacity of the cache
    int capacity = Integer.valueOf(System.getenv().getOrDefault("PROXY_CAPACITY", "1000"));

    //expiry of stored keys in ms
    long expiry = Long.valueOf(System.getenv().getOrDefault("PROXY_EXPIRY", "100000"));

    //number of parallel requests
    int parallelism = Integer.valueOf(System.getenv().getOrDefault("PROXY_PARALLELISM", "13"));

    InetSocketAddress listeningAddress = new InetSocketAddress(
        InetAddress.getByName(proxyHost), Integer.valueOf(proxyPort)
    );

    InetSocketAddress redisInstanceAddress = new InetSocketAddress(
        InetAddress.getByName(redisHost), Integer.valueOf(redisPort)
    );

    Proxy proxy = new Proxy(listeningAddress, redisInstanceAddress, capacity, expiry, parallelism);

    log.info("proxy " + proxyHost + ":" + proxyPort);
    log.info("redis " + redisHost + ":" + redisPort);
    log.info("capacity " + capacity);
    log.info("expiry " + expiry);
    log.info("parallelism " + parallelism);

    try {

      log.info("Started");
      proxy.startProxy();
    } catch (IOException | InterruptedException e) {
      log.error(e.getMessage());
    }
  }
}