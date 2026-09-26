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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * State of the index diagnostic job, as served by {@code /api/v1/index/diagnostic} and its stream.
 *
 * @param state      {@code idle}, {@code running}, {@code done}, {@code failed} or {@code cancelled}
 * @param startedAt  ISO-8601 instant the job started, to the second
 * @param startedBy  who started it, as passed by the caller
 * @param finishedAt ISO-8601 instant the job ended, {@code null} while it runs
 * @param step       1-based index of the current step
 * @param steps      four per core, plus one for the repair report
 * @param cores      progress of each core, in processing order
 * @param result     the last successful result, the previous one while a job runs
 * @param error      why the job failed, {@code null} otherwise
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DiagnosticSnapshot(String state, String startedAt, String startedBy, String finishedAt,
        Integer step, Integer steps, Map<String, CoreProgress> cores, Map<String, Object> result, String error)
{
    public static final String IDLE = "idle";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";
    public static final String PHASE_PENDING = "pending";
    public static final String PHASE_DONE = "done";

    /**
     * Progress of one core.
     *
     * @param phase   {@code pending}, a {@link DiagnosticPhase} wire name, or {@code done}
     * @param current how far into the phase, {@code null} outside a phase
     * @param target  where the phase ends, {@code null} outside a phase
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CoreProgress(String phase, Long current, Long target)
    {
        static CoreProgress pending()
        {
            return new CoreProgress(PHASE_PENDING, null, null);
        }

        static CoreProgress done()
        {
            return new CoreProgress(PHASE_DONE, null, null);
        }
    }

    static int stepsFor(int cores)
    {
        return DiagnosticPhase.values().length * cores + 1;
    }

    static DiagnosticSnapshot idle()
    {
        return new DiagnosticSnapshot(IDLE, null, null, null, null, null, Map.of(), null, null);
    }

    static DiagnosticSnapshot running(String startedAt, String startedBy, List<String> cores,
            Map<String, Object> previous)
    {
        Map<String, CoreProgress> progress = coreProgressMap(cores, CoreProgress::pending);
        return new DiagnosticSnapshot(RUNNING, startedAt, startedBy, null, 1, stepsFor(cores.size()),
                progress, previous, null);
    }

    static DiagnosticSnapshot done(String startedAt, String startedBy, String finishedAt, List<String> cores,
            Map<String, Object> result)
    {
        Map<String, CoreProgress> progress = coreProgressMap(cores, CoreProgress::done);
        int steps = stepsFor(cores.size());
        return new DiagnosticSnapshot(DONE, startedAt, startedBy, finishedAt, steps, steps, progress, result, null);
    }

    private static Map<String, CoreProgress> coreProgressMap(List<String> cores, Supplier<CoreProgress> progress)
    {
        Map<String, CoreProgress> map = new LinkedHashMap<>();
        for (String core : cores)
        {
            map.put(core, progress.get());
        }
        return Collections.unmodifiableMap(map);
    }

    DiagnosticSnapshot withCore(String core, CoreProgress progress, int newStep)
    {
        Map<String, CoreProgress> updated = new LinkedHashMap<>(cores);
        updated.put(core, progress);
        return new DiagnosticSnapshot(state, startedAt, startedBy, finishedAt, newStep, steps,
                Collections.unmodifiableMap(updated), result, error);
    }

    DiagnosticSnapshot atStep(int newStep)
    {
        return new DiagnosticSnapshot(state, startedAt, startedBy, finishedAt, newStep, steps, cores, result, error);
    }

    DiagnosticSnapshot withResult(Map<String, Object> newResult)
    {
        return new DiagnosticSnapshot(state, startedAt, startedBy, finishedAt, step, steps, cores, newResult, error);
    }

    DiagnosticSnapshot ended(String newState, String endedAt, Map<String, Object> lastResult, String reason)
    {
        return new DiagnosticSnapshot(newState, startedAt, startedBy, endedAt, step, steps, cores, lastResult, reason);
    }
}
