package org.infinispan.server.memcached.ascii;

import java.util.Objects;

import org.infinispan.commons.marshall.ProtoStreamTypeIds;
import org.infinispan.container.versioning.EntryVersion;
import org.infinispan.container.versioning.NumericVersion;
import org.infinispan.container.versioning.SimpleClusteredVersion;
import org.infinispan.metadata.EmbeddedMetadata;
import org.infinispan.metadata.Metadata;
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoField;
import org.infinispan.protostream.annotations.ProtoTypeId;

/**
 * Memcached metadata information.
 *
 * @author Galder Zamarreño
 * @since 5.3
 * @deprecated since 10.1. Will be removed unless a binary protocol encoder/decoder is implemented.
 */
@Deprecated
@ProtoTypeId(ProtoStreamTypeIds.MEMCACHED_METADATA)
public
class MemcachedTextMetadata extends EmbeddedMetadata.EmbeddedLifespanExpirableMetadata {

   @ProtoField(number = 5, defaultValue = "0")
   final long flags;

   @ProtoFactory
   MemcachedTextMetadata(long flags, long lifespan, NumericVersion numericVersion, SimpleClusteredVersion clusteredVersion) {
      this(flags, lifespan, numericVersion != null ? numericVersion : clusteredVersion);
   }

   private MemcachedTextMetadata(long flags, long lifespan, EntryVersion version) {
      super(lifespan, version);
      this.flags = flags;
   }

   @Override
   public Metadata.Builder builder() {
      return new Builder()
            .flags(flags)
            .lifespan(lifespan())
            .version(version());
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      if (!super.equals(o)) return false;
      MemcachedTextMetadata that = (MemcachedTextMetadata) o;
      return flags == that.flags;
   }

   @Override
   public int hashCode() {
      return Objects.hash(super.hashCode(), flags);
   }

   @Override
   public String toString() {
      return "MemcachedMetadata{" +
            "flags=" + flags +
            ", version=" + version() +
            ", lifespan=" + lifespan() +
            '}';
   }

   static class Builder extends EmbeddedMetadata.Builder {

      private long flags;

      Builder flags(long flags) {
         this.flags = flags;
         return this;
      }

      @Override
      public Metadata build() {
         return new MemcachedTextMetadata(flags, lifespan == null ? -1 : lifespanUnit.toMillis(lifespan), version);
      }
   }
}
