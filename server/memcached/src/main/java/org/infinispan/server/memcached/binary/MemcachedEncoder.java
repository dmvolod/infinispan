package org.infinispan.server.memcached.binary;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import javax.transaction.xa.Xid;

import org.infinispan.CacheSet;
import org.infinispan.commons.marshall.WrappedByteArray;
import org.infinispan.commons.util.CloseableIterator;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.counter.api.CounterConfiguration;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.server.core.transport.NettyTransport;
import org.infinispan.server.memcached.binary.Events.Event;
import org.infinispan.server.memcached.binary.counter.listener.ClientCounterEvent;
import org.infinispan.server.memcached.binary.iteration.IterableIterationResult;
import org.infinispan.server.memcached.MemcachedServer;
import org.infinispan.stats.Stats;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

/**
 * This class represents the work to be done by an encoder of a particular Hot Rod protocol version.
 *
 * @author Galder Zamarreño
 * @since 5.1
 */
public interface MemcachedEncoder {

   ByteBuf authResponse(MemcachedHeader header, MemcachedServer server, Channel channel, byte[] challenge);

   ByteBuf authMechListResponse(MemcachedHeader header, MemcachedServer server, Channel channel, Set<String> mechs);

   ByteBuf notExecutedResponse(MemcachedHeader header, MemcachedServer server, Channel channel, byte[] prev);

   ByteBuf notExistResponse(MemcachedHeader header, MemcachedServer server, Channel channel);

   ByteBuf valueResponse(MemcachedHeader header, MemcachedServer server, Channel channel, OperationStatus status, byte[] prev);

   ByteBuf successResponse(MemcachedHeader header, MemcachedServer server, Channel channel, byte[] result);

   ByteBuf errorResponse(MemcachedHeader header, MemcachedServer server, Channel channel, String message, OperationStatus status);

   ByteBuf bulkGetResponse(MemcachedHeader header, MemcachedServer server, Channel channel, int size, CacheSet<Map.Entry<byte[], byte[]>> entries);

   ByteBuf emptyResponse(MemcachedHeader header, MemcachedServer server, Channel channel, OperationStatus status);

   ByteBuf statsResponse(MemcachedHeader header, MemcachedServer server, Channel channel, Stats stats, NettyTransport transport, ComponentRegistry cacheRegistry);

   ByteBuf valueWithVersionResponse(MemcachedHeader header, MemcachedServer server, Channel channel, byte[] value, long version);

   ByteBuf getWithMetadataResponse(MemcachedHeader header, MemcachedServer server, Channel channel, CacheEntry<byte[], byte[]> entry);

   ByteBuf getStreamResponse(MemcachedHeader header, MemcachedServer server, Channel channel, int offset, CacheEntry<byte[], byte[]> entry);

   ByteBuf getAllResponse(MemcachedHeader header, MemcachedServer server, Channel channel, Map<byte[], byte[]> map);

   ByteBuf bulkGetKeysResponse(MemcachedHeader header, MemcachedServer server, Channel channel, CloseableIterator<byte[]> iterator);

   ByteBuf iterationStartResponse(MemcachedHeader header, MemcachedServer server, Channel channel, String iterationId);

   ByteBuf iterationNextResponse(MemcachedHeader header, MemcachedServer server, Channel channel, IterableIterationResult iterationResult);

   ByteBuf counterConfigurationResponse(MemcachedHeader header, MemcachedServer server, Channel channel, CounterConfiguration configuration);

   ByteBuf counterNamesResponse(MemcachedHeader header, MemcachedServer server, Channel channel, Collection<String> counterNames);

   ByteBuf multimapCollectionResponse(MemcachedHeader header, MemcachedServer server, Channel channel, OperationStatus status, Collection<byte[]> values);

   ByteBuf multimapEntryResponse(MemcachedHeader header, MemcachedServer server, Channel channel, OperationStatus status, CacheEntry<WrappedByteArray, Collection<WrappedByteArray>> ce, Collection<byte[]> result);

   ByteBuf booleanResponse(MemcachedHeader header, MemcachedServer server, Channel channel, boolean result);

   ByteBuf unsignedLongResponse(MemcachedHeader header, MemcachedServer server, Channel channel, long value);

   ByteBuf longResponse(MemcachedHeader header, MemcachedServer server, Channel channel, long value);

   OperationStatus errorStatus(Throwable t);

   /**
    * Write an event, including its header, using the given channel buffer
    */
   void writeEvent(Event e, ByteBuf buf);

   /**
    * Writes a {@link ClientCounterEvent}, including its header, using a giver channel buffer.
    */
   void writeCounterEvent(ClientCounterEvent event, ByteBuf buffer);
}
