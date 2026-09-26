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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class DiagnosticBroadcasterTest
{
    private final DiagnosticSnapshot idle = DiagnosticSnapshot.idle();
    private final AtomicReference<DiagnosticSnapshot> current = new AtomicReference<>(idle);
    private final DiagnosticBroadcaster broadcaster = new DiagnosticBroadcaster(current::get);

    @Test
    public void handsANewSubscriberTheCurrentSnapshotAtOnce()
    {
        RecordingEmitter emitter = new RecordingEmitter();

        broadcaster.register(emitter);

        assertEquals(List.of(idle), emitter.snapshots);
        assertTrue(emitter.texts.toString(), emitter.texts.stream().anyMatch(text -> text.startsWith("event:state")));
    }

    @Test
    public void throttlesRunningSnapshotsToTheLatestOnePerFlush()
    {
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.register(emitter);
        DiagnosticSnapshot latest = null;
        for (long batch = 1; batch <= 5; batch++)
        {
            latest = running(batch);
            publish(latest);
        }

        assertEquals(1, emitter.snapshots.size());
        broadcaster.flush();
        assertEquals(2, emitter.snapshots.size());
        assertSame(latest, emitter.snapshots.get(1));
        broadcaster.flush();
        assertEquals(2, emitter.snapshots.size());
    }

    @Test
    public void sendsAFinishedSnapshotAtOnceAndDropsTheQueuedOne()
    {
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.register(emitter);
        DiagnosticSnapshot done = DiagnosticSnapshot.done("2026-09-25T15:02:11Z", "admin", "2026-09-25T15:04:40Z",
                List.of("alfresco"), null);

        publish(running(1));
        publish(done);
        broadcaster.flush();

        assertEquals(List.of(idle, done), emitter.snapshots);
    }

    @Test
    public void ignoresAStalePublishWhenTheSupplierAlreadyMovedOn()
    {
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.register(emitter);
        DiagnosticSnapshot runningNow = running(3);
        current.set(runningNow);
        DiagnosticSnapshot staleDone = DiagnosticSnapshot.done("2026-09-25T15:02:11Z", "admin",
                "2026-09-25T15:04:40Z", List.of("alfresco"), null);

        broadcaster.publish(staleDone);
        broadcaster.flush();

        assertEquals(List.of(idle, runningNow), emitter.snapshots);
    }

    @Test
    public void sendsAKeepaliveComment()
    {
        RecordingEmitter emitter = new RecordingEmitter();
        broadcaster.register(emitter);

        broadcaster.keepalive();

        assertTrue(emitter.texts.toString(), emitter.texts.contains(":keepalive\n\n"));
    }

    @Test
    public void forgetsAConnectionThatBreaksMidStream()
    {
        RecordingEmitter healthy = new RecordingEmitter();
        RecordingEmitter broken = new RecordingEmitter();
        broadcaster.register(healthy);
        broadcaster.register(broken);
        broken.failing = true;

        publish(DiagnosticSnapshot.idle().ended(DiagnosticSnapshot.CANCELLED, "2026-09-25T15:03:00Z", null, null));
        broadcaster.keepalive();

        assertEquals(1, broadcaster.subscriberCount());
        assertEquals(2, healthy.snapshots.size());
        assertTrue(healthy.texts.contains(":keepalive\n\n"));
    }

    @Test
    public void aKeepaliveOnABrokenConnectionForgetsIt()
    {
        RecordingEmitter broken = new RecordingEmitter();
        broadcaster.register(broken);
        broken.failing = true;

        broadcaster.keepalive();

        assertEquals(0, broadcaster.subscriberCount());
    }

    private void publish(DiagnosticSnapshot snapshot)
    {
        current.set(snapshot);
        broadcaster.publish(snapshot);
    }

    private static DiagnosticSnapshot running(long batch)
    {
        return DiagnosticSnapshot.running("2026-09-25T15:02:11Z", "admin", List.of("alfresco"), null)
                .withCore("alfresco", new DiagnosticSnapshot.CoreProgress("metadata.db", batch, 10L), 1);
    }

    private static final class RecordingEmitter extends SseEmitter
    {
        private final List<DiagnosticSnapshot> snapshots = new ArrayList<>();
        private final List<String> texts = new ArrayList<>();
        private boolean failing;

        @Override
        public void send(SseEventBuilder builder) throws IOException
        {
            if (failing)
            {
                throw new IOException("broken pipe");
            }
            builder.build().forEach(part -> {
                if (part.getData() instanceof DiagnosticSnapshot snapshot)
                {
                    snapshots.add(snapshot);
                }
                else
                {
                    texts.add(String.valueOf(part.getData()));
                }
            });
        }
    }
}
