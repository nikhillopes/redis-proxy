package org.n.proxy.cache;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Cache {

  public Lock lock;
  public int cacheMiss;
  public int cacheHit;
  public int getExpired;
  public int lruEviction;
  public int expiryEviction;
  public int update;
  public int write;

  private int capacity;
  private long expiry;
  private int cacheId;

  private Map<String, CacheNode> cache;
  private Queue<KeyAndExpiry> evictionHeap;

  private CacheNode head = null;
  private CacheNode tail = null;

  public Cache(int capacity, long expiry, int cacheId) {
    this.capacity = capacity;
    this.expiry = expiry;

    this.evictionHeap = new PriorityQueue<>(
        capacity * 3,
        Comparator.comparingLong(KeyAndExpiry::getEvictionTime)
    );

    this.cache = new HashMap<>(capacity + 1, 1.0f);

    head = new CacheNode();
    head.setKey("head");
    tail = new CacheNode();
    head.setNext(tail);
    tail.setKey("tail");
    tail.setPrevious(head);

    this.lock = new ReentrantLock(true);
    this.cacheId = cacheId;

  }

  public String get(String key) {

    log.info(cacheId + "-----------GET (K :" + key + ")-----------");
    long currentTs = System.currentTimeMillis();

    CacheNode cacheNode = cache.get(key);

    if (cacheNode == null) {
      log.info(cacheId + "GET : " + key + " not found : " + currentTs);
      cacheMiss++;
      return null;
    } else if (isExpired(cacheNode.getEvictionTime(), currentTs)) {
      log.info(cacheId + "GET : " + key + " expired : " + currentTs);
      cache.remove(key);
      removeNode(cacheNode);
      getExpired++;
      expiryEviction++;
      return null;
    } else {
      log.info(cacheId + "GET : " + key + " found : " + currentTs);
      moveToFront(cacheNode);
      cacheHit++;
      return cacheNode.getValue();
    }
  }

  public void set(String key, String value) {

    log.info(cacheId + "--------SET (K :" + key + " V : " + value + ")--------");
    long currentTs = System.currentTimeMillis();
    long evictionTime = currentTs + expiry;

    makeSpace(currentTs, key);

    evictionHeap.add(new KeyAndExpiry(key, evictionTime));

    CacheNode nodeToAdd = cache.get(key);

    if (nodeToAdd != null) {

      log.info(cacheId + "SET : exists " + key + " expiry : " + currentTs);
      nodeToAdd.setValue(value);
      nodeToAdd.setEvictionTime(evictionTime);
      moveToFront(nodeToAdd);
      update++;
    } else {

      log.info(cacheId + "SET : " + key + " expiry : " + currentTs);

      nodeToAdd = CacheNode
          .builder()
          .key(key)
          .value(value)
          .evictionTime(evictionTime)
          .build();

      addToFront(nodeToAdd);
      cache.put(key, nodeToAdd);
      write++;
    }
  }

  public void display() {

    cache.entrySet().stream().map(Object::toString).forEach(log::info);
    log.info(Arrays.toString(evictionHeap.toArray()));
  }

  public void display(boolean stats) {
    log.info(cacheId + " GET : " + (cacheHit + cacheMiss + getExpired));
    log.info(cacheId + " GET HIT : " + (cacheHit));
    log.info(cacheId + " GET MISS : " + (cacheMiss));
    log.info(cacheId + " GET EXPIRED : " + (getExpired));
    log.info(cacheId + " SET ALL : " + (write + update));
    log.info(cacheId + " SET WRITE : " + (write));
    log.info(cacheId + " SET UPDATE : " + (update));
    log.info(cacheId + " EVICT ALL : " + (lruEviction + expiryEviction));
    log.info(cacheId + " EVICT LRU : " + (lruEviction));
    log.info(cacheId + " EVICT EXPIRY : " + (expiryEviction));

  }

  public void clear() {
    cache.clear();
    evictionHeap.clear();
    head.setNext(tail);
    tail.setPrevious(head);

  }

  private void moveToFront(CacheNode nodeToMove) {
    removeNode(nodeToMove);
    addToFront(nodeToMove);
  }

  private void addToFront(CacheNode nodeToAdd) {
    CacheNode prev = tail.getPrevious();
    prev.setNext(nodeToAdd);
    nodeToAdd.setPrevious(prev);
    tail.setPrevious(nodeToAdd);
    nodeToAdd.setNext(tail);

  }

  private void removeNode(CacheNode nodeToRemove) {
    CacheNode prev = nodeToRemove.getPrevious();
    CacheNode next = nodeToRemove.getNext();
    prev.setNext(next);
    next.setPrevious(prev);
  }

  private void makeSpace(long currentTs, String newKey) {
    //Make space if needed
    if (cache.size() >= capacity || evictionHeap.size() >= capacity * 3) {

      log.info(cacheId + "MakeSpace");
      KeyAndExpiry evictionCandidate;

      while ((evictionCandidate = evictionHeap.peek()) != null) {

        boolean isExpired = isExpired(evictionCandidate.getEvictionTime(), currentTs);
        boolean isInCache = cache.containsKey(evictionCandidate.getKey());
        boolean isDupInEvictionHeap = isDupInEvictionHeap(evictionCandidate);

        log.info(cacheId + "EvictionCandidate : " + evictionCandidate.toString()
            + " isExpired : " + isExpired
            + " isInCache : " + isInCache
            + " isDupInEvictionHeap : " + isDupInEvictionHeap
        );

        if (isExpired || isDupInEvictionHeap) {

          log.info(cacheId + "evictionHeap.poll() isExpired : "
              + isExpired + " isDupInEvictionHeap : " + isDupInEvictionHeap);
          evictionHeap.poll();
        }

        if (isInCache && isExpired && !isDupInEvictionHeap) {
          log.info(cacheId + "inCache but Expired");
          CacheNode cacheNodeToRemove = cache.remove(evictionCandidate.getKey());
          removeNode(cacheNodeToRemove);
          expiryEviction++;
        }

        if (cache.size() >= capacity && !cache.containsKey(newKey)) {

          //LRU EvictionCandidate
          CacheNode lruNode = head.getNext();
          cache.remove(lruNode.getKey());
          removeNode(lruNode);
          log.info(cacheId + "LRUEviction : " + lruNode.toString());
          lruEviction++;
        }

        if (!isExpired && !isDupInEvictionHeap) {
          break;
        }

        log.info(cacheId + "Evicted : " + evictionCandidate.toString()
            + " isExpired : " + isExpired
            + " isInCache : " + isInCache
            + " isDupInEvictionHeap : " + isDupInEvictionHeap);
      }

      log.info(cacheId + "MakeSpaceEnd");
    }
  }

  private boolean isExpired(long evictionTime, long currentTs) {
    return evictionTime < currentTs;
  }

  private boolean isDupInEvictionHeap(KeyAndExpiry evictionCandidate) {
    CacheNode cacheEntry = cache.get(evictionCandidate.getKey());

    if (cacheEntry != null) {
      return cacheEntry.getEvictionTime() != evictionCandidate.getEvictionTime();
    } else {
      return true;
    }
  }
}
