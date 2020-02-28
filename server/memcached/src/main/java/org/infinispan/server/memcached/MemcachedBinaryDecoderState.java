package org.infinispan.server.memcached;

public enum MemcachedBinaryDecoderState {
    DECODE_HEADER,
    DECODE_EXTRAS,
    DECODE_KEY,
    DECODE_VALUE
}
