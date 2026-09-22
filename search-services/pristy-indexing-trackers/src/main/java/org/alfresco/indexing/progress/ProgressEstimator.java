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

import java.util.List;

/**
 * Derives a throughput, an ETA and a trend from a window of samples.
 *
 * <p>The throughput is the slope of a least-squares fit over every sample of the window.
 * A delta between its two ends would use two samples out of thirty and carry the noise of
 * a two-point estimate with the latency of a one-minute window; trackers work in cycles,
 * so those two ends land at arbitrary phases and the figure oscillates.
 *
 * <p>The cursor is the measurement of choice. A tracker that keeps none leaves the drain
 * of its own backlog as the only thing that can be timed, and that reading is only valid
 * while the backlog shrinks: a queue being fed faster than it empties reports no speed
 * rather than a negative one.
 */
public final class ProgressEstimator
{
    private static final double TREND_DEAD_BAND = 0.10d;

    private ProgressEstimator()
    {
    }

    /**
     * @param samples the window, oldest first
     * @return the measured throughput, the resulting ETA and the trend
     */
    public static ProgressRate estimate(List<ProgressSample> samples)
    {
        Double ratePerSec = slope(samples);
        if (ratePerSec == null || ratePerSec < 0.0d)
        {
            return ProgressRate.unknown();
        }

        long remaining = samples.get(samples.size() - 1).remaining();
        Long etaSeconds;
        if (remaining <= 0L)
        {
            etaSeconds = 0L;
        }
        else if (ratePerSec <= 0.0d)
        {
            etaSeconds = null;
        }
        else
        {
            etaSeconds = (long) Math.ceil(remaining / ratePerSec);
        }
        return new ProgressRate(ratePerSec, etaSeconds, trend(samples));
    }

    private static ProgressRate.Trend trend(List<ProgressSample> samples)
    {
        int mid = samples.size() / 2;
        if (mid < 2 || samples.size() - mid < 2)
        {
            return null;
        }
        Double older = slope(samples.subList(0, mid));
        Double newer = slope(samples.subList(mid, samples.size()));
        if (older == null || newer == null || older < 0.0d || newer < 0.0d)
        {
            return null;
        }
        if (newer > older * (1.0d + TREND_DEAD_BAND))
        {
            return ProgressRate.Trend.RISING;
        }
        if (newer < older * (1.0d - TREND_DEAD_BAND))
        {
            return ProgressRate.Trend.FALLING;
        }
        return ProgressRate.Trend.STEADY;
    }

    private static Double slope(List<ProgressSample> samples)
    {
        if (samples.size() < 2)
        {
            return null;
        }

        double meanSeconds = 0.0d;
        double meanValue = 0.0d;
        for (ProgressSample sample : samples)
        {
            Double value = value(sample);
            if (value == null)
            {
                return null;
            }
            meanSeconds += sample.timestampMillis() / 1000.0d;
            meanValue += value;
        }
        meanSeconds /= samples.size();
        meanValue /= samples.size();

        double covariance = 0.0d;
        double variance = 0.0d;
        for (ProgressSample sample : samples)
        {
            double seconds = sample.timestampMillis() / 1000.0d - meanSeconds;
            covariance += seconds * (value(sample) - meanValue);
            variance += seconds * seconds;
        }
        return variance == 0.0d ? null : covariance / variance;
    }

    private static Double value(ProgressSample sample)
    {
        return sample.done() == null ? (double) -sample.remaining() : (double) sample.done();
    }
}
