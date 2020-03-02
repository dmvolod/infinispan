package org.infinispan.server.memcached.configuration;

/**
 * AbstractHotRodServerChildConfigurationBuilder.
 *
 * @author Tristan Tarrant
 * @since 7.0
 */
public abstract class AbstractHotRodServerChildConfigurationBuilder implements MemcachedServerChildConfigurationBuilder {
   private final MemcachedServerChildConfigurationBuilder builder;

   protected AbstractHotRodServerChildConfigurationBuilder(MemcachedServerChildConfigurationBuilder builder) {
      this.builder = builder;
   }

   @Override
   public AuthenticationConfigurationBuilder authentication() {
      return builder.authentication();
   }

   @Override
   public  MemcachedServerChildConfigurationBuilder proxyHost(String proxyHost) {
      return builder.proxyHost(proxyHost);
   }

   @Override
   public  MemcachedServerChildConfigurationBuilder proxyPort(int proxyPort) {
      return builder.proxyPort(proxyPort);
   }

   @Override
   public  MemcachedServerChildConfigurationBuilder topologyLockTimeout(long topologyLockTimeout) {
      return builder.topologyLockTimeout(topologyLockTimeout);
   }

   @Override
   public  MemcachedServerChildConfigurationBuilder topologyReplTimeout(long topologyReplTimeout) {
      return builder.topologyReplTimeout(topologyReplTimeout);
   }

   @Override
   public  MemcachedServerChildConfigurationBuilder topologyAwaitInitialTransfer(boolean topologyAwaitInitialTransfer) {
      return builder.topologyAwaitInitialTransfer(topologyAwaitInitialTransfer);
   }

   @Override
   public  MemcachedServerChildConfigurationBuilder topologyStateTransfer(boolean topologyStateTransfer) {
      return builder.topologyStateTransfer(topologyStateTransfer);
   }

}
