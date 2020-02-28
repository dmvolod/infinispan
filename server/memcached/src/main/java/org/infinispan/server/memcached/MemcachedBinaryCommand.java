package org.infinispan.server.memcached;

public enum MemcachedBinaryCommand {
    Get((byte)0x00),
    Set((byte)0x01);

    public final byte command;

    private MemcachedBinaryCommand(byte command) {
        this.command = command;
    }

    public static MemcachedBinaryCommand valueOfCode(int code) {
        for (MemcachedBinaryCommand c : values()) {
            if (c.command == code) {
                return c;
            }
        }
        return null;
    }
}
