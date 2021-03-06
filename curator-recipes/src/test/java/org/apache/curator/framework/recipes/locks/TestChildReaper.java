/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.curator.framework.recipes.locks;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.BaseClassForTests;
import org.apache.curator.test.TestingServer;
import org.apache.curator.test.Timing;
import org.apache.curator.utils.CloseableUtils;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

public class TestChildReaper extends BaseClassForTests
{
    @Test
    public void testMaxChildren() throws Exception
    {
        server.close();

        final int LARGE_QTY = 10000;

        System.setProperty("jute.maxbuffer", "" + LARGE_QTY);
        server = new TestingServer();
        try
        {
            Timing timing = new Timing();
            ChildReaper reaper = null;
            CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new ExponentialBackoffRetry(100, 3));
            try
            {
                client.start();

                for ( int i = 0; i < LARGE_QTY; ++i )
                {
                    if ( (i % 1000) == 0 )
                    {
                        System.out.println(i);
                    }
                    client.create().creatingParentsIfNeeded().forPath("/big/node-" + i);
                }

                try
                {
                    client.getChildren().forPath("/big");
                    Assert.fail("Should have been a connection loss");
                }
                catch ( KeeperException.ConnectionLossException e )
                {
                    // expected
                }

                final CountDownLatch latch = new CountDownLatch(1);
                reaper = new ChildReaper(client, "/big", Reaper.Mode.REAP_UNTIL_DELETE, 1)
                {
                    @Override
                    protected void warnMaxChildren(String path, Stat stat)
                    {
                        latch.countDown();
                        super.warnMaxChildren(path, stat);
                    }
                };
                reaper.setMaxChildren(100);
                reaper.start();
                Assert.assertTrue(timing.awaitLatch(latch));
            }
            finally
            {
                CloseableUtils.closeQuietly(reaper);
                CloseableUtils.closeQuietly(client);
            }
        }
        finally
        {
            System.clearProperty("jute.maxbuffer");
        }
    }

    @Test
    public void testLargeNodes() throws Exception
    {
        server.close();

        final int LARGE_QTY = 10000;
        final int SMALL_QTY = 100;

        System.setProperty("jute.maxbuffer", "" + LARGE_QTY);
        server = new TestingServer();
        try
        {
            Timing timing = new Timing();
            ChildReaper reaper = null;
            CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new ExponentialBackoffRetry(100, 3));
            try
            {
                client.start();

                for ( int i = 0; i < LARGE_QTY; ++i )
                {
                    if ( (i % 1000) == 0 )
                    {
                        System.out.println(i);
                    }
                    client.create().creatingParentsIfNeeded().forPath("/big/node-" + i);

                    if ( i < SMALL_QTY )
                    {
                        client.create().creatingParentsIfNeeded().forPath("/small/node-" + i);
                    }
                }

                reaper = new ChildReaper(client, "/foo", Reaper.Mode.REAP_UNTIL_DELETE, 1);
                reaper.start();

                reaper.addPath("/big");
                reaper.addPath("/small");

                int count = -1;
                for ( int i = 0; (i < 10) && (count != 0); ++i )
                {
                    timing.sleepABit();
                    count = client.checkExists().forPath("/small").getNumChildren();
                }
                Assert.assertEquals(count, 0);
            }
            finally
            {
                CloseableUtils.closeQuietly(reaper);
                CloseableUtils.closeQuietly(client);
            }
        }
        finally
        {
            System.clearProperty("jute.maxbuffer");
        }
    }

    @Test
    public void testSomeNodes() throws Exception
    {
        Timing timing = new Timing();
        ChildReaper reaper = null;
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
        try
        {
            client.start();

            Random r = new Random();
            int nonEmptyNodes = 0;
            for ( int i = 0; i < 10; ++i )
            {
                client.create().creatingParentsIfNeeded().forPath("/test/" + Integer.toString(i));
                if ( r.nextBoolean() )
                {
                    client.create().forPath("/test/" + Integer.toString(i) + "/foo");
                    ++nonEmptyNodes;
                }
            }

            reaper = new ChildReaper(client, "/test", Reaper.Mode.REAP_UNTIL_DELETE, 1);
            reaper.start();

            timing.forWaiting().sleepABit();

            Stat stat = client.checkExists().forPath("/test");
            Assert.assertEquals(stat.getNumChildren(), nonEmptyNodes);
        }
        finally
        {
            CloseableUtils.closeQuietly(reaper);
            CloseableUtils.closeQuietly(client);
        }
    }

    @Test
    public void testSimple() throws Exception
    {
        Timing timing = new Timing();
        ChildReaper reaper = null;
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
        try
        {
            client.start();

            for ( int i = 0; i < 10; ++i )
            {
                client.create().creatingParentsIfNeeded().forPath("/test/" + Integer.toString(i));
            }

            reaper = new ChildReaper(client, "/test", Reaper.Mode.REAP_UNTIL_DELETE, 1);
            reaper.start();

            timing.forWaiting().sleepABit();

            Stat stat = client.checkExists().forPath("/test");
            Assert.assertEquals(stat.getNumChildren(), 0);
        }
        finally
        {
            CloseableUtils.closeQuietly(reaper);
            CloseableUtils.closeQuietly(client);
        }
    }

    @Test
    public void testLeaderElection() throws Exception
    {
        Timing timing = new Timing();
        ChildReaper reaper = null;
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
        LeaderLatch otherLeader = null;
        try
        {
            client.start();

            for ( int i = 0; i < 10; ++i )
            {
                client.create().creatingParentsIfNeeded().forPath("/test/" + Integer.toString(i));
            }

            otherLeader = new LeaderLatch(client, "/test-leader");
            otherLeader.start();
            otherLeader.await();

            reaper = new ChildReaper(client, "/test", Reaper.Mode.REAP_UNTIL_DELETE, ChildReaper.newExecutorService(), 1, "/test-leader");
            reaper.start();

            timing.forWaiting().sleepABit();

            //Should not have reaped anything at this point since otherLeader is still leader
            Stat stat = client.checkExists().forPath("/test");
            Assert.assertEquals(stat.getNumChildren(), 10);

            CloseableUtils.closeQuietly(otherLeader);

            timing.forWaiting().sleepABit();

            stat = client.checkExists().forPath("/test");
            Assert.assertEquals(stat.getNumChildren(), 0);
        }
        finally
        {
            CloseableUtils.closeQuietly(reaper);
            if ( otherLeader != null && otherLeader.getState() == LeaderLatch.State.STARTED )
            {
                CloseableUtils.closeQuietly(otherLeader);
            }
            CloseableUtils.closeQuietly(client);
        }
    }

    @Test
    public void testMultiPath() throws Exception
    {
        Timing timing = new Timing();
        ChildReaper reaper = null;
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
        try
        {
            client.start();

            for ( int i = 0; i < 10; ++i )
            {
                client.create().creatingParentsIfNeeded().forPath("/test1/" + Integer.toString(i));
                client.create().creatingParentsIfNeeded().forPath("/test2/" + Integer.toString(i));
                client.create().creatingParentsIfNeeded().forPath("/test3/" + Integer.toString(i));
            }

            reaper = new ChildReaper(client, "/test2", Reaper.Mode.REAP_UNTIL_DELETE, 1);
            reaper.start();
            reaper.addPath("/test1");

            timing.forWaiting().sleepABit();

            Stat stat = client.checkExists().forPath("/test1");
            Assert.assertEquals(stat.getNumChildren(), 0);
            stat = client.checkExists().forPath("/test2");
            Assert.assertEquals(stat.getNumChildren(), 0);
            stat = client.checkExists().forPath("/test3");
            Assert.assertEquals(stat.getNumChildren(), 10);
        }
        finally
        {
            CloseableUtils.closeQuietly(reaper);
            CloseableUtils.closeQuietly(client);
        }
    }

    @Test
    public void testNamespace() throws Exception
    {
        Timing timing = new Timing();
        ChildReaper reaper = null;
        CuratorFramework client = CuratorFrameworkFactory.builder()
            .connectString(server.getConnectString())
            .sessionTimeoutMs(timing.session())
            .connectionTimeoutMs(timing.connection())
            .retryPolicy(new RetryOneTime(1))
            .namespace("foo")
            .build();
        try
        {
            client.start();

            for ( int i = 0; i < 10; ++i )
            {
                client.create().creatingParentsIfNeeded().forPath("/test/" + Integer.toString(i));
            }

            reaper = new ChildReaper(client, "/test", Reaper.Mode.REAP_UNTIL_DELETE, 1);
            reaper.start();

            timing.forWaiting().sleepABit();

            Stat stat = client.checkExists().forPath("/test");
            Assert.assertEquals(stat.getNumChildren(), 0);

            stat = client.usingNamespace(null).checkExists().forPath("/foo/test");
            Assert.assertNotNull(stat);
            Assert.assertEquals(stat.getNumChildren(), 0);
        }
        finally
        {
            CloseableUtils.closeQuietly(reaper);
            CloseableUtils.closeQuietly(client);
        }
    }
}
