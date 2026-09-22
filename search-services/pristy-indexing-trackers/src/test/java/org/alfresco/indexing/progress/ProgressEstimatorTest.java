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

import java.util.List;

import org.junit.Test;

/**
 * Pins the throughput and ETA computation.
 *
 * <p>Throughput is measured on {@code done}, the tracker's own cursor, never on
 * {@code remaining}: the latter is a difference between two moving quantities and falls
 * while an import raises it, so its derivative mixes indexing speed with ingestion speed.
 * A tracker that keeps no cursor at all leaves the drain of its backlog as the only
 * measurable thing, and falls back to it.
 */
public class ProgressEstimatorTest
{
    @Test
    public void measuresThroughputOnTheCursorAndDividesTheRemainderByIt()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(0L, 0L, 800L),
                new ProgressSample(1_000L, 100L, 700L),
                new ProgressSample(2_000L, 200L, 600L),
                new ProgressSample(3_000L, 300L, 500L));

        ProgressRate rate = ProgressEstimator.estimate(samples);

        assertEquals(100.0d, rate.ratePerSec(), 0.001d);
        assertEquals(Long.valueOf(5L), rate.etaSeconds());
    }

    @Test
    public void reportsAMeasuredZeroWhenTheCursorHasNotMoved()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(0L, 42L, 500L),
                new ProgressSample(2_000L, 42L, 500L));

        ProgressRate rate = ProgressEstimator.estimate(samples);

        assertEquals(0.0d, rate.ratePerSec(), 0.001d);
        assertNull("a stalled tracker has no ETA, and must not be reported as instant",
                rate.etaSeconds());
    }

    @Test
    public void measuresNothingFromASingleSample()
    {
        ProgressRate rate = ProgressEstimator.estimate(
                List.of(new ProgressSample(0L, 42L, 500L)));

        assertNull("one sample carries no duration to divide by", rate.ratePerSec());
        assertNull(rate.etaSeconds());
    }

    @Test
    public void measuresNothingFromAnEmptyWindow()
    {
        ProgressRate rate = ProgressEstimator.estimate(List.of());

        assertNull(rate.ratePerSec());
        assertNull(rate.etaSeconds());
    }

    @Test
    public void keepsTheThroughputAndStretchesTheEtaWhenTheBacklogGrows()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(0L, 0L, 500L),
                new ProgressSample(1_000L, 100L, 600L),
                new ProgressSample(2_000L, 200L, 700L),
                new ProgressSample(3_000L, 300L, 800L));

        ProgressRate rate = ProgressEstimator.estimate(samples);

        assertEquals("the cursor advanced at a steady 100/s throughout", 100.0d,
                rate.ratePerSec(), 0.001d);
        assertEquals("ingestion outrunning indexing must lengthen the ETA, not void it",
                Long.valueOf(8L), rate.etaSeconds());
    }

    @Test
    public void reportsACaughtUpTrackerAsZeroSecondsAway()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(0L, 0L, 300L),
                new ProgressSample(3_000L, 300L, 0L));

        ProgressRate rate = ProgressEstimator.estimate(samples);

        assertEquals(100.0d, rate.ratePerSec(), 0.001d);
        assertEquals(Long.valueOf(0L), rate.etaSeconds());
    }

    @Test
    public void measuresNothingWhenTheWindowSpansNoTime()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(1_000L, 0L, 500L),
                new ProgressSample(1_000L, 100L, 400L));

        ProgressRate rate = ProgressEstimator.estimate(samples);

        assertNull("dividing by a zero-length window would report an infinite rate",
                rate.ratePerSec());
        assertNull(rate.etaSeconds());
    }

    @Test
    public void fallsBackOnTheBacklogDrainWhenThereIsNoCursorAtAll()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(0L, null, 400L),
                new ProgressSample(1_500L, null, 250L),
                new ProgressSample(3_000L, null, 100L));

        ProgressRate rate = ProgressEstimator.estimate(samples);

        assertEquals(100.0d, rate.ratePerSec(), 0.001d);
        assertEquals(Long.valueOf(1L), rate.etaSeconds());
    }

    @Test
    public void reportsAMeasuredZeroWhenABacklogWithNoCursorDoesNotMove()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(0L, null, 400L),
                new ProgressSample(2_000L, null, 400L));

        ProgressRate rate = ProgressEstimator.estimate(samples);

        assertEquals(0.0d, rate.ratePerSec(), 0.001d);
        assertNull(rate.etaSeconds());
    }

    @Test
    public void measuresNothingWhileABacklogWithNoCursorIsStillFillingUp()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(0L, null, 100L),
                new ProgressSample(2_000L, null, 400L));

        ProgressRate rate = ProgressEstimator.estimate(samples);

        assertNull("a queue being fed faster than it drains has no meaningful speed",
                rate.ratePerSec());
        assertNull(rate.etaSeconds());
    }

    @Test
    public void fitsEverySampleRatherThanJustTheTwoEnds()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(0L, 0L, 1_000L),
                new ProgressSample(1_000L, 100L, 900L),
                new ProgressSample(2_000L, 200L, 800L),
                new ProgressSample(3_000L, 300L, 700L),
                new ProgressSample(4_000L, 400L, 600L),
                new ProgressSample(5_000L, 700L, 300L));

        ProgressRate rate = ProgressEstimator.estimate(samples);

        assertEquals("an end-to-end delta would read 140/s off that last burst alone",
                128.571d, rate.ratePerSec(), 0.001d);
    }

    @Test
    public void reportsAnAcceleratingTrackerAsRising()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(0L, 0L, 1_000L),
                new ProgressSample(1_000L, 10L, 990L),
                new ProgressSample(2_000L, 20L, 980L),
                new ProgressSample(3_000L, 120L, 880L),
                new ProgressSample(4_000L, 220L, 780L));

        assertEquals(ProgressRate.Trend.RISING, ProgressEstimator.estimate(samples).trend());
    }

    @Test
    public void reportsASlowingTrackerAsFalling()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(0L, 0L, 1_000L),
                new ProgressSample(1_000L, 100L, 900L),
                new ProgressSample(2_000L, 200L, 800L),
                new ProgressSample(3_000L, 210L, 790L),
                new ProgressSample(4_000L, 220L, 780L));

        assertEquals(ProgressRate.Trend.FALLING, ProgressEstimator.estimate(samples).trend());
    }

    @Test
    public void ignoresAChangeOfPaceTooSmallToMeananything()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(0L, 0L, 1_000L),
                new ProgressSample(1_000L, 100L, 900L),
                new ProgressSample(2_000L, 200L, 800L),
                new ProgressSample(3_000L, 305L, 695L),
                new ProgressSample(4_000L, 410L, 590L));

        assertEquals("5% is noise, not a trend",
                ProgressRate.Trend.STEADY, ProgressEstimator.estimate(samples).trend());
    }

    @Test
    public void callsNoTrendBeforeItHasTwoHalvesToCompare()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(0L, 0L, 1_000L),
                new ProgressSample(1_000L, 100L, 900L),
                new ProgressSample(2_000L, 200L, 800L));

        assertNull(ProgressEstimator.estimate(samples).trend());
    }

    @Test
    public void ignoresACursorResetRatherThanReportingANegativeRate()
    {
        List<ProgressSample> samples = List.of(
                new ProgressSample(0L, 5_000L, 100L),
                new ProgressSample(1_000L, 0L, 5_100L));

        ProgressRate rate = ProgressEstimator.estimate(samples);

        assertNull("a purge resets the cursor; that is not negative throughput",
                rate.ratePerSec());
        assertNull(rate.etaSeconds());
    }
}
