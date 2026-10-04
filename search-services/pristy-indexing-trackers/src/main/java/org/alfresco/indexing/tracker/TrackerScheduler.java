/*
 * #%L
 * Alfresco Search Services
 * %%
 * Copyright (C) 2005 - 2020 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

package org.alfresco.indexing.tracker;

import java.util.Collection;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.JobListener;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.impl.matchers.KeyMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a decorator of the Quartz Scheduler object to add tracker-specific functionality.
 * @author Ahmed Owian
 */
public class TrackerScheduler
{
    private static final String DEFAULT_CRON = "0/10 * * * * ? *";
    public static final int DEFAULT_THREAD_COUNT = 40;
    public static final String SOLR_JOB_GROUP = "Solr";
    public static final String AWAIT_TRIGGER_GROUP = "IndexAwait";
    protected final static Logger log = LoggerFactory.getLogger(TrackerScheduler.class);
    protected Scheduler scheduler;
    private final AtomicLong triggerSequence = new AtomicLong();

    /**
     * Starts a Quartz scheduler with a pool of {@link #DEFAULT_THREAD_COUNT} threads.
     */
    public TrackerScheduler(String schedulerName)
    {
        this(schedulerName, DEFAULT_THREAD_COUNT);
    }

    /**
     * Starts a Quartz scheduler whose pool runs at most {@code threadCount} jobs at once, all cores together.
     */
    public TrackerScheduler(String schedulerName, int threadCount)
    {
        if (threadCount < 1)
        {
            throw new IllegalArgumentException("The scheduler thread count must be at least 1, got " + threadCount);
        }
        try
        {
            StdSchedulerFactory factory = new StdSchedulerFactory();
            Properties properties = new Properties();
            properties.setProperty("org.quartz.scheduler.instanceName", schedulerName);
            properties.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
            properties.setProperty("org.quartz.threadPool.threadCount", Integer.toString(threadCount));
            properties.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
            properties.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
            properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
            properties.setProperty("org.quartz.scheduler.skipUpdateCheck","true");
            factory.initialize(properties);
            scheduler = factory.getScheduler();
            scheduler.start();

        }
        catch (SchedulerException e)
        {
            logError("TrackerScheduler", e);
        }
    }

    private void logError(String jobType, Throwable e)
    {
        log.error("Failed to schedule " + jobType + " Job.", e);
    }

    private String getCron(Properties props, String cronType)
    {
        String cron = props.getProperty(cronType);
        return cron == null ? props.getProperty("alfresco.cron", DEFAULT_CRON) : cron;
    }

    /**
     * Schedules individual trackers based on the solrcore properties.
     *
     * @author Michael Suzuki
     * @param tracker the tracker to bo scheduled.
     * @param coreName the owning core name.
     * @param props the core properties.
     */
    public void schedule(Tracker tracker, String coreName, Properties props)
    {
        String jobName = this.getJobName(tracker, coreName);
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(TrackerJob.JOBDATA_TRACKER_KEY, tracker);
        JobDetail job =
                JobBuilder.newJob(TrackerJob.class)
                        .withIdentity(jobName, SOLR_JOB_GROUP)
                        .withDescription(jobName)
                        .setJobData(jobDataMap).build();
        Trigger trigger;
        try
        {
            String cron;
            switch (tracker.getType())
            {
            case ACL:
                cron = getCron(props,"alfresco.acl.tracker.cron");
                break;
            case MODEL:
                cron = getCron(props,"alfresco.model.tracker.cron");
                break;
            case CONTENT:
                cron = getCron(props,"alfresco.content.tracker.cron");
                break;
            case METADATA:
                cron = getCron(props,"alfresco.metadata.tracker.cron");
                break;
            case CASCADE:
                cron = getCron(props,"alfresco.cascade.tracker.cron");
                break;
            case COMMIT:
                cron = getCron(props,"alfresco.commit.tracker.cron");
                break;
            case NODE_STATE_PUBLISHER:
                cron = getCron(props,"alfresco.nodestate.tracker.cron");
                break;
            case REPAIR:
                cron = getCron(props,"alfresco.repair.tracker.cron");
                break;
            default:
                cron = props.getProperty("alfresco.cron",DEFAULT_CRON);
                break;
            }
            trigger = TriggerBuilder.newTrigger().withIdentity(jobName, SOLR_JOB_GROUP).withSchedule(CronScheduleBuilder.cronSchedule(cron)).build();
            log.info("Scheduling job " + jobName);
            scheduler.scheduleJob(job, trigger);
        }
        catch (SchedulerException e)
        {
            logError("Tracker", e);
        }
    }

    protected String getJobName(Tracker tracker, String coreName)
    {
        return tracker.getClass().getSimpleName() + "-" + coreName;
    }

    /**
     * Fires the job of a tracker once, besides its cron schedule.
     *
     * @param trackerName the simple class name of the tracker, e.g. {@code MetadataTracker}
     * @param coreName    the core the job tracks
     * @param delayMillis how long to wait before firing
     * @return {@code false} when no such job is scheduled or Quartz refused the trigger
     */
    public boolean triggerOnce(String trackerName, String coreName, long delayMillis)
    {
        JobKey jobKey = existingJobKey(trackerName, coreName);
        if (jobKey == null)
        {
            return false;
        }
        try
        {
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(jobKey.getName() + "-" + triggerSequence.incrementAndGet(), AWAIT_TRIGGER_GROUP)
                    .forJob(jobKey)
                    .startAt(new Date(System.currentTimeMillis() + Math.max(0L, delayMillis)))
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
                    .build();
            scheduler.scheduleJob(trigger);
            return true;
        }
        catch (SchedulerException e)
        {
            log.warn("Could not trigger job " + jobKey.getName(), e);
            return false;
        }
    }

