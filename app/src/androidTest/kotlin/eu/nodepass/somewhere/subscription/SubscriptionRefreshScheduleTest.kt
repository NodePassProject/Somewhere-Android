// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.subscription

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.nodepass.somewhere.SomewhereApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the platform actually took the schedule.
 *
 * The preference is unit-tested and needs no device. What needs one is the
 * other half: `JobScheduler.schedule` returns a result nobody reads, a job with
 * an interval the platform will not honour is silently adjusted, and a job that
 * was never accepted looks exactly like one that has not fired yet.
 *
 * So this asks the scheduler what it is holding, which is the only answer that
 * is not this app agreeing with itself.
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionRefreshScheduleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val application get() = context.applicationContext as SomewhereApplication

    private var saved = RefreshPreferences.Settings()

    @After
    fun restoreThePreference() {
        application.refreshPreferences.save(saved)
        SubscriptionRefreshJob.apply(context)
    }

    @Test
    fun turningItOnPutsAJobInTheScheduler() {
        saved = application.refreshPreferences.load()
        application.refreshPreferences.save(RefreshPreferences.Settings(automatic = true, intervalHours = 6))
        SubscriptionRefreshJob.apply(context)
        assertTrue("the platform did not accept the schedule", SubscriptionRefreshJob.isScheduled(context))
    }

    @Test
    fun turningItOffTakesTheJobAway() {
        // A schedule that outlived its preference would be a switch that says
        // off while a job runs every six hours — the exact shape of the
        // switches this screen deleted for describing behaviour that was not
        // there, facing the other way.
        saved = application.refreshPreferences.load()
        application.refreshPreferences.save(RefreshPreferences.Settings(automatic = true))
        SubscriptionRefreshJob.apply(context)
        assertTrue(SubscriptionRefreshJob.isScheduled(context))

        application.refreshPreferences.save(RefreshPreferences.Settings(automatic = false))
        SubscriptionRefreshJob.apply(context)
        assertFalse("the schedule outlived the preference", SubscriptionRefreshJob.isScheduled(context))
    }

    @Test
    fun schedulingTwiceLeavesOneJobRatherThanTwo() {
        // Same id, so a reschedule replaces. Without that, every visit to the
        // settings screen would add a job and the refresh would speed up the
        // more often somebody looked at the switch.
        saved = application.refreshPreferences.load()
        application.refreshPreferences.save(RefreshPreferences.Settings(automatic = true))
        SubscriptionRefreshJob.apply(context)
        SubscriptionRefreshJob.apply(context)

        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)!!
        val ours = scheduler.allPendingJobs.filter { it.id == SubscriptionRefreshJob.JOB_ID }
        assertEquals("one job, not one per call", 1, ours.size)
    }

    @Test
    fun theScheduledJobSurvivesAReboot() {
        // The interval is measured in hours. A schedule a restart cancelled
        // would be a switch that works until the phone is turned off, which is
        // worse than no switch at all because nothing says it stopped.
        saved = application.refreshPreferences.load()
        application.refreshPreferences.save(RefreshPreferences.Settings(automatic = true))
        SubscriptionRefreshJob.apply(context)

        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)!!
        val job = scheduler.allPendingJobs.first { it.id == SubscriptionRefreshJob.JOB_ID }
        assertTrue("the job is not persisted", job.isPersisted)
        assertTrue("the job does not require a network", job.networkType != android.app.job.JobInfo.NETWORK_TYPE_NONE)
    }
}
