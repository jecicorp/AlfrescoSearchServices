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

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the index diagnostic as one server-side job, at most one at a time, and keeps its last result.
 */
public class DiagnosticJobService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticJobService.class);

    static final String KEY_REPORT = "report";
    static final String KEY_ERROR_NODES = "errorNodes";
    static final String KEY_PARTIAL_FAILURES = "partialFailures";

    private final Supplier<List<String>> cores;
    private final CoreReporter reporter;
    private final Supplier<Map<String, Object>> repairReport;
    private final DiagnosticStore store;
    private final Clock clock;
    private final Executor executor;
    private final List<Consumer<DiagnosticSnapshot>> listeners = new CopyOnWriteArrayList<>();

    private DiagnosticSnapshot snapshot = DiagnosticSnapshot.idle();
    private Map<String, Object> lastResult;

    /**
     * @param cores        the cores to diagnose, in processing order
     * @param reporter     builds the {@code REPORT} section of one core
     * @param repairReport the {@code /repairreport} content, as plain JSON values
     * @param store        where each core's last result is kept
     * @param clock        the source of {@code startedAt} and {@code finishedAt}
     * @param executor     runs the job, a single thread in production
     */
    public DiagnosticJobService(Supplier<List<String>> cores, CoreReporter reporter,
            Supplier<Map<String, Object>> repairReport, DiagnosticStore store, Clock clock, Executor executor)
    {
        this.cores = cores;
        this.reporter = reporter;
        this.repairReport = repairReport;
        this.store = store;
        this.clock = clock;
        this.executor = executor;
    }

    /** @param listener called with every new snapshot, outside the service lock */
    public void addListener(Consumer<DiagnosticSnapshot> listener)
    {
        listeners.add(listener);
    }

    /** @return the current state of the job, with the last result */
    public synchronized DiagnosticSnapshot snapshot()
    {
        return snapshot;
    }

    /**
     * Starts a diagnostic, or joins the one already running.
     *
     * @param user who asks, recorded as {@code startedBy}
     * @return the snapshot of the job now running
     */
    public DiagnosticSnapshot start(String user)
    {
        DiagnosticSnapshot started;
        List<String> names;
        synchronized (this)
        {
            if (DiagnosticSnapshot.RUNNING.equals(snapshot.state()))
            {
                return snapshot;
            }
            names = List.copyOf(cores.get());
            snapshot = DiagnosticSnapshot.running(now(), normalise(user), names, lastResult);
            started = snapshot;
        }
        publish(started);
        try
        {
            executor.execute(() -> run(started, names));
        }
        catch (RejectedExecutionException e)
        {
            LOGGER.error("The index diagnostic could not be scheduled", e);
            finish(DiagnosticSnapshot.FAILED, "The index diagnostic could not be scheduled");
        }
        return started;
    }

    /** Stops the worker thread; a running job ends at its next batch. */
    public void shutdown()
    {
        if (executor instanceof ExecutorService service)
        {
            service.shutdownNow();
        }
    }

    private void run(DiagnosticSnapshot started, List<String> names)
    {
        try
        {
            Map<String, Map<String, Object>> report = new LinkedHashMap<>();
            for (int index = 0; index < names.size(); index++)
            {
                String core = names.get(index);
                int position = index;
                report.put(core, reporter.report(core,
                        phase -> (current, target) -> progress(position, core, phase, current, target)));
                coreDone(core);
            }
            repairStep();
            List<String> partialFailures = new ArrayList<>();
            Map<String, Object> errorNodes = errorNodes(partialFailures);
            String finishedAt = now();
            persist(started, finishedAt, report, errorNodes, partialFailures);
            succeed(finishedAt, result(report, errorNodes, partialFailures));
        }
        catch (RuntimeException | Error e)
        {
            LOGGER.error("The index diagnostic failed", e);
            finish(DiagnosticSnapshot.FAILED, e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            if (e instanceof Error error)
            {
                throw error;
            }
        }
    }

    private void progress(int position, String core, DiagnosticPhase phase, long current, long target)
    {
        update(snap -> {
            int step = position * DiagnosticPhase.values().length + phase.ordinal() + 1;
            return snap.withCore(core, new DiagnosticSnapshot.CoreProgress(phase.wireName(), current, target), step);
        });
    }

    private void coreDone(String core)
    {
        update(snap -> snap.withCore(core, DiagnosticSnapshot.CoreProgress.done(), snap.step()));
    }

    private void repairStep()
    {
        update(snap -> snap.atStep(snap.steps()));
    }

    private Map<String, Object> errorNodes(List<String> partialFailures)
    {
        try
        {
            return repairReport.get();
        }
        catch (RuntimeException e)
        {
            LOGGER.warn("The {} section of the index diagnostic failed", KEY_ERROR_NODES, e);
            partialFailures.add(KEY_ERROR_NODES);
            return new LinkedHashMap<>();
        }
    }

    private void persist(DiagnosticSnapshot started, String finishedAt, Map<String, Map<String, Object>> report,
            Map<String, Object> errorNodes, List<String> partialFailures)
    {
        for (Map.Entry<String, Map<String, Object>> section : report.entrySet())
        {
            try
            {
                store.save(section.getKey(), new StoredDiagnostic(started.startedAt(), started.startedBy(),
                        finishedAt, section.getValue(), errorNodes, List.copyOf(partialFailures)));
            }
            catch (IOException | RuntimeException e)
            {
                LOGGER.warn("The index diagnostic of core {} could not be stored", section.getKey(), e);
            }
        }
    }

    private void succeed(String finishedAt, Map<String, Object> result)
    {
        update(snap -> {
            lastResult = result;
            return DiagnosticSnapshot.done(snap.startedAt(), snap.startedBy(), finishedAt,
                    List.copyOf(snap.cores().keySet()), result);
        });
    }

    private void finish(String state, String error)
    {
        update(snap -> snap.ended(state, now(), lastResult, error));
    }

    private void update(UnaryOperator<DiagnosticSnapshot> change)
    {
        DiagnosticSnapshot updated;
        synchronized (this)
        {
            snapshot = change.apply(snapshot);
            updated = snapshot;
        }
        publish(updated);
    }

    private void publish(DiagnosticSnapshot published)
    {
        for (Consumer<DiagnosticSnapshot> listener : listeners)
        {
            try
            {
                listener.accept(published);
            }
            catch (RuntimeException e)
            {
                LOGGER.debug("A diagnostic listener failed", e);
            }
        }
    }

    static Map<String, Object> result(Map<String, Map<String, Object>> report, Map<String, Object> errorNodes,
            List<String> partialFailures)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(KEY_REPORT, Collections.unmodifiableMap(new LinkedHashMap<>(report)));
        result.put(KEY_ERROR_NODES, errorNodes);
        result.put(KEY_PARTIAL_FAILURES, List.copyOf(partialFailures));
        return Collections.unmodifiableMap(result);
    }

    private String now()
    {
        return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String normalise(String user)
    {
        return user == null || user.isBlank() ? null : user.strip();
    }
}
