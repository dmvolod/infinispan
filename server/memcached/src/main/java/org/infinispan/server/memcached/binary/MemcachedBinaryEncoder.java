package org.infinispan.server.memcached.binary;

import static org.infinispan.server.core.transport.VInt.write;

import java.security.PrivilegedActionException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.infinispan.AdvancedCache;
import org.infinispan.CacheSet;
import org.infinispan.commons.CacheException;
import org.infinispan.commons.IllegalLifecycleStateException;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.dataconversion.MediaTypeIds;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.commons.marshall.WrappedByteArray;
import org.infinispan.commons.util.CloseableIterator;
import org.infinispan.commons.util.Util;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.counter.api.CounterConfiguration;
import org.infinispan.counter.impl.CounterModuleLifecycle;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.jgroups.SuspectException;
import org.infinispan.server.core.transport.NettyTransport;
import org.infinispan.server.core.transport.VInt;
import org.infinispan.server.hotrod.Constants;
import org.infinispan.server.memcached.MemcachedServer;
import org.infinispan.server.memcached.binary.counter.listener.ClientCounterEvent;
import org.infinispan.server.memcached.binary.iteration.IterableIterationResult;
import org.infinispan.server.memcached.logging.Log;
import org.infinispan.stats.ClusterCacheStats;
import org.infinispan.stats.Stats;
import org.infinispan.topology.CacheTopology;
import org.jgroups.SuspectedException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

/**
 * @author Galder Zamarreño
 */
class MemcachedBinaryEncoder implements MemcachedEncoder {
   private static final Log log = LogFactory.getLog(MemcachedBinaryEncoder.class, Log.class);
   private static final boolean trace = log.isTraceEnabled();

   @Override
   public void writeEvent(Events.Event e, ByteBuf buf) {
      if (trace)
         log.tracef("Write event %s", e);
      writeHeaderNoTopology(buf, e.messageId, e.op);
      //ExtendedByteBuf.writeRangedBytes(e.listenerId, buf);
      e.writeEvent(buf);
   }

