package org.n.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.n.proxy.cache.Cache;

@Slf4j
public class CacheTester {

  public static void main(String[] args) throws InterruptedException {

    log.info("//Test LRU");

    Cache cache = new Cache(10, 600000, 1);

    List<String> access = new ArrayList<>(10);
    for (int i = 0; i < 10; i++) {

      String key = UUID.randomUUID().toString();
      cache.set(key, UUID.randomUUID().toString());

      access.add(key);
    }

    Collections.shuffle(access);

    log.info("Access Order : " + access);

    for (String key : access) {
      cache.get(key);
    }

    cache.set(UUID.randomUUID().toString(), UUID.randomUUID().toString());

    if (cache.get(access.get(0)) != null) {
      log.error("Not LRU'ed");
    }

    String newKey = UUID.randomUUID().toString();
    cache.set(access.get(1), newKey);

    cache.set(UUID.randomUUID().toString(), UUID.randomUUID().toString());

    if (!cache.get(access.get(1)).equals(newKey)) {
      log.error("Wrongly LRU'ed");
    }

    log.info("//Test Expiry");

    cache = new Cache(10, 1000, 1);

    access = new ArrayList<>(10);
    for (int i = 0; i < 10; i++) {

      String key = UUID.randomUUID().toString();
      cache.set(key, UUID.randomUUID().toString());

      access.add(key);
    }

    Thread.sleep(1000);

    Collections.shuffle(access);

    log.info("Access Order : " + access);

    for (String key : access) {
      cache.get(key);
    }

    for (String key : access) {
      if (cache.get(key) != null) {
        log.error(key + " not Expired");
      } else {
        log.info(key + " Expired");
      }
    }
  }

}
