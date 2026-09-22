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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a bounded window of readings per tracker and serves the derived progress.
 *
 * <p>Throughput is derived from the tracker's cursor rather than from its backlog: the
 * backlog falls as the tracker indexes and rises as the repository ingests, so its
 * derivative measures neither of the two on its own.
 *
 * <p>The worst backlog seen is kept outside the window, and outlives it: a tracker with no
 * cursor has no other denominator to show progress against.
 */
public class ProgressService
{
    private final ProgressSource source;
    private final Clock clock;
    private final Duration window;
    private final Map<String, List<ProgressSample>> windows = new ConcurrentHashMap<>();
    private final Map<String, Long> peaks = new ConcurrentHashMap<>();
    private final Map<String, ProgressRate> rates = new ConcurrentHashMap<>();
    private volatile Map<String, TrackerReading> latest = new LinkedHashMap<>();

    public ProgressService(ProgressSource source, Clock clock, Duration window)
    {
        this.source = source;
        this.clock = clock;
        this.window = window;
    }

    /** Takes one reading of every tracker, appends it to its window and derives its rate. */
    public void sample()
    {
        long now = clock.millis();
        long floor = now - window.toMillis();

        Map<String, TrackerReading> readings = new LinkedHashMap<>();
        for (TrackerReading reading : source.read())
        {
            String key = key(reading);
            readings.put(key, reading);
            if (reading.remaining() == null)
            {
                continue;
            }

            List<ProgressSample> samples = new ArrayList<>(windows.getOrDefault(key, List.of()));
            samples.add(new ProgressSample(now, reading.done(), reading.remaining()));
            samples.removeIf(sample -> sample.timestampMillis() < floor);
            windows.put(key, List.copyOf(samples));
            peaks.merge(key, reading.remaining(), Math::max);

            rates.put(key, ProgressEstimator.estimate(List.copyOf(samples)));
        }

        windows.keySet().retainAll(readings.keySet());
        peaks.keySet().retainAll(readings.keySet());
        rates.keySet().retainAll(readings.keySet());
        latest = readings;
    }

    /** @return the progress of every tracker reported by the last reading */
    public ProgressSnapshot snapshot()
    {
        Instant generatedAt = clock.instant();
        Map<String, List<TrackerProgress>> byCore = new LinkedHashMap<>();

        for (Map.Entry<String, TrackerReading> entry : latest.entrySet())
        {
            String key = entry.getKey();
            TrackerReading reading = entry.getValue();
            List<ProgressSample> samples = windows.getOrDefault(key, List.of());
            ProgressRate rate = rates.getOrDefault(key, ProgressRate.unknown());
            Instant lastSampleAt = samples.isEmpty()
                    ? null
                    : Instant.ofEpochMilli(samples.get(samples.size() - 1).timestampMillis());

            byCore.computeIfAbsent(reading.core(), core -> new ArrayList<>())
                    .add(new TrackerProgress(reading.tracker(), reading.active(),
                            reading.done(), reading.remaining(), remainingNodes(reading),
                            peaks.get(key), rate.ratePerSec(), rate.etaSeconds(),
                            rate.trend(), lastSampleAt));
        }

        List<CoreProgress> cores = new ArrayList<>();
        byCore.forEach((core, trackers) -> cores.add(new CoreProgress(core, List.copyOf(trackers))));
        return new ProgressSnapshot(generatedAt, List.copyOf(cores));
    }



    private static Long remainingNodes(TrackerReading reading)
    {
        if (reading.remaining() == null || reading.docsPerUnit() == null)
        {
            return null;
        }
        return Math.round(reading.remaining() * reading.docsPerUnit());
    }

    private static String key(TrackerReading reading)
    {
        return reading.core() + '/' + reading.tracker();
    }
}
