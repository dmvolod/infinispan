package org.infinispan.server.memcached;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import org.infinispan.AdvancedCache;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.server.core.transport.NettyTransport;
import org.infinispan.server.memcached.logging.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;

import static org.infinispan.server.memcached.TextProtocolUtil.*;
import static org.infinispan.server.memcached.TextProtocolUtil.skipLine;

public class MemcachedBinaryDecoder extends ReplayingDecoder<MemcachedBinaryDecoderState>  {
    public MemcachedBinaryDecoder(AdvancedCache<byte[], byte[]> memcachedCache, ScheduledExecutorService scheduler,
                                  NettyTransport transport, Predicate<? super String> ignoreCache) {
        super(MemcachedBinaryDecoderState.DECODE_HEADER);
        this.cache = memcachedCache;
        this.scheduler = scheduler;
        this.transport = transport;
        this.ignoreCache = ignoreCache;
        isStatsEnabled = cache.getCacheConfiguration().jmxStatistics().enabled();
    }

    private final AdvancedCache<byte[], byte[]> cache;
    private final ScheduledExecutorService scheduler;
    protected final NettyTransport transport;
    protected final Predicate<? super String> ignoreCache;

    private final boolean isStatsEnabled;
    private final static Log log = LogFactory.getLog(MemcachedBinaryDecoder.class, Log.class);
    private final static boolean isTrace = log.isTraceEnabled();
    private ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();

    protected RequestHeader header;


    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        try {
            decodeDispatch(ctx, in, out);
        } finally {
            // reset in all cases
            byteBuffer.reset();
        }
    }

    private void decodeDispatch(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws MemcachedBinaryException {
        try {
            if (isTrace) // To aid debugging
                log.tracef("Decode using instance @%x", System.identityHashCode(this));
            MemcachedBinaryDecoderState state = state();
            switch (state) {
                case DECODE_HEADER:
                    decodeHeader(ctx, in, state, out);
                    break;
                    /*
                case DECODE_KEY:
                    decodeKey(ctx, in);
                    break;
                case DECODE_PARAMETERS:
                    decodeParameters(ctx, in, state);
                    break;
                case DECODE_VALUE:
                    decodeValue(ctx, in, state);
                    break;
                     */
            }
        } catch (IOException | NumberFormatException e) {
            //ctx.pipeline().fireExceptionCaught(new MemcachedException(CLIENT_ERROR_BAD_FORMAT + e.getMessage(), e));
        } catch (Exception e) {
            //throw new MemcachedException(SERVER_ERROR + e, e);
        }
    }

    void decodeHeader(ChannelHandlerContext ctx, ByteBuf buffer, MemcachedBinaryDecoderState state, List<Object> out)
            throws CacheUnavailableException, IOException {
        header = new RequestHeader();
        Optional<Boolean> endOfOp = readHeader(buffer, header);
        if (!endOfOp.isPresent()) {
            // Something went wrong reading the header, so get more bytes.
            // It can happen with Hot Rod if the header is completely corrupted
            return;
        }


        Channel ch = ctx.channel();
        String cacheName = cache.getName();
        if (ignoreCache.test(cacheName)) throw new CacheUnavailableException(cacheName);

    }

    private Optional<Boolean> readHeader(ByteBuf buffer, RequestHeader header) throws IOException {
        short magic = buffer.getByte(0);
        if ((MemcachedBinaryMagic.valueOfCode(magic) == null) || (MemcachedBinaryMagic.valueOfCode(magic) != MemcachedBinaryMagic.Request)) {
            return Optional.of(false);
        }

        boolean endOfOp = readElement(buffer, byteBuffer);
        String streamOp = extractString(byteBuffer);
        MemcachedOperation op = toRequest(streamOp, endOfOp, buffer);
        if (op == MemcachedOperation.StatsRequest && !endOfOp) {
            String line = readDiscardedLine(buffer).trim();
            if (!line.isEmpty())
                throw new StreamCorruptedException("Stats command does not accept arguments: " + line);
            else
                endOfOp = true;
        }
        if (op == MemcachedOperation.VerbosityRequest) {
            if (!endOfOp)
                skipLine(buffer); // Read rest of line to clear the operation
            throw new StreamCorruptedException("Memcached 'verbosity' command is unsupported");
        }

        header.operation = op;
        return Optional.of(endOfOp);
    }

    class MemcachedBinaryException extends Exception {
        MemcachedBinaryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class RequestHeader {
        MemcachedBinaryMagic magic;
        MemcachedBinaryCommand command;
        short keyLength;
        byte exstrasLength;
        byte dataType;
        byte vBucketId;
        int bodyLength;
        int opaque;
        int cas;

        @Override
        public String toString() {
            return "RequestHeader{" +
                    "command=" + command +
                    '}';
        }
    }

    enum MemcachedBinaryMagic {
        Request(0x80),
        Response(0x81);

        public final int magic;

        private MemcachedBinaryMagic(int magic) {
            this.magic = magic;
        }

        public static MemcachedBinaryMagic valueOfCode(int code) {
            for (MemcachedBinaryMagic c : values()) {
                if (c.magic == code) {
                    return c;
                }
            }
            return null;
        }
    }
}
