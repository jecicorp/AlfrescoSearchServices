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

package org.alfresco.indexing.progress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Pins how readings accumulate into a window and are served as a snapshot.
 */
public class ProgressServiceTest
{
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-22T10:00:00Z"));
    private final FakeSource source = new FakeSource();

    @Test
    public void servesNoCoreBeforeTheFirstSample()
    {
        ProgressService service = newService(Duration.ofSeconds(60));

        ProgressSnapshot snapshot = service.snapshot();

        assertTrue(snapshot.cores().isEmpty());
        assertEquals(clock.instant(), snapshot.generatedAt());
    }

    @Test
    public void measuresNoThroughputFromASingleSample()
    {
        ProgressService service = newService(Duration.ofSeconds(60));
        source.readings = List.of(metadata(100L, 900L));
        service.sample();

        TrackerProgress progress = onlyTracker(service.snapshot());

        assertEquals(Long.valueOf(100L), progress.done());
        assertEquals(Long.valueOf(900L), progress.remaining());
        assertNull(progress.ratePerSec());
        assertNull(progress.etaSeconds());
    }

    @Test
    public void derivesThroughputAndEtaAcrossSamples()
    {
        ProgressService service = newService(Duration.ofSeconds(60));
        source.readings = List.of(metadata(0L, 1_000L));
        service.sample();
        clock.advance(Duration.ofSeconds(10));
        source.readings = List.of(metadata(500L, 500L));
        service.sample();

        TrackerProgress progress = onlyTracker(service.snapshot());

        assertEquals(50.0d, progress.ratePerSec(), 0.001d);
        assertEquals(Long.valueOf(10L), progress.etaSeconds());
        assertEquals(clock.instant(), progress.lastSampleAt());
    }

    @Test
    public void forgetsSamplesOlderThanTheWindow()
    {
        ProgressService service = newService(Duration.ofSeconds(60));
        source.readings = List.of(metadata(0L, 1_000L));
        service.sample();
        clock.advance(Duration.ofSeconds(100));
        source.readings = List.of(metadata(10_000L, 900L));
        service.sample();
        clock.advance(Duration.ofSeconds(10));
        source.readings = List.of(metadata(10_100L, 800L));
        service.sample();

        TrackerProgress progress = onlyTracker(service.snapshot());

        assertEquals("the 100 s old sample must not flatten the measured rate", 10.0d,
                progress.ratePerSec(), 0.001d);
    }

    @Test
    public void expressesTheBacklogInNodesWhenTheUnitIsTheTransaction()
    {
        ProgressService service = newService(Duration.ofSeconds(60));
        source.readings = List.of(new TrackerReading("alfresco", "metadata", true, 0L, 200L, 1.5d));
        service.sample();

        TrackerProgress progress = onlyTracker(service.snapshot());

        assertEquals(Long.valueOf(300L), progress.remainingNodes());
    }

    @Test
    public void leavesTheNodeBacklogUnsetWhenNoMeanIsAvailable()
    {
        ProgressService service = newService(Duration.ofSeconds(60));
        source.readings = List.of(metadata(0L, 200L));
        service.sample();

        assertNull(onlyTracker(service.snapshot()).remainingNodes());
    }

    @Test
    public void keepsATrackerThatCountsNothingButReportsWhetherItRuns()
    {
        ProgressService service = newService(Duration.ofSeconds(60));
        source.readings = List.of(new TrackerReading("alfresco", "cascade", true, null, null, null));
        service.sample();

        TrackerProgress progress = onlyTracker(service.snapshot());

        assertEquals("cascade", progress.tracker());
        assertTrue(progress.active());
        assertNull(progress.remaining());
        assertNull(progress.etaSeconds());
        assertNull(progress.peakRemaining());
    }

    @Test
    public void timesTheDrainOfABacklogThatHasNoCursor()
    {
        ProgressService service = newService(Duration.ofSeconds(60));
        source.readings = List.of(cascade(400L));
        service.sample();
        clock.advance(Duration.ofSeconds(10));
        source.readings = List.of(cascade(300L));
        service.sample();

        TrackerProgress progress = onlyTracker(service.snapshot());

        assertEquals(10.0d, progress.ratePerSec(), 0.001d);
        assertEquals(Long.valueOf(30L), progress.etaSeconds());
    }

    @Test
    public void remembersTheWorstBacklogSoAProgressBarHasADenominator()
    {
        ProgressService service = newService(Duration.ofSeconds(60));
        source.readings = List.of(cascade(400L));
        service.sample();
        source.readings = List.of(cascade(120L));
        service.sample();

        assertEquals(Long.valueOf(400L), onlyTracker(service.snapshot()).peakRemaining());
    }

    @Test
    public void raisesThePeakWhenTheBacklogGrowsPastIt()
    {
        ProgressService service = newService(Duration.ofSeconds(60));
        source.readings = List.of(cascade(400L));
        service.sample();
        source.readings = List.of(cascade(900L));
        service.sample();

        assertEquals(Long.valueOf(900L), onlyTracker(service.snapshot()).peakRemaining());
    }

    @Test
    public void keepsThePeakBeyondTheMeasurementWindow()
    {
        ProgressService service = newService(Duration.ofSeconds(60));
        source.readings = List.of(cascade(400L));
        service.sample();
        clock.advance(Duration.ofSeconds(300));
        source.readings = List.of(cascade(50L));
        service.sample();

        assertEquals("the worst backlog outlives the window the rate is measured over",
                Long.valueOf(400L), onlyTracker(service.snapshot()).peakRemaining());
    }

    @Test
    public void dropsACoreThatStopsBeingReported()
    {
        ProgressService service = newService(Duration.ofSeconds(60));
        source.readings = List.of(metadata(0L, 10L));
        service.sample();
        source.readings = List.of();
        service.sample();

        assertTrue("a removed core must not linger with stale figures",
                service.snapshot().cores().isEmpty());
    }

    private ProgressService newService(Duration window)
    {
        return new ProgressService(source, clock, window);
    }

    private static TrackerReading cascade(long remaining)
    {
        return new TrackerReading("alfresco", "cascade", true, null, remaining, null);
    }

    private static TrackerReading metadata(long done, long remaining)
    {
        return new TrackerReading("alfresco", "metadata", true, done, remaining, null);
    }

    private static TrackerProgress onlyTracker(ProgressSnapshot snapshot)
    {
        assertEquals(1, snapshot.cores().size());
        assertEquals(1, snapshot.cores().get(0).trackers().size());
        return snapshot.cores().get(0).trackers().get(0);
    }

    private static final class FakeSource implements ProgressSource
    {
        private List<TrackerReading> readings = new ArrayList<>();

        @Override
        public List<TrackerReading> read()
        {
            return readings;
        }
    }

    private static final class MutableClock extends java.time.Clock
    {
        private Instant now;

        private MutableClock(Instant now)
        {
            this.now = now;
        }

        private void advance(Duration by)
        {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone()
        {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }
}
