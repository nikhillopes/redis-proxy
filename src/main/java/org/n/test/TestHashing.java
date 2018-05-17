package org.n.test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TestHashing {

  public static void main(String[] args) {

    //Hashing Consistency
    String key = UUID.randomUUID().toString();

    int bucket = Math.abs(key.hashCode() % 7);
    for(int i = 0; i < 1024; i++) {
      if (Math.abs(key.hashCode() % 7) != bucket) {
        log.error("got different bucket");
      }
    }

    //Hash Distribution
    int [] buckets = {0,0,0,0,0,0,0,0,0,0,0,0};

    for (int i = 0; i < 1024; i++) {
      bucket = Math.abs(UUID.randomUUID().toString().hashCode() % 11);
      buckets[bucket]++;
    }

    log.info(Arrays.toString(buckets));
  }

}
