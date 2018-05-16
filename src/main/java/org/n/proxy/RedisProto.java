package org.n.proxy;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RedisProto {

  public static String CRLF = "\r\n";

  public static String encodeBulkString(String string) {

    if (string == null) {

      return "$" + "-1" + CRLF;
    } else if (string.isEmpty()) {

      return "$" + "0" + CRLF + CRLF;
    } else {

      return ("$" + string.length() + CRLF + string + CRLF);
    }
  }

  public static String encodeSimpleString(String string) {
    return ("+" + string + CRLF);

  }

  public static String encodeErrorString(String string) {
    return ("-" + string + CRLF);

  }

  public static String decode(String in) {
    String type = in.substring(0, 1);

    switch (type) {

      case "+": {
        return in.substring(1, in.length() - 2);
      }

      case "*": {

        int indexOf = in.indexOf(CRLF);
        int arrayLength = Integer.valueOf(in.substring(1, indexOf));

        if (arrayLength == 2) {
          //decode get

          // "*2\r\n$3\r\nGET\r\n$3\r\nKEY\r\n"
          return
              "GET "
                  + in.substring(in.indexOf(CRLF, in.lastIndexOf("$")) + 2, in.length() - 2);

        } else {
          //not implemented
        }
      }
      case "$":
        return decodeBulkString(in);
      case "-":
        throw new RuntimeException("Redis Error" + in);
      default:
        throw new RuntimeException("Not Implemented");
    }
  }

  private static String decodeBulkString(String in) {
    String type = in.substring(0, 1);

    log.info("decodeBulkString : " + in.replaceAll(RedisProto.CRLF, "\\r\\n"));

    switch (type) {
      case "$": {

        int indexOf = in.indexOf(CRLF);
        int stringLength = Integer.valueOf(in.substring(1, indexOf));
        log.info("stringLength " + stringLength);
        if (stringLength == -1) {
          return null;
        } else if (stringLength == 0) {
          return "";
        } else {

          int startIndex = (stringLength + "").length() + 3;
          log.info("startIndex : " + startIndex);
          return in.substring(startIndex, startIndex + stringLength);

        }
      }
      default:
        throw new RuntimeException("Not Implemented");
    }
  }
}