    /**
     * Calls back when a run fired by {@link #triggerOnce} starts and when it ends.
     *
     * @param trackerName the simple class name of the tracker
     * @param coreName    the core the job tracks
     * @param started     called on the Quartz thread before such a run
     * @param ended       called on the Quartz thread after such a run
     * @return {@code false} when no such job is scheduled or Quartz refused the listener
     */
    public boolean onTriggeredRun(String trackerName, String coreName, Runnable started, Runnable ended)
    {
        JobKey jobKey = existingJobKey(trackerName, coreName);
        if (jobKey == null)
        {
            return false;
        }
        try
        {
            scheduler.getListenerManager().addJobListener(
                    new TriggeredRunListener(AWAIT_TRIGGER_GROUP + "-" + jobKey.getName(), started, ended),
                    KeyMatcher.keyEquals(jobKey));
            return true;
        }
        catch (SchedulerException e)
        {
            log.warn("Could not listen to job " + jobKey.getName(), e);
            return false;
        }
    }

    private JobKey existingJobKey(String trackerName, String coreName)
    {
        if (scheduler == null)
        {
            return null;
        }
        JobKey jobKey = new JobKey(trackerName + "-" + coreName, SOLR_JOB_GROUP);
        try
        {
            return scheduler.checkExists(jobKey) ? jobKey : null;
        }
        catch (SchedulerException e)
        {
            log.warn("Could not look up job " + jobKey.getName(), e);
            return null;
        }
    }

    private static final class TriggeredRunListener implements JobListener
    {
        private final String name;
        private final Runnable started;
        private final Runnable ended;

        TriggeredRunListener(String name, Runnable started, Runnable ended)
        {
            this.name = name;
            this.started = started;
            this.ended = ended;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public void jobToBeExecuted(JobExecutionContext context)
        {
            if (isTriggered(context))
            {
                runQuietly(started);
            }
        }

        @Override
        public void jobExecutionVetoed(JobExecutionContext context)
        {
        }

        @Override
        public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException)
        {
            if (isTriggered(context))
            {
                runQuietly(ended);
            }
        }

        private static boolean isTriggered(JobExecutionContext context)
        {
            return AWAIT_TRIGGER_GROUP.equals(context.getTrigger().getKey().getGroup());
        }

        private void runQuietly(Runnable callback)
        {
            try
            {
                callback.run();
            }
            catch (Throwable e)
            {
                log.error("Callback of job listener " + name + " failed", e);
            }
        }
    }

    public void shutdown() throws SchedulerException
    {
        this.scheduler.shutdown(false);
    }

    public void deleteTrackerJobs(String coreName, Collection<Tracker> trackers) throws SchedulerException
    {
        for (Tracker tracker : trackers)
        {
            deleteTrackerJob(coreName, tracker);
        }
    }

    /**
     * Delete a Tracker Job ONLY if its exactly the same tracker instance that was passed in.
     *
     * In theory more than one instance of a core can exist with the same core name but the
     * scheduler stores jobs using the core name as a unique key (even though it may not be unique).
     *
     * This method gets the tracker instance associated with the Job and compares to see if its
     * identical to the instance that is passed in.  If they are identical then the job is deleted.
     * Otherwise, another core (of the same name) scheduled this job, so its left alone.
     *
     * @param coreName the core name.
     * @param tracker Specific instance of a tracker
     */
    public void deleteJobForTrackerInstance(String coreName, Tracker tracker)
    {
        String jobName = this.getJobName(tracker, coreName);
        JobDetail detail = null;
        try {
            detail = this.scheduler.getJobDetail(new JobKey(jobName, SOLR_JOB_GROUP));
            if (detail != null)
            {
                Tracker jobTracker = (Tracker) detail.getJobDataMap().get(TrackerJob.JOBDATA_TRACKER_KEY);
                //If this is the exact tracker instance that was scheduled, then delete it.
                if (tracker == jobTracker)
                {
                    this.scheduler.deleteJob(new JobKey(jobName, SOLR_JOB_GROUP));
                }
            }

        } catch (SchedulerException e) {
            log.error("Unable to delete a tracker job "+jobName, e);
        }
    }

    public void deleteTrackerJob(String coreName, Tracker tracker) throws SchedulerException
    {
        String jobName = this.getJobName(tracker, coreName);
        this.scheduler.deleteJob(new JobKey(jobName, SOLR_JOB_GROUP));
    }

    public boolean isShutdown() throws SchedulerException
    {
        return this.scheduler.isShutdown();
    }

    public void pauseAll() throws SchedulerException
    {
        this.scheduler.pauseAll();
    }

    public int getJobsCount() throws SchedulerException
    {
        return this.scheduler.getJobKeys(GroupMatcher.jobGroupEquals(SOLR_JOB_GROUP)).size();
    }
}
