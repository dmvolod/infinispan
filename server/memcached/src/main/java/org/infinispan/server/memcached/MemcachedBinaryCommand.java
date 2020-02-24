package org.infinispan.server.memcached;

public enum MemcachedBinaryCommand {
    Get(0x00),
    Set(0x01);

    public final int command;

    private MemcachedBinaryCommand(int command) {
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
