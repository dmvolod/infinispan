package org.infinispan.server.memcached;

import io.netty.buffer.ByteBuf;
//import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import org.infinispan.AdvancedCache;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.server.core.transport.NettyTransport;
import org.infinispan.server.memcached.logging.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;

import static org.infinispan.server.memcached.BinaryProtocolUtil.decodeByte;
import static org.infinispan.server.memcached.BinaryProtocolUtil.decodeShort;
import static org.infinispan.server.memcached.BinaryProtocolUtil.decodeUnsignedInt;

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
    private long offset = 0;
    private ByteArrayOutputStream element = new ByteArrayOutputStream();

    long defaultLifespanTime;
    long defaultMaxIdleTime;

    protected byte[] extras;
    protected byte[] key;
    protected byte[] rawValue;
    protected Configuration cacheConfiguration;

    protected RequestHeader header;

    protected static final int HEADER_PACKAGE_SIZE = 24;
    protected static final int HEADER_DATA_TYPE = 0x00;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        try {
            decodeDispatch(ctx, in, out);
        } finally {
            // reset in all cases
            element.reset();
            offset = 0;
        }
    }

    private void decodeDispatch(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws MemcachedBinaryException {
        try {
            if (isTrace) // To aid debugging
                log.tracef("Decode using instance @%x", System.identityHashCode(this));
            MemcachedBinaryDecoderState state = state();
            switch (state) {
                case DECODE_HEADER:
                    decodeHeader(ctx, in);
                    break;
                case DECODE_EXTRAS:
                    decodeExtras(ctx, in);
                    break;
                case DECODE_KEY:
                    decodeKey(ctx, in);
                    break;
                case DECODE_VALUE:
                    decodeValue(ctx, in);
                    break;
            }
        } catch (IOException | NumberFormatException e) {
            //ctx.pipeline().fireExceptionCaught(new MemcachedException(CLIENT_ERROR_BAD_FORMAT + e.getMessage(), e));
        } catch (Exception e) {
            //throw new MemcachedException(SERVER_ERROR + e, e);
        }
    }

    void decodeHeader(ChannelHandlerContext ctx, ByteBuf buffer) throws CacheUnavailableException, IOException {
        header = new RequestHeader();
        boolean hasHeader = readHeader(buffer, header);
        if (!hasHeader) {
            // Something went wrong reading the header, so get more bytes.
            return;
        }

        String cacheName = cache.getName();
        if (ignoreCache.test(cacheName)) {
            throw new CacheUnavailableException(cacheName);
        }
        cacheConfiguration = getCacheConfiguration();
        defaultLifespanTime = cacheConfiguration.expiration().lifespan();
        defaultMaxIdleTime = cacheConfiguration.expiration().maxIdle();

        checkpoint(MemcachedBinaryDecoderState.DECODE_EXTRAS);
    }

    void decodeExtras(ChannelHandlerContext ctx, ByteBuf buffer) throws IOException {
        boolean hasExtras = readElement(buffer, element, header.exstrasLength);
        if (!hasExtras) {
            return;
        }
        extras = element.toByteArray();
        System.out.println("extras: " + Arrays.toString(extras));
        System.out.println("buffer: " + buffer);

        checkpoint(MemcachedBinaryDecoderState.DECODE_KEY);
    }

    void decodeKey(ChannelHandlerContext ctx, ByteBuf buffer) throws IOException {
        boolean hasKey = readElement(buffer, element, header.keyLength);
        if (!hasKey) {
            return;
        }
        key = element.toByteArray();
        System.out.println("key: " + new String(key, StandardCharsets.UTF_8));
        System.out.println("buffer: " + buffer);

        checkpoint(MemcachedBinaryDecoderState.DECODE_VALUE);
    }

    void decodeValue(ChannelHandlerContext ctx, ByteBuf buffer) throws IOException {
        boolean hasValue = readElement(buffer, element, header.bodyLength - header.exstrasLength - header.keyLength);
        if (!hasValue) {
            return;
        }
        rawValue = element.toByteArray();

        System.out.println("value: " + new String(rawValue, StandardCharsets.UTF_8));
        System.out.println("buffer: " + buffer);
    }

    private boolean readHeader(ByteBuf buffer, RequestHeader header) throws IOException {
        boolean hasHeader = readElement(buffer, element, HEADER_PACKAGE_SIZE);
        if (!hasHeader) {
            return false;
        }

        byte[] rawHeader = element.toByteArray();
        byte magic = rawHeader[0];
        short command = rawHeader[1];
        int dataType = decodeByte(rawHeader, 5);

        System.out.println("offset: " + offset);
        System.out.println("magic: " + magic);
        System.out.println("request: " + MemcachedBinaryMagic.valueOfCode(magic));
        System.out.println("buffer: " + buffer);

        if ((MemcachedBinaryMagic.valueOfCode(magic) == null) || (MemcachedBinaryMagic.valueOfCode(magic) != MemcachedBinaryMagic.Request)) {
            throw new StreamCorruptedException("Invalid magic: " + magic);
        } else {
            header.magic = MemcachedBinaryMagic.valueOfCode(magic);
        }

        if (MemcachedBinaryCommand.valueOfCode(command) == null) {
            throw new StreamCorruptedException("Unexpected response command value: " + command);
        } else {
            header.command = MemcachedBinaryCommand.valueOfCode(command);
        }

        if (dataType == HEADER_DATA_TYPE) {
            header.dataType = dataType;
        } else {
            throw new StreamCorruptedException("Unexpected data type value: " + dataType);
        }

        header.keyLength = decodeShort(rawHeader, 2);
        header.exstrasLength = decodeByte(rawHeader, 4);
        header.vBucketId = decodeShort(rawHeader, 6);
        header.bodyLength = decodeUnsignedInt(rawHeader, 8);
        header.opaque = decodeUnsignedInt(rawHeader, 12);
        header.cas = decodeUnsignedInt(rawHeader, 16);

        System.out.println("keyLength: " + header.keyLength);
        System.out.println("exstrasLength: " + header.exstrasLength);
        System.out.println("dataType: " + header.dataType);
        System.out.println("vBucketId: " + header.vBucketId);
        System.out.println("bodyLength: " + header.bodyLength);
        System.out.println("opaque: " + header.opaque);
        System.out.println("cas: " + header.cas);

        return true;
    }

    private boolean readElement(ByteBuf buffer, ByteArrayOutputStream element, long size) throws IOException {
        System.out.println("size: " + size);
        System.out.println("offset: " + offset);
        while (offset < size) {
            byte next;
            try {
                next = buffer.readByte();
                offset++;
            } catch (IndexOutOfBoundsException e) {
                return false;
            }
            element.write(next);
        }
        log.debugf("Reading %d element bytes", size);
        System.out.println("Reading element bytes: " + size);
        return true;
    }

    private Configuration getCacheConfiguration() {
        return cache.getCacheConfiguration();
    }

    @Override
    protected void checkpoint(MemcachedBinaryDecoderState state) {
        element.reset();
        super.checkpoint(state);
    }

    class MemcachedBinaryException extends Exception {
        MemcachedBinaryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class RequestHeader {
        MemcachedBinaryMagic magic;
        MemcachedBinaryCommand command;
        int keyLength;
        int exstrasLength;
        int dataType;
        int vBucketId;
        long bodyLength;
        long opaque;
        long cas;

        @Override
        public String toString() {
            return "RequestHeader{" +
                    "command=" + command +
                    '}';
        }
    }

    enum MemcachedBinaryMagic {
        Request((byte)0x80),
        Response((byte)0x81);

        public final byte magic;

        private MemcachedBinaryMagic(byte magic) {
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
