package org.infinispan.server.memcached.binary;

import org.infinispan.AdvancedCache;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.context.Flag;
import org.infinispan.server.memcached.logging.Log;

/**
 * @author wburns
 * @since 9.0
 */
public class MemcachedHeader {
   private static final Log log = LogFactory.getLog(MemcachedHeader.class, Log.class);

   MemcachedOperation op;
   long messageId;
   String cacheName;
   int flag;
   short clientIntel;
   int topologyId;
   MediaType keyType;
   MediaType valueType;

   public MemcachedHeader(MemcachedHeader header) {
      this(header.op, header.messageId, header.cacheName, header.flag, header.clientIntel, header.topologyId, header.keyType, header.valueType);
   }

   public MemcachedHeader(MemcachedOperation op, long messageId, String cacheName, int flag, short clientIntel, int topologyId, MediaType keyType, MediaType valueType) {
      this.op = op;
      this.messageId = messageId;
      this.cacheName = cacheName;
      this.flag = flag;
      this.clientIntel = clientIntel;
      this.topologyId = topologyId;
      this.keyType = keyType;
      this.valueType = valueType;
   }

   public boolean hasFlag(ProtocolFlag f) {
      return (flag & f.getValue()) == f.getValue();
   }

   public MemcachedOperation getOp() {
      return op;
   }

   public MediaType getKeyMediaType() {
      return keyType == null ? MediaType.APPLICATION_UNKNOWN : keyType;
   }

   public MediaType getValueMediaType() {
      return valueType == null ? MediaType.APPLICATION_UNKNOWN : valueType;
   }

   public long getMessageId() {
      return messageId;
   }

   public String getCacheName() {
      return cacheName;
   }

   public int getFlag() {
      return flag;
   }

   public short getClientIntel() {
      return clientIntel;
   }

   public int getTopologyId() {
      return topologyId;
   }

   AdvancedCache<byte[], byte[]> getOptimizedCache(AdvancedCache<byte[], byte[]> c,
                                                   boolean transactional, boolean clustered) {
      AdvancedCache<byte[], byte[]> optCache = c;

      if (hasFlag(ProtocolFlag.SkipListenerNotification)) {
         optCache = c.withFlags(Flag.SKIP_LISTENER_NOTIFICATION);
      }
      /*
      if (clustered && !transactional && op.isConditional()) {
         log.warnConditionalOperationNonTransactional(op.toString());
      }

      if (op.canSkipCacheLoading() && hasFlag(ProtocolFlag.SkipCacheLoader)) {
         optCache = c.withFlags(Flag.SKIP_CACHE_LOAD);
      }

      if (op.canSkipIndexing() && hasFlag(ProtocolFlag.SkipIndexing)) {
         optCache = c.withFlags(Flag.SKIP_INDEXING);
      }
      if (!hasFlag(ProtocolFlag.ForceReturnPreviousValue)) {
         if (op.isNotConditionalAndCanReturnPrevious()) {
            optCache = optCache.withFlags(Flag.IGNORE_RETURN_VALUES);
         }
      } else if (!transactional && op.canReturnPreviousValue()) {
         log.warnForceReturnPreviousNonTransactional(op.toString());
      }
       */
      return optCache;
   }

   public static MemcachedEncoder encoder() {
      return new MemcachedBinaryEncoder();
   }

   @Override
   public String toString() {
      return "MemcachedHeader{" +
            "op=" + op +
            ", messageId=" + messageId +
            ", cacheName='" + cacheName + '\'' +
            ", flag=" + flag +
            ", clientIntel=" + clientIntel +
            ", topologyId=" + topologyId +
            ", keyType=" + keyType +
            ", valueType=" + valueType +
            '}';
   }
}
