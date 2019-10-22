package org.infinispan.query.remote.impl;

import static org.infinispan.query.remote.client.ProtobufMetadataManagerConstants.ERRORS_KEY_SUFFIX;
import static org.infinispan.query.remote.client.ProtobufMetadataManagerConstants.PROTO_KEY_SUFFIX;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.infinispan.commands.AbstractVisitor;
import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.ReplicableCommand;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.commands.control.LockControlCommand;
import org.infinispan.commands.functional.ReadWriteKeyCommand;
import org.infinispan.commands.functional.ReadWriteKeyValueCommand;
import org.infinispan.commands.functional.ReadWriteManyCommand;
import org.infinispan.commands.functional.ReadWriteManyEntriesCommand;
import org.infinispan.commands.functional.WriteOnlyKeyCommand;
import org.infinispan.commands.functional.WriteOnlyKeyValueCommand;
import org.infinispan.commands.functional.WriteOnlyManyCommand;
import org.infinispan.commands.functional.WriteOnlyManyEntriesCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.ComputeCommand;
import org.infinispan.commands.write.ComputeIfAbsentCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.PutMapCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.commons.util.EnumUtil;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.FlagBitSets;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.distribution.ch.KeyPartitioner;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.impl.ComponentRef;
import org.infinispan.interceptors.AsyncInterceptorChain;
import org.infinispan.interceptors.BaseCustomAsyncInterceptor;
import org.infinispan.marshall.protostream.impl.SerializationContextRegistry;
import org.infinispan.metadata.EmbeddedMetadata;
import org.infinispan.metadata.Metadata;
import org.infinispan.protostream.DescriptorParserException;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.descriptors.FileDescriptor;
import org.infinispan.query.remote.ProtobufMetadataManager;
import org.infinispan.query.remote.client.ProtobufMetadataManagerConstants;
import org.infinispan.query.remote.impl.logging.Log;


/**
 * Intercepts updates to the protobuf schema file caches and updates the SerializationContext accordingly.
 *
 * <p>Must be in the interceptor chain after {@code EntryWrappingInterceptor} so that it can fail a write after
 * {@code CallInterceptor} updates the context entry but before {@code EntryWrappingInterceptor} commits the entry.</p>
 *
 * @author anistor@redhat.com
 * @since 7.0
 */
final class ProtobufMetadataManagerInterceptor extends BaseCustomAsyncInterceptor {

   private static final Log log = LogFactory.getLog(ProtobufMetadataManagerInterceptor.class, Log.class);

   private static final Metadata DEFAULT_METADATA = new EmbeddedMetadata.Builder().build();

   private CommandsFactory commandsFactory;

   private ComponentRef<AsyncInterceptorChain> invoker;

   private SerializationContext serializationContext;

   private KeyPartitioner keyPartitioner;

   private SerializationContextRegistry serializationContextRegistry;

   /**
    * A no-op callback.
    */
   private static final FileDescriptorSource.ProgressCallback EMPTY_CALLBACK = new FileDescriptorSource.ProgressCallback() {
   };

   private final class ProgressCallback implements FileDescriptorSource.ProgressCallback {

      private final InvocationContext ctx;
      private final long flagsBitSet;

      private final Set<String> errorFiles = new TreeSet<>();

      private ProgressCallback(InvocationContext ctx, long flagsBitSet) {
         this.ctx = ctx;
         this.flagsBitSet = flagsBitSet;
      }

      Set<String> getErrorFiles() {
         return errorFiles;
      }

      @Override
      public void handleError(String fileName, DescriptorParserException exception) {
         // handle first error per file, ignore the rest if any
         if (errorFiles.add(fileName)) {
            Object key = fileName + ERRORS_KEY_SUFFIX;
            VisitableCommand cmd = commandsFactory.buildPutKeyValueCommand(key, exception.getMessage(),
                  keyPartitioner.getSegment(key), DEFAULT_METADATA, flagsBitSet);
            invoker.running().invoke(ctx, cmd);
         }
      }

