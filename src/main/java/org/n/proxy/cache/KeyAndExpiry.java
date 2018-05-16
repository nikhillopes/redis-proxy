package org.n.proxy.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@AllArgsConstructor
@Builder
@Data
public class KeyAndExpiry {

  private String key;
  private long evictionTime;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    KeyAndExpiry that = (KeyAndExpiry) o;

    return key != null ? key.equals(that.key) : that.key == null;
  }

  @Override
  public int hashCode() {
    return key != null ? key.hashCode() : 0;
  }

  @Override
  public String toString() {
    return "KeyAndExpiry{" +
        "key='" + key + '\'' +
        ", evictionTime=" + evictionTime +
        '}';
  }
}
