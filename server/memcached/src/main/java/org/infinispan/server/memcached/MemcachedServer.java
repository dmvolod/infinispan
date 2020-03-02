package org.infinispan.server.memcached;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.ExpirationConfiguration;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.server.core.AbstractProtocolServer;
import org.infinispan.server.core.transport.NettyChannelInitializer;
import org.infinispan.server.core.transport.NettyInitializers;
import org.infinispan.server.memcached.ascii.MemcachedTextDecoder;
import org.infinispan.server.memcached.configuration.MemcachedServerConfiguration;
import org.infinispan.server.memcached.logging.Log;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandler;
import org.infinispan.server.memcached.logging.MemcachedAccessLogging;

/**
 * Memcached server defining its decoder/encoder settings. In fact, Memcached does not use an encoder since there's
 * no really common headers between protocol operations.
 *
 * @author Galder Zamarreño
 * @since 4.1
 * @deprecated since 10.1. Will be removed unless a binary protocol encoder/decoder is implemented.
 */
@Deprecated
public class MemcachedServer extends AbstractProtocolServer<MemcachedServerConfiguration> {
   public MemcachedServer() {
      super("Memcached");
   }

   private final static Log log = LogFactory.getLog(MethodHandles.lookup().lookupClass(), Log.class);
   protected ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
   private AdvancedCache<byte[], byte[]> memcachedCache;
   private MemcachedAccessLogging accessLogging = new MemcachedAccessLogging();

   @Override
   protected void startInternal(MemcachedServerConfiguration configuration, EmbeddedCacheManager cacheManager) {
      if (cacheManager.getCacheConfiguration(configuration.defaultCacheName()) == null) {
         ConfigurationBuilder builder = new ConfigurationBuilder();
         Configuration defaultCacheConfiguration = cacheManager.getDefaultCacheConfiguration();
         if (defaultCacheConfiguration != null) { // We have a default configuration, use that
            builder.read(defaultCacheConfiguration);
         } else if (cacheManager.getCacheManagerConfiguration().isClustered()) { // We are running in clustered mode
            builder.clustering().cacheMode(CacheMode.REPL_SYNC);
         }
         cacheManager.defineConfiguration(configuration.defaultCacheName(), builder.build());
      }
      ExpirationConfiguration expConfig = cacheManager.getCacheConfiguration(configuration.defaultCacheName()).expiration();
      if (expConfig.lifespan() >= 0 || expConfig.maxIdle() >= 0)
        throw log.invalidExpiration(configuration.defaultCacheName());
      Cache<byte[], byte[]> cache = cacheManager.getCache(configuration.defaultCacheName());
      memcachedCache = cache.getAdvancedCache();

      super.startInternal(configuration, cacheManager);
   }

   public MemcachedAccessLogging accessLogging() {
      return accessLogging;
   }

   @Override
   public ChannelOutboundHandler getEncoder() {
      return null;
   }

   @Override
   public ChannelInboundHandler getDecoder() {
      return new MemcachedTextDecoder(memcachedCache, scheduler, transport, this::isCacheIgnored, configuration.clientEncoding());
   }

   @Override
   public ChannelInitializer<Channel> getInitializer() {
      return new NettyInitializers(new NettyChannelInitializer<>(this, transport, getEncoder(), getDecoder()));
   }

   @Override
   public void stop() {
      super.stop();
      scheduler.shutdown();
   }

   /**
    * Returns the cache being used by the Memcached server
    */
   public Cache<byte[], byte[]> getCache() {
      return memcachedCache;
   }
}
