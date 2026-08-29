// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.subscription

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import eu.nodepass.somewhere.SomewhereApplication
import java.util.concurrent.TimeUnit

/**
 * Refreshes the subscription on a schedule the platform decides when to run.
 *
 * ## Why `JobScheduler` and not WorkManager
 *
 * WorkManager is the usual answer and would be a reasonable one. It is not
 * taken here because it brings Room, SQLite and App Startup into an app that
 * has none of them, to schedule **one** periodic job — and the platform has had
 * a scheduler with the same Doze semantics since API 21, five API levels below
 * this client's minimum. The rule this project applies to vendored C applies to
 * dependencies too: the cost of a library is not its size, it is that everything
 * it brings has to be reviewed and kept.
 *
 * What WorkManager would buy is chained work, retry policies with backoff, and
 * observability. A subscription refresh needs none of the three: it is one
 * network call whose failure is recorded and shown, and whose retry is the next
 * interval.
 *
 * ## Doze
 *
 * The job is periodic, needs a network, and is persisted across reboot. Doze
 * defers it into a maintenance window rather than cancelling it, which is the
 * correct behaviour — a subscription that refreshed at 3 a.m. by waking the
 * radio would be spending a user's battery on a list that changes weekly. The
 * interval is a *minimum*, and the platform is free to be later; anything that
 * needed to be exact would be the wrong design for this.
 */
class SubscriptionRefreshJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val application = applicationContext as SomewhereApplication
        val settings = application.refreshPreferences.load()
        if (!settings.automatic) {
            // The switch went off while a job was already scheduled. Cancelling
            // here as well as at the switch means the schedule cannot outlive
            // the preference even if one of the two paths is missed.
            Log.i(TAG, "automatic refresh is off; cancelling the schedule")
            cancel(this)
            return false
        }
        if (application.nodes.subscription.value == null) {
            Log.i(TAG, "no subscription to refresh")
            return false
        }

        // The repository owns the fetch, its own scope, and the failure
        // reporting the screen reads. Returning true says the work outlives
        // this call; the repository publishes to a flow rather than calling
        // back, so the job finishes as soon as it has handed the work over.
        application.nodes.refreshInBackground()
        return false
    }

    /** Nothing here holds a wakelock of its own, so there is nothing to stop. */
    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val TAG = "SomewhereRefresh"

        /** Stable, because rescheduling with the same id replaces rather than duplicates. */
        const val JOB_ID = 0x50FA

        /**
         * Puts the schedule where the preference says it should be.
         *
         * Idempotent and safe to call from anywhere that changes either — the
         * switch, a boot, or the job itself noticing it should not be running.
         * A schedule that disagreed with the preference is the failure this
         * shape exists to make impossible.
         */
        fun apply(context: Context) {
            val application = context.applicationContext as SomewhereApplication
            val settings = application.refreshPreferences.load()
            if (settings.automatic) schedule(context, settings.effectiveIntervalHours) else cancel(context)
        }

        fun schedule(
            context: Context,
            intervalHours: Int,
        ) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job =
                JobInfo
                    .Builder(JOB_ID, ComponentName(context, SubscriptionRefreshJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(TimeUnit.HOURS.toMillis(intervalHours.toLong()))
                    // Survives a reboot, which is the whole point of a schedule
                    // measured in hours. Needs RECEIVE_BOOT_COMPLETED, which is
                    // declared for this and for nothing else.
                    .setPersisted(true)
                    .build()
            scheduler.schedule(job)
            Log.i(TAG, "refreshing every $intervalHours hour(s)")
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }

        /** Whether the platform is holding a schedule for this job right now. */
        fun isScheduled(context: Context): Boolean = context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) != null
    }
}
