/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.utils;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.DebuggableScheduledThreadPoolExecutor;

public class ExpiringMap<K, V>
{
    private static final Logger logger = LoggerFactory.getLogger(ExpiringMap.class);
    private volatile boolean shutdown;

    private static class CacheableObject<T>
    {
        private final T value;
        private final long createdAt;
        private final long expiration;

        CacheableObject(T o, long e)
        {
            assert o != null;
            value = o;
            expiration = e;
            createdAt = System.currentTimeMillis();
        }

        T getValue()
        {
            return value;
        }

        boolean isReadyToDieAt(long time)
        {
            return ((time - createdAt) > expiration);
        }
    }

    // if we use more ExpiringMaps we may want to add multiple threads to this executor
    private static final ScheduledExecutorService service = new DebuggableScheduledThreadPoolExecutor("EXPIRING-MAP-REAPER");

    private final ConcurrentMap<K, CacheableObject<V>> cache = new ConcurrentHashMap<K, CacheableObject<V>>();
    private final long defaultExpiration;

    public ExpiringMap(long defaultExpiration)
    {
        this(defaultExpiration, null);
    }

    /**
     *
     * @param defaultExpiration the TTL for objects in the cache in milliseconds
     */
    public ExpiringMap(long defaultExpiration, final Function<Pair<K,V>, ?> postExpireHook)
    {
        this.defaultExpiration = defaultExpiration;

        if (defaultExpiration <= 0)
        {
            throw new IllegalArgumentException("Argument specified must be a positive number");
        }

        Runnable runnable = new Runnable()
        {
            public void run()
            {
                long start = System.currentTimeMillis();
                int n = 0;
                for (Map.Entry<K, CacheableObject<V>> entry : cache.entrySet())
                {
                    if (entry.getValue().isReadyToDieAt(start))
                    {
                        cache.remove(entry.getKey());
                        n++;
                        if (postExpireHook != null)
                            postExpireHook.apply(new Pair<K, V>(entry.getKey(), entry.getValue().getValue()));
                    }
                }
                logger.trace("Expired {} entries", n);
            }
        };
        service.scheduleWithFixedDelay(runnable, defaultExpiration / 2, defaultExpiration / 2, TimeUnit.MILLISECONDS);
    }

    public void shutdownBlocking()
    {
        service.shutdown();
        try
        {
            service.awaitTermination(defaultExpiration * 2, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            throw new AssertionError(e);
        }
    }

    public void reset()
    {
        shutdown = false;
        cache.clear();
    }

    public V put(K key, V value)
    {
        return put(key, value, this.defaultExpiration);
    }

    public V put(K key, V value, long timeout)
    {
        if (shutdown)
        {
            // StorageProxy isn't equipped to deal with "I'm nominally alive, but I can't send any messages out."
            // So we'll just sit on this thread until the rest of the server shutdown completes.
            //
            // See comments in CustomTThreadPoolServer.serve, CASSANDRA-3335, and CASSANDRA-3727.
            try
            {
                Thread.sleep(Long.MAX_VALUE);
            }
            catch (InterruptedException e)
            {
                throw new AssertionError(e);
            }
        }
        CacheableObject<V> previous = cache.put(key, new CacheableObject<V>(value, timeout));
        return (previous == null) ? null : previous.getValue();
    }

    public V get(K key)
    {
        CacheableObject<V> co = cache.get(key);
        return co == null ? null : co.getValue();
    }

    public V remove(K key)
    {
        CacheableObject<V> co = cache.remove(key);
        return co == null ? null : co.getValue();
    }

    public long getAge(K key)
    {
        CacheableObject<V> co = cache.get(key);
        return co == null ? 0 : co.createdAt;
    }

    public int size()
    {
        return cache.size();
    }

    public boolean containsKey(K key)
    {
        return cache.containsKey(key);
    }

    public boolean isEmpty()
    {
        return cache.isEmpty();
    }

    public Set<K> keySet()
    {
        return cache.keySet();
    }
}
