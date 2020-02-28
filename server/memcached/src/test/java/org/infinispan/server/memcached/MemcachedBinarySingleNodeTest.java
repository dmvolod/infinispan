package org.infinispan.server.memcached;

import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.test.SingleCacheManagerTest;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.testng.annotations.AfterClass;

import net.spy.memcached.MemcachedClient;

import static org.infinispan.server.memcached.test.MemcachedTestingUtil.createMemcachedBinaryClient;
import static org.infinispan.server.memcached.test.MemcachedTestingUtil.startMemcachedBinaryServer;
import static org.infinispan.server.memcached.test.MemcachedTestingUtil.killMemcachedServer;

abstract class MemcachedBinarySingleNodeTest extends SingleCacheManagerTest {
    protected MemcachedClient client;
    protected MemcachedServer server;
    protected static final int timeout = 60;

    @Override
    protected EmbeddedCacheManager createCacheManager() throws Exception {
        cacheManager = createTestCacheManager();
        server = startMemcachedBinaryServer(cacheManager);
        client = createMemcachedBinaryClient(60000, server.getPort());
        cache = cacheManager.getCache(server.getConfiguration().defaultCacheName());
        return cacheManager;
    }

    protected EmbeddedCacheManager createTestCacheManager() {
        return TestCacheManagerFactory.createCacheManager(false);
    }

    @AfterClass(alwaysRun = true)
    @Override
    protected void destroyAfterClass() {
        super.destroyAfterClass();
        log.debug("Test finished, close binary memcached server");
        shutdownClient();
        killMemcachedServer(server);
    }

    protected void shutdownClient() {
        client.shutdown();
    }
}