      @Override
      public void handleSuccess(String fileName) {
         Object key = fileName + ERRORS_KEY_SUFFIX;
         VisitableCommand cmd = commandsFactory.buildRemoveCommand(key, null, keyPartitioner.getSegment(key), flagsBitSet);
         invoker.running().invoke(ctx, cmd);
      }
   }

   /**
    * Visitor used for handling the list of modifications of a PrepareCommand.
    */
   private final AbstractVisitor serializationContextUpdaterVisitor = new AbstractVisitor() {

      @Override
      public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) {
         final String key = (String) command.getKey();
         if (shouldIntercept(key)) {
            registerProtoFile(key, (String) command.getValue());
         }
         return null;
      }

      @Override
      public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) {
         final Map<Object, Object> map = command.getMap();
         FileDescriptorSource source = new FileDescriptorSource().withProgressCallback(EMPTY_CALLBACK);
         FileDescriptorSource ctxRegistrySource = new FileDescriptorSource();
         for (Object key : map.keySet()) {
            if (shouldIntercept(key)) {
               source.addProtoFile((String) key, (String) map.get(key));
               ctxRegistrySource.addProtoFile((String) key, (String) map.get(key));
            }
         }
         try {
            serializationContext.registerProtoFiles(source);
            registerWithContextRegistry(ctxRegistrySource);
         } catch (DescriptorParserException e) {
            throw log.failedToParseProtoFile(e);
         }
         return null;
      }

      @Override
      public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) {
         final String key = (String) command.getKey();
         if (shouldIntercept(key)) {
            registerProtoFile(key, (String) command.getNewValue());
         }
         return null;
      }

      @Override
      public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) {
         final String key = (String) command.getKey();
         if (shouldIntercept(key)) {
            if (serializationContext.getFileDescriptors().containsKey(key)) {
               serializationContext.unregisterProtoFile(key);
            }
         }
         return null;
      }

      @Override
      public Object visitClearCommand(InvocationContext ctx, ClearCommand command) {
         for (String fileName : serializationContext.getFileDescriptors().keySet()) {
            serializationContext.unregisterProtoFile(fileName);
         }
         return null;
      }
   };

   private void registerProtoFile(String name, String content) {
      registerProtoFile(name, content, EMPTY_CALLBACK);
   }

   private void registerProtoFile(String name, String content, FileDescriptorSource.ProgressCallback callback) {
      try {
         // Register protoFiles with remote-query context
         serializationContext.registerProtoFiles(
               new FileDescriptorSource()
                     .withProgressCallback(callback)
                     .addProtoFile(name, content)
         );

         // Register schema with global context to allow transcoding to json
         registerWithContextRegistry(new FileDescriptorSource().addProtoFile(name, content));
      } catch (DescriptorParserException e) {
         if (name == null)
            throw log.failedToParseProtoFile(e);
         throw log.failedToParseProtoFile(name, e);
      }
   }

   private void registerWithContextRegistry(FileDescriptorSource source) {
      try {
         serializationContextRegistry.addProtoFile(SerializationContextRegistry.MarshallerType.GLOBAL, source);
      } catch (Exception ignore) {
         // Ignore any exceptions here, as they will be reported in the protobuf cache
      }
   }

   @Inject
   public void init(CommandsFactory commandsFactory, ComponentRef<AsyncInterceptorChain> invoker, KeyPartitioner keyPartitioner,
                    ProtobufMetadataManager protobufMetadataManager, SerializationContextRegistry serializationContextRegistry) {
      this.commandsFactory = commandsFactory;
      this.invoker = invoker;
      this.keyPartitioner = keyPartitioner;
      this.serializationContext = ((ProtobufMetadataManagerImpl) protobufMetadataManager).getSerializationContext();
      this.serializationContextRegistry = serializationContextRegistry;
   }

   @Override
   public Object visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command) {
      return invokeNextThenAccept(ctx, command, (rCtx, rCommand, rv) -> {
         if (!rCtx.isOriginLocal()) {
            // apply updates to the serialization context
            for (WriteCommand wc : rCommand.getModifications()) {
               wc.acceptVisitor(rCtx, serializationContextUpdaterVisitor);
            }
         }
      });
   }

   @Override
   public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) {
      final Object key = command.getKey();
      if (!(key instanceof String)) {
         throw log.keyMustBeString(key.getClass());
      }

      if (!shouldIntercept(key)) {
         return invokeNext(ctx, command);
      }

      if (ctx.isOriginLocal() && !command.hasAnyFlag(FlagBitSets.PUT_FOR_STATE_TRANSFER | FlagBitSets.SKIP_LOCKING)) {
         if (!((String) key).endsWith(PROTO_KEY_SUFFIX)) {
            throw log.keyMustBeStringEndingWithProto(key);
         }

         // lock .errors key
         LockControlCommand cmd = commandsFactory.buildLockControlCommand(ERRORS_KEY_SUFFIX, command.getFlagsBitSet(), null);
         invoker.running().invoke(ctx, cmd);
      }

      return invokeNextThenAccept(ctx, command, this::handlePutKeyValueResult);
   }

   private void handlePutKeyValueResult(InvocationContext rCtx, PutKeyValueCommand putKeyValueCommand, Object rv) {
      if (putKeyValueCommand.isSuccessful()) {
         // StateConsumerImpl uses PutKeyValueCommands with InternalCacheEntry
         // values in order to preserve timestamps, so read the value from the context
         Object key = putKeyValueCommand.getKey();
         Object value = rCtx.lookupEntry(key).getValue();
         if (!(value instanceof String)) {
            throw log.valueMustBeString(value.getClass());
         }

         long flagsBitSet = copyFlags(putKeyValueCommand);
         ProgressCallback progressCallback = null;
         if (rCtx.isOriginLocal() && !putKeyValueCommand.hasAnyFlag(FlagBitSets.PUT_FOR_STATE_TRANSFER)) {
            progressCallback = new ProgressCallback(rCtx, flagsBitSet);
            registerProtoFile((String) key, (String) value, progressCallback);
         } else {
            registerProtoFile((String) key, (String) value);
         }

         if (progressCallback != null) {
            updateGlobalErrors(rCtx, progressCallback.getErrorFiles(), flagsBitSet);
         }
      }
   }

   /**
    * For preload, we need to copy the CACHE_MODE_LOCAL flag from the put command.
    * But we also need to remove the SKIP_CACHE_STORE flag, so that existing .errors keys are updated.
    */
   private long copyFlags(FlagAffectedCommand command) {
      return EnumUtil.diffBitSets(command.getFlagsBitSet(), FlagBitSets.SKIP_CACHE_STORE);
   }

   @Override
   public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) {
      if (!ctx.isOriginLocal()) {
         return invokeNext(ctx, command);
      }

      final Map<Object, Object> map = command.getMap();

      FileDescriptorSource ctxRegistrySource = new FileDescriptorSource();
      FileDescriptorSource source = new FileDescriptorSource();
      for (Object key : map.keySet()) {
         final Object value = map.get(key);
         if (!(key instanceof String)) {
            throw log.keyMustBeString(key.getClass());
         }
         if (!(value instanceof String)) {
            throw log.valueMustBeString(value.getClass());
         }
         if (shouldIntercept(key)) {
            if (!((String) key).endsWith(PROTO_KEY_SUFFIX)) {
               throw log.keyMustBeStringEndingWithProto(key);
            }
            source.addProtoFile((String) key, (String) value);
            ctxRegistrySource.addProtoFile((String) key, (String) value);
         }
      }

      // lock .errors key
      VisitableCommand cmd = commandsFactory.buildLockControlCommand(ERRORS_KEY_SUFFIX, command.getFlagsBitSet(), null);
      invoker.running().invoke(ctx, cmd);

      return invokeNextThenAccept(ctx, command, (rCtx, rCommand, rv) -> {
         long flagsBitSet = copyFlags(rCommand);
         ProgressCallback progressCallback = null;
         if (rCtx.isOriginLocal()) {
            progressCallback = new ProgressCallback(rCtx, flagsBitSet);
            source.withProgressCallback(progressCallback);
         } else {
            source.withProgressCallback(EMPTY_CALLBACK);
         }
         try {
            serializationContext.registerProtoFiles(source);
            registerWithContextRegistry(ctxRegistrySource);
         } catch (DescriptorParserException e) {
            throw log.failedToParseProtoFile(e);
         }

         if (progressCallback != null) {
            updateGlobalErrors(rCtx, progressCallback.getErrorFiles(), flagsBitSet);
         }
      });
   }

   @Override
   public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) {
      if (ctx.isOriginLocal()) {
         if (!(command.getKey() instanceof String)) {
            throw log.keyMustBeString(command.getKey().getClass());
         }
         String key = (String) command.getKey();
         if (shouldIntercept(key)) {
            // lock .errors key
            long flagsBitSet = copyFlags(command);
            LockControlCommand lockCommand = commandsFactory.buildLockControlCommand(ERRORS_KEY_SUFFIX, flagsBitSet, null);
            invoker.running().invoke(ctx, lockCommand);

            Object keyWithSuffix = key + ERRORS_KEY_SUFFIX;
            WriteCommand writeCommand = commandsFactory.buildRemoveCommand(keyWithSuffix, null,
                  keyPartitioner.getSegment(keyWithSuffix), flagsBitSet);
            invoker.running().invoke(ctx, writeCommand);

            if (serializationContext.getFileDescriptors().containsKey(key)) {
               serializationContext.unregisterProtoFile(key);
            }

            // put error key for all unresolved files and remove error key for all resolved files
            StringBuilder sb = new StringBuilder();
            for (FileDescriptor fd : serializationContext.getFileDescriptors().values()) {
               String errorFileName = fd.getName() + ERRORS_KEY_SUFFIX;
               if (fd.isResolved()) {
                  writeCommand = commandsFactory.buildRemoveCommand(errorFileName, null,
                        keyPartitioner.getSegment(errorFileName), flagsBitSet);
                  invoker.running().invoke(ctx, writeCommand);
               } else {
                  if (sb.length() > 0) {
                     sb.append('\n');
                  }
                  sb.append(fd.getName());
                  PutKeyValueCommand put = commandsFactory.buildPutKeyValueCommand(errorFileName,
                        "One of the imported files is missing or has errors", keyPartitioner.getSegment(errorFileName),
                        DEFAULT_METADATA, flagsBitSet);
                  put.setPutIfAbsent(true);
                  invoker.running().invoke(ctx, put);
               }
            }

            if (sb.length() > 0) {
               writeCommand = commandsFactory.buildPutKeyValueCommand(ERRORS_KEY_SUFFIX, sb.toString(),
                     keyPartitioner.getSegment(ERRORS_KEY_SUFFIX), DEFAULT_METADATA, flagsBitSet);
            } else {
               writeCommand = commandsFactory.buildRemoveCommand(ERRORS_KEY_SUFFIX, null,
                     keyPartitioner.getSegment(ERRORS_KEY_SUFFIX), flagsBitSet);
            }
            invoker.running().invoke(ctx, writeCommand);
         }
      }

      return invokeNext(ctx, command);
   }

   @Override
   public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) {
      final Object key = command.getKey();
      final Object value = command.getNewValue();

      if (!ctx.isOriginLocal()) {
         return invokeNext(ctx, command);
      }
      if (!(key instanceof String)) {
         throw log.keyMustBeString(key.getClass());
      }
      if (!(value instanceof String)) {
         throw log.valueMustBeString(value.getClass());
      }
      if (!shouldIntercept(key)) {
         return invokeNext(ctx, command);
      }
      if (!((String) key).endsWith(PROTO_KEY_SUFFIX)) {
         throw log.keyMustBeStringEndingWithProto(key);
      }

      // lock .errors key
      LockControlCommand cmd = commandsFactory.buildLockControlCommand(ERRORS_KEY_SUFFIX, command.getFlagsBitSet(), null);
      invoker.running().invoke(ctx, cmd);

      return invokeNextThenAccept(ctx, command, (rCtx, rCommand, rv) -> {
         if (rCommand.isSuccessful()) {
            long flagsBitSet = copyFlags(rCommand);
            ProgressCallback progressCallback = null;
            if (rCtx.isOriginLocal()) {
               progressCallback = new ProgressCallback(rCtx, flagsBitSet);
               registerProtoFile((String) key, (String) value, progressCallback);
            } else {
               registerProtoFile((String) key, (String) value);
            }

            if (progressCallback != null) {
               updateGlobalErrors(rCtx, progressCallback.getErrorFiles(), flagsBitSet);
            }
         }
      });
   }

   @Override
   public Object visitClearCommand(InvocationContext ctx, ClearCommand command) {
      for (String fileName : serializationContext.getFileDescriptors().keySet()) {
         serializationContext.unregisterProtoFile(fileName);
      }

      return invokeNext(ctx, command);
   }

   private boolean shouldIntercept(Object key) {
      return !((String) key).endsWith(ERRORS_KEY_SUFFIX);
   }

   private void updateGlobalErrors(InvocationContext ctx, Set<String> errorFiles, long flagsBitSet) {
      // remove or update .errors accordingly
      VisitableCommand cmd;
      if (errorFiles.isEmpty()) {
         cmd = commandsFactory.buildRemoveCommand(ERRORS_KEY_SUFFIX, null, keyPartitioner.getSegment(ERRORS_KEY_SUFFIX),
               flagsBitSet);
      } else {
         StringBuilder sb = new StringBuilder();
         for (String fileName : errorFiles) {
            if (sb.length() > 0) {
               sb.append('\n');
            }
            sb.append(fileName);
         }
         cmd = commandsFactory.buildPutKeyValueCommand(ERRORS_KEY_SUFFIX, sb.toString(),
               keyPartitioner.getSegment(ERRORS_KEY_SUFFIX), DEFAULT_METADATA, flagsBitSet);
      }
      invoker.running().invoke(ctx, cmd);
   }

   // --- unsupported operations: compute, computeIfAbsent, eval or any other functional map commands  ---

   @Override
   public Object visitComputeCommand(InvocationContext ctx, ComputeCommand command) {
      return handleUnsupportedCommand(command);
   }

   @Override
   public Object visitComputeIfAbsentCommand(InvocationContext ctx, ComputeIfAbsentCommand command) {
      return handleUnsupportedCommand(command);
   }

   @Override
   public Object visitWriteOnlyKeyCommand(InvocationContext ctx, WriteOnlyKeyCommand command) {
      return handleUnsupportedCommand(command);
   }

   @Override
   public Object visitWriteOnlyKeyValueCommand(InvocationContext ctx, WriteOnlyKeyValueCommand command) {
      return handleUnsupportedCommand(command);
   }

   @Override
   public Object visitWriteOnlyManyCommand(InvocationContext ctx, WriteOnlyManyCommand command) {
      return handleUnsupportedCommand(command);
   }

   @Override
   public Object visitWriteOnlyManyEntriesCommand(InvocationContext ctx, WriteOnlyManyEntriesCommand command) {
      return handleUnsupportedCommand(command);
   }

   @Override
   public Object visitReadWriteKeyCommand(InvocationContext ctx, ReadWriteKeyCommand command) {
      return handleUnsupportedCommand(command);
   }

   @Override
   public Object visitReadWriteKeyValueCommand(InvocationContext ctx, ReadWriteKeyValueCommand command) {
      return handleUnsupportedCommand(command);
   }

   @Override
   public Object visitReadWriteManyCommand(InvocationContext ctx, ReadWriteManyCommand command) {
      return handleUnsupportedCommand(command);
   }

   @Override
   public Object visitReadWriteManyEntriesCommand(InvocationContext ctx, ReadWriteManyEntriesCommand command) {
      return handleUnsupportedCommand(command);
   }

   private Object handleUnsupportedCommand(ReplicableCommand command) {
      throw log.cacheDoesNotSupportCommand(ProtobufMetadataManagerConstants.PROTOBUF_METADATA_CACHE_NAME, command.getClass().getName());
   }
}
