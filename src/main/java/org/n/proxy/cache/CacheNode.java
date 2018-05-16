package org.n.proxy.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CacheNode {

  private String key;
  private String value;
  private long evictionTime;
  private CacheNode previous;
  private CacheNode next;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    CacheNode that = (CacheNode) o;

    return value != null ? value.equals(that.value) : that.value == null;
  }

  @Override
  public int hashCode() {
    return value != null ? value.hashCode() : 0;
  }

  @Override
  public String toString() {
    return "CacheNode{" +
        "key='" + key + '\'' +
        ", value='" + value + '\'' +
        ", evictionTime=" + evictionTime +
        ", previous=" + previous.key +
        ", next=" + next.key +
        '}';
  }
}
