package org.infinispan.server.memcached.binary.iteration;

/**
 * @author Tristan Tarrant &lt;tristan@infinispan.org&gt;
 * @since 10.0
 **/
public interface IterationState {
   String getId();

   IterationReaper getReaper();
}