   @Override
   public ByteBuf authResponse(MemcachedHeader header, MemcachedServer server, Channel channel, byte[] challenge) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      if (challenge != null) {
         buf.writeBoolean(false);
         //ExtendedByteBuf.writeRangedBytes(challenge, buf);
      } else {
         buf.writeBoolean(true);
         //ExtendedByteBuf.writeUnsignedInt(0, buf);
      }
      return buf;
   }

   @Override
   public ByteBuf authMechListResponse(MemcachedHeader header, MemcachedServer server, Channel channel, Set<String> mechs) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      //ExtendedByteBuf.writeUnsignedInt(mechs.size(), buf);
      for (String s : mechs) {
         //ExtendedByteBuf.writeString(s, buf);
      }
      return buf;
   }

   @Override
   public ByteBuf notExecutedResponse(MemcachedHeader header, MemcachedServer server, Channel channel, byte[] prev) {
      return valueResponse(header, server, channel, OperationStatus.NotExecutedWithPrevious, prev);
   }

   @Override
   public ByteBuf notExistResponse(MemcachedHeader header, MemcachedServer server, Channel channel) {
      return emptyResponse(header, server, channel, OperationStatus.KeyDoesNotExist);
   }

   @Override
   public ByteBuf valueResponse(MemcachedHeader header, MemcachedServer server, Channel channel, OperationStatus status, byte[] prev) {
      ByteBuf buf = writeHeader(header, server, channel, status);
      if (prev == null) {
         //ExtendedByteBuf.writeUnsignedInt(0, buf);
      } else {
         //ExtendedByteBuf.writeRangedBytes(prev, buf);
      }
      if (trace) {
         log.tracef("Write response to %s messageId=%d status=%s prev=%s", header.op, header.messageId, status, Util.printArray(prev));
      }
      return buf;
   }

   @Override
   public ByteBuf successResponse(MemcachedHeader header, MemcachedServer server, Channel channel, byte[] result) {
      return valueResponse(header, server, channel, OperationStatus.SuccessWithPrevious, result);
   }

   @Override
   public ByteBuf errorResponse(MemcachedHeader header, MemcachedServer server, Channel channel, String message, OperationStatus status) {
      ByteBuf buf = writeHeader(header, server, channel, status);
      //ExtendedByteBuf.writeString(message, buf);
      return buf;
   }

   @Override
   public ByteBuf bulkGetResponse(MemcachedHeader header, MemcachedServer server, Channel channel, int size, CacheSet<Map.Entry<byte[], byte[]>> entries) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      try (CloseableIterator<Map.Entry<byte[], byte[]>> iterator = entries.iterator()) {
         int max = Integer.MAX_VALUE;
         if (size != 0) {
            if (trace) log.tracef("About to write (max) %d messages to the client", size);
            max = size;
         }
         int count = 0;
         while (iterator.hasNext() && count < max) {
            Map.Entry<byte[], byte[]> entry = iterator.next();
            buf.writeByte(1); // Not done
            //ExtendedByteBuf.writeRangedBytes(entry.getKey(), buf);
            //ExtendedByteBuf.writeRangedBytes(entry.getValue(), buf);
            count++;
         }
         buf.writeByte(0); // Done
      }
      return buf;
   }

   @Override
   public ByteBuf emptyResponse(MemcachedHeader header, MemcachedServer server, Channel channel, OperationStatus status) {
      return writeHeader(header, server, channel, status);
   }

   @Override
   public ByteBuf statsResponse(MemcachedHeader header, MemcachedServer server, Channel channel, Stats stats, NettyTransport transport, ComponentRegistry cacheRegistry) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      int numStats = 9;
      if (transport != null) {
         numStats += 2;
      }
      ClusterCacheStats clusterCacheStats = null;
      clusterCacheStats = cacheRegistry.getComponent(ClusterCacheStats.class);
      if (clusterCacheStats != null) {
         numStats += 7;
      }

      //ExtendedByteBuf.writeUnsignedInt(numStats, buf);
      writePair(buf, "timeSinceStart", String.valueOf(stats.getTimeSinceStart()));
      writePair(buf, "currentNumberOfEntries", String.valueOf(stats.getCurrentNumberOfEntries()));
      writePair(buf, "totalNumberOfEntries", String.valueOf(stats.getTotalNumberOfEntries()));
      writePair(buf, "stores", String.valueOf(stats.getStores()));
      writePair(buf, "retrievals", String.valueOf(stats.getRetrievals()));
      writePair(buf, "hits", String.valueOf(stats.getHits()));
      writePair(buf, "misses", String.valueOf(stats.getMisses()));
      writePair(buf, "removeHits", String.valueOf(stats.getRemoveHits()));
      writePair(buf, "removeMisses", String.valueOf(stats.getRemoveMisses()));

      if (transport != null) {
         writePair(buf, "totalBytesRead", String.valueOf(transport.getTotalBytesRead()));
         writePair(buf, "totalBytesWritten", String.valueOf(transport.getTotalBytesWritten()));
      }

      if (clusterCacheStats != null) {
         writePair(buf, "globalCurrentNumberOfEntries", String.valueOf(clusterCacheStats.getCurrentNumberOfEntries()));
         writePair(buf, "globalStores", String.valueOf(clusterCacheStats.getStores()));
         writePair(buf, "globalRetrievals", String.valueOf(clusterCacheStats.getRetrievals()));
         writePair(buf, "globalHits", String.valueOf(clusterCacheStats.getHits()));
         writePair(buf, "globalMisses", String.valueOf(clusterCacheStats.getMisses()));
         writePair(buf, "globalRemoveHits", String.valueOf(clusterCacheStats.getRemoveHits()));
         writePair(buf, "globalRemoveMisses", String.valueOf(clusterCacheStats.getRemoveMisses()));
      }
      return buf;
   }

   private void writePair(ByteBuf buf, String key, String value) {
      //ExtendedByteBuf.writeString(key, buf);
      //ExtendedByteBuf.writeString(value, buf);
   }

   @Override
   public ByteBuf valueWithVersionResponse(MemcachedHeader header, MemcachedServer server, Channel channel, byte[] value, long version) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      buf.writeLong(version);
      //ExtendedByteBuf.writeRangedBytes(value, buf);
      return buf;
   }


   @Override
   public ByteBuf getWithMetadataResponse(MemcachedHeader header, MemcachedServer server, Channel channel, CacheEntry<byte[], byte[]> entry) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      //MetadataUtils.writeMetadata(MetadataUtils.extractLifespan(entry), MetadataUtils.extractMaxIdle(entry),
      //      MetadataUtils.extractCreated(entry), MetadataUtils.extractLastUsed(entry), MetadataUtils.extractVersion(entry), buf);
      //ExtendedByteBuf.writeRangedBytes(entry.getValue(), buf);
      return buf;
   }

   @Override
   public ByteBuf getStreamResponse(MemcachedHeader header, MemcachedServer server, Channel channel, int offset, CacheEntry<byte[], byte[]> entry) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      //MetadataUtils.writeMetadata(MetadataUtils.extractLifespan(entry), MetadataUtils.extractMaxIdle(entry),
      //      MetadataUtils.extractCreated(entry), MetadataUtils.extractLastUsed(entry), MetadataUtils.extractVersion(entry), buf);
      //ExtendedByteBuf.writeRangedBytes(entry.getValue(), offset, buf);
      return buf;
   }

   @Override
   public ByteBuf getAllResponse(MemcachedHeader header, MemcachedServer server, Channel channel, Map<byte[], byte[]> entries) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      //ExtendedByteBuf.writeUnsignedInt(entries.size(), buf);
      //for (Map.Entry<byte[], byte[]> entry : entries.entrySet()) {
      //   ExtendedByteBuf.writeRangedBytes(entry.getKey(), buf);
      //   ExtendedByteBuf.writeRangedBytes(entry.getValue(), buf);
      //}
      return buf;
   }

   @Override
   public ByteBuf bulkGetKeysResponse(MemcachedHeader header, MemcachedServer server, Channel channel, CloseableIterator<byte[]> iterator) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      while (iterator.hasNext()) {
         buf.writeByte(1);
         //ExtendedByteBuf.writeRangedBytes(iterator.next(), buf);
      }
      buf.writeByte(0);
      return buf;
   }

   @Override
   public ByteBuf iterationStartResponse(MemcachedHeader header, MemcachedServer server, Channel channel, String iterationId) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      //ExtendedByteBuf.writeString(iterationId, buf);
      return buf;
   }

   @Override
   public ByteBuf iterationNextResponse(MemcachedHeader header, MemcachedServer server, Channel channel, IterableIterationResult iterationResult) {
      ByteBuf buf = writeHeader(header, server, channel, iterationResult.getStatusCode());
      /*
      ExtendedByteBuf.writeRangedBytes(iterationResult.segmentsToBytes(), buf);
      List<CacheEntry> entries = iterationResult.getEntries();
      ExtendedByteBuf.writeUnsignedInt(entries.size(), buf);
      Optional<Integer> projectionLength = projectionInfo(entries, header.version);
      projectionLength.ifPresent(i -> ExtendedByteBuf.writeUnsignedInt(i, buf));
      for (CacheEntry cacheEntry : entries) {
         if (HotRodVersion.HOTROD_25.isAtLeast(header.version)) {
            if (iterationResult.isMetadata()) {
               buf.writeByte(1);
               InternalCacheEntry ice = (InternalCacheEntry) cacheEntry;
               int lifespan = ice.getLifespan() < 0 ? -1 : (int) (ice.getLifespan() / 1000);
               int maxIdle = ice.getMaxIdle() < 0 ? -1 : (int) (ice.getMaxIdle() / 1000);
               long lastUsed = ice.getLastUsed();
               long created = ice.getCreated();
               long dataVersion = MetadataUtils.extractVersion(ice);
               MetadataUtils.writeMetadata(lifespan, maxIdle, created, lastUsed, dataVersion, buf);
            } else {
               buf.writeByte(0);
            }
         }
         Object key = iterationResult.getResultFunction().apply(cacheEntry.getKey());
         Object value = cacheEntry.getValue();
         ExtendedByteBuf.writeRangedBytes((byte[]) key, buf);
         if (value instanceof Object[]) {
            for (Object o : (Object[]) value) {
               ExtendedByteBuf.writeRangedBytes((byte[]) o, buf);
            }
         } else if (value instanceof byte[]) {
            ExtendedByteBuf.writeRangedBytes((byte[]) value, buf);
         } else {
            throw new IllegalArgumentException("Unsupported type passed: " + value.getClass());
         }
      }
       */
      return buf;
   }

   @Override
   public ByteBuf counterConfigurationResponse(MemcachedHeader header, MemcachedServer server, Channel channel, CounterConfiguration configuration) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      //encodeConfiguration(configuration, buf::writeByte, buf::writeLong, value -> ExtendedByteBuf.writeUnsignedInt(value, buf));
      return buf;
   }

   @Override
   public ByteBuf counterNamesResponse(MemcachedHeader header, MemcachedServer server, Channel channel, Collection<String> counterNames) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      write(buf, counterNames.size());
      for (String s : counterNames) {
         //writeString(s, buf);
      }
      return buf;
   }

   @Override
   public ByteBuf multimapCollectionResponse(MemcachedHeader header, MemcachedServer server, Channel channel, OperationStatus status, Collection<byte[]> values) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      /*
      ExtendedByteBuf.writeUnsignedInt(values.size(), buf);
      for (byte[] v : values) {
         ExtendedByteBuf.writeRangedBytes(v, buf);
      }
       */
      return buf;
   }

   @Override
   public ByteBuf multimapEntryResponse(MemcachedHeader header, MemcachedServer server, Channel channel, OperationStatus status, CacheEntry<WrappedByteArray, Collection<WrappedByteArray>> entry, Collection<byte[]> values) {
      ByteBuf buf = writeHeader(header, server, channel, status);
      /*
      MetadataUtils.writeMetadata(MetadataUtils.extractLifespan(entry), MetadataUtils.extractMaxIdle(entry),
            MetadataUtils.extractCreated(entry), MetadataUtils.extractLastUsed(entry), MetadataUtils.extractVersion(entry), buf);
      if (values == null) {
         buf.writeByte(0);
      } else {
         ExtendedByteBuf.writeUnsignedInt(values.size(), buf);
         for (byte[] v : values) {
            ExtendedByteBuf.writeRangedBytes(v, buf);
         }
      }
       */
      return buf;
   }

   @Override
   public ByteBuf booleanResponse(MemcachedHeader header, MemcachedServer server, Channel channel, boolean result) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      buf.writeByte(result ? 1 : 0);
      return buf;
   }

   @Override
   public ByteBuf unsignedLongResponse(MemcachedHeader header, MemcachedServer server, Channel channel, long value) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      //ExtendedByteBuf.writeUnsignedLong(value, buf);
      return buf;
   }

   @Override
   public ByteBuf longResponse(MemcachedHeader header, MemcachedServer server, Channel channel, long value) {
      ByteBuf buf = writeHeader(header, server, channel, OperationStatus.Success);
      buf.writeLong(value);
      return buf;
   }

   @Override
   public OperationStatus errorStatus(Throwable t) {
      if (t instanceof SuspectException) {
         return OperationStatus.NodeSuspected;
      } else if (t instanceof IllegalLifecycleStateException) {
         return OperationStatus.IllegalLifecycleState;
      } else if (t instanceof CacheException) {
         // JGroups and remote exceptions (inside RemoteException) can come wrapped up
         Throwable cause = t.getCause() == null ? t : t.getCause();
         if (cause instanceof SuspectedException) {
            return OperationStatus.NodeSuspected;
         } else if (cause instanceof IllegalLifecycleStateException) {
            return OperationStatus.IllegalLifecycleState;
         } else if (cause instanceof InterruptedException) {
            return OperationStatus.IllegalLifecycleState;
         } else {
            return OperationStatus.ServerError;
         }
      } else if (t instanceof InterruptedException) {
         return OperationStatus.IllegalLifecycleState;
      } else if (t instanceof PrivilegedActionException) {
         return errorStatus(t.getCause());
      } else if (t instanceof SuspectedException) {
         return OperationStatus.NodeSuspected;
      } else {
         return OperationStatus.ServerError;
      }
   }

   private ByteBuf writeHeader(MemcachedHeader header, MemcachedServer server, Channel channel, OperationStatus status) {
      return writeHeader(header, server, channel, status, false);
   }

   private ByteBuf writeHeader(MemcachedHeader header, MemcachedServer server, Channel channel, OperationStatus status, boolean sendMediaType) {
      ByteBuf buf = channel.alloc().ioBuffer();
      /*
      Cache<Address, ServerAddress> addressCache = HotRodVersion.forVersion(header.version) != HotRodVersion.UNKNOWN ?
            server.getAddressCache() : null;

      Optional<AbstractTopologyResponse> newTopology;

      MediaType keyMediaType = null;
      MediaType valueMediaType = null;
      boolean objectStorage = false;
      CacheTopology cacheTopology;

      if (header.op != HotRodOperation.ERROR) {
         if (CounterModuleLifecycle.COUNTER_CACHE_NAME.equals(header.cacheName)) {
            cacheTopology = getCounterCacheTopology(server.getCacheManager());
            newTopology = getTopologyResponse(header.clientIntel, header.topologyId, addressCache, CacheMode.DIST_SYNC,
                  cacheTopology);
         } else if (header.cacheName.isEmpty() && !server.hasDefaultCache()) {
            cacheTopology = null;
            newTopology = Optional.empty();
         } else {
            HotRodServer.CacheInfo cacheInfo = server.getCacheInfo(header);
            Configuration configuration = cacheInfo.configuration;
            CacheMode cacheMode = configuration.clustering().cacheMode();

            cacheTopology =
               cacheMode.isClustered() ? cacheInfo.distributionManager.getCacheTopology() : null;
            newTopology = getTopologyResponse(header.clientIntel, header.topologyId, addressCache, cacheMode,
                                              cacheTopology);
            keyMediaType = configuration.encoding().keyDataType().mediaType();
            valueMediaType = configuration.encoding().valueDataType().mediaType();
            objectStorage = APPLICATION_OBJECT.match(keyMediaType);
         }
      } else {
         cacheTopology = null;
         newTopology = Optional.empty();
      }

      buf.writeByte(Constants.MAGIC_RES);
      writeUnsignedLong(header.messageId, buf);
      buf.writeByte(header.op.getResponseOpCode());
      writeStatus(header, buf, server, objectStorage, status);
      if (newTopology.isPresent()) {
         AbstractTopologyResponse topology = newTopology.get();
         if (topology instanceof TopologyAwareResponse) {
            writeTopologyUpdate((TopologyAwareResponse) topology, buf, ((InetSocketAddress) channel.localAddress()).getAddress());
            if (header.clientIntel == Constants.INTELLIGENCE_HASH_DISTRIBUTION_AWARE)
               writeEmptyHashInfo(topology, buf);
         } else if (topology instanceof HashDistAware20Response) {
            writeHashTopologyUpdate((HashDistAware20Response) topology, cacheTopology, buf, ((InetSocketAddress) channel.localAddress()).getAddress());
         } else {
            throw new IllegalArgumentException("Unsupported response: " + topology);
         }
      } else {
         if (trace) log.trace("Write topology response header with no change");
         buf.writeByte(0);
      }
      if (sendMediaType && HotRodVersion.HOTROD_29.isAtLeast(header.version)) {
         writeMediaType(buf, keyMediaType);
         writeMediaType(buf, valueMediaType);
      }
       */
      return buf;
   }

   @Override
   public void writeCounterEvent(ClientCounterEvent event, ByteBuf buffer) {
      //writeHeaderNoTopology(buffer, 0, HotRodOperation.COUNTER_EVENT);
      event.writeTo(buffer);
   }

   private CacheTopology getCounterCacheTopology(EmbeddedCacheManager cacheManager) {
      AdvancedCache<?, ?> cache = cacheManager.getCache(CounterModuleLifecycle.COUNTER_CACHE_NAME).getAdvancedCache();
      return cache.getCacheConfiguration().clustering().cacheMode().isClustered() ?
            cache.getComponentRegistry().getDistributionManager().getCacheTopology() :
            null; //local cache
   }

   private void writeHeaderNoTopology(ByteBuf buffer, long messageId, MemcachedOperation operation) {
      buffer.writeByte(Constants.MAGIC_RES);
      //writeUnsignedLong(messageId, buffer);
      buffer.writeByte(operation.getResponseOpCode());
      buffer.writeByte(OperationStatus.Success.getCode());
      buffer.writeByte(0); // no topology change
   }

   private void writeStatus(MemcachedHeader header, ByteBuf buf, MemcachedServer server, boolean objStorage, OperationStatus status) {
      /*
      if (server == null || HotRodVersion.HOTROD_24.isOlder(header.version) || HotRodVersion.HOTROD_29.isAtLeast(header.version))
         buf.writeByte(status.getCode());
      else {
       */
         OperationStatus st = OperationStatus.withLegacyStorageHint(status, objStorage);
         buf.writeByte(st.getCode());
      //}
   }

   private void writeMediaType(ByteBuf buf, MediaType mediaType) {
      if (mediaType == null) {
         buf.writeByte(0);
      } else {
         Short id = MediaTypeIds.getId(mediaType);
         if (id != null) {
            buf.writeByte(1);
            VInt.write(buf, id);
         } else {
            buf.writeByte(2);
            //ExtendedByteBuf.writeString(mediaType.toString(), buf);
         }
         Map<String, String> parameters = mediaType.getParameters();
         VInt.write(buf, parameters.size());
         parameters.forEach((key, value) -> {
            //ExtendedByteBuf.writeString(key, buf);
            //ExtendedByteBuf.writeString(value, buf);
         });
      }
   }

}
