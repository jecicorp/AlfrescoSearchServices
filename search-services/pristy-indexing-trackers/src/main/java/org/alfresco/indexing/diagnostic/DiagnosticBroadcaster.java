/*-
 * #%L
 * Alfresco Indexing Trackers
 * %%
 * Copyright (C) 2026 Jeci SARL - https://jeci.fr
 * %%
 * This file is part of the Pristy software, developed by Jeci SARL.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

package org.alfresco.indexing.diagnostic;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans the diagnostic snapshot out to every open SSE connection from its own sender thread. Each event
 * carries the snapshot current at send time; running snapshots are throttled to the latest one per
 * interval, any other state is sent at once, and a keepalive comment runs on its own cadence.
 */
public class DiagnosticBroadcaster
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticBroadcaster.class);

    static final String EVENT_NAME = "state";
    static final String KEEPALIVE = "keepalive";
    static final String SENDER_THREAD = "diagnostic-stream";

    private final Supplier<DiagnosticSnapshot> current;
    private final ScheduledExecutorService sender;
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean pending = new AtomicBoolean(false);
    private final AtomicBoolean dispatchQueued = new AtomicBoolean(false);

    /**
     * @param current                the snapshot to send, read at send time
     * @param minEventIntervalMillis minimum interval between two running events
     * @param keepaliveMillis        interval of the keepalive comment
     */
    public DiagnosticBroadcaster(Supplier<DiagnosticSnapshot> current, long minEventIntervalMillis,
            long keepaliveMillis)
    {
        this(current, defaultSender(), minEventIntervalMillis, keepaliveMillis);
    }

    DiagnosticBroadcaster(Supplier<DiagnosticSnapshot> current, ScheduledExecutorService sender,
            long minEventIntervalMillis, long keepaliveMillis)
    {
        this.current = current;
        this.sender = sender;
        sender.scheduleWithFixedDelay(guarded(this::flush), minEventIntervalMillis, minEventIntervalMillis,
                TimeUnit.MILLISECONDS);
        sender.scheduleWithFixedDelay(guarded(this::keepalive), keepaliveMillis, keepaliveMillis,
                TimeUnit.MILLISECONDS);
    }

    private static ScheduledExecutorService defaultSender()
    {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, SENDER_THREAD);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Registers a connection and serves it the current snapshot as its first event.
     *
     * @param emitter the connection to serve
     */
    public void register(SseEmitter emitter)
    {
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(error -> subscribers.remove(emitter));
        submit(() -> send(emitter, current.get()));
    }

    /** Announces that the snapshot changed; returns at once, the event is sent by the sender thread. */
    public void trigger()
    {
        pending.set(true);
        if (dispatchQueued.compareAndSet(false, true))
        {
            submit(this::dispatch);
        }
    }

    /** Stops the sender thread. */
    public void shutdown()
    {
        sender.shutdownNow();
    }

    /** @return how many connections are open */
    public int subscriberCount()
    {
        return subscribers.size();
    }

    void flush()
    {
        if (pending.getAndSet(false))
        {
            broadcast(current.get());
        }
    }

    void keepalive()
    {
        for (SseEmitter emitter : subscribers)
        {
            try
            {
                emitter.send(SseEmitter.event().comment(KEEPALIVE));
            }
            catch (Exception e)
            {
                subscribers.remove(emitter);
            }
        }
    }

    private void dispatch()
    {
        dispatchQueued.set(false);
        DiagnosticSnapshot actual = current.get();
        if (DiagnosticSnapshot.RUNNING.equals(actual.state()))
        {
            return;
        }
        pending.set(false);
        broadcast(actual);
    }

    private void submit(Runnable task)
    {
        try
        {
            sender.execute(guarded(task));
        }
        catch (RejectedExecutionException e)
        {
            LOGGER.debug("The diagnostic stream is shut down", e);
        }
    }

    private static Runnable guarded(Runnable task)
    {
        return () -> {
            try
            {
                task.run();
            }
            catch (RuntimeException e)
            {
                LOGGER.warn("A diagnostic stream task failed", e);
            }
        };
    }

    private void broadcast(DiagnosticSnapshot snapshot)
    {
        for (SseEmitter emitter : subscribers)
        {
            send(emitter, snapshot);
        }
    }

    private void send(SseEmitter emitter, DiagnosticSnapshot snapshot)
    {
        try
        {
            emitter.send(SseEmitter.event().name(EVENT_NAME).data(snapshot));
        }
        catch (Exception e)
        {
            subscribers.remove(emitter);
        }
    }
}
