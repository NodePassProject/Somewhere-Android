// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import eu.nodepass.somewhere.apps.AppsController
import eu.nodepass.somewhere.data.Backup
import eu.nodepass.somewhere.data.ThemeMode
import eu.nodepass.somewhere.nodes.NodeChoice
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.routing.RoutingController
import eu.nodepass.somewhere.subscription.SubscriptionRefreshJob
import eu.nodepass.somewhere.ui.SomewhereApp
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.vpn.SomewhereVpnService
import eu.nodepass.somewhere.vpn.TunnelController
import eu.nodepass.somewhere.vpn.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single activity.
 *
 * Edge to edge on purpose: the design's screens start 52 dp from the top of the
 * display, with the status bar sitting over the app's own ground rather than in
 * a band of its own. A system bar in a different colour would put a seam across
 * every screen.
 */
class MainActivity : ComponentActivity() {
    private var pendingLink by mutableStateOf<String?>(null)

    /** What the last backup action came to, for Settings to say. */
    private var backupMessage by mutableStateOf<Int?>(null)

    /**
     * The node waiting for the system's consent dialog to come back.
     *
     * Held here rather than passed through the intent because the contract
     * hands back only a result code. It is cleared on every outcome, including
     * refusal, so a second tap starts a fresh request rather than reusing a
     * node the user may since have changed.
     */
    private var awaitingConsent: Start? = null

    /** A node to start on, and whether the service may move off it. */
    private data class Start(
        val node: NowhereUrl,
        val mayFailOver: Boolean,
    )

    /**
     * The node the running tunnel was started with.
     *
     * Kept so that a per-application change can rebuild the same tunnel rather
     * than asking the user which node they meant. Android fixes that set at
     * `establish()`, so rebuilding is the only way a change takes effect.
     */
    private var running: Start? = null

    /** The application's node list, once [onCreate] has run. */
    private lateinit var repository: eu.nodepass.somewhere.data.NodeRepository

    /**
     * The routing controller, once the composition has been built.
     *
     * Held as a field because the document picker is registered before
     * `onCreate` runs — an `ActivityResultLauncher` has to be, or the system
     * cannot restore it — and its callback needs somewhere to deliver a file.
     */
    private var routing: RoutingController? = null

    /**
     * `VpnService.prepare()` returns an intent the first time, and null once
     * the user has agreed. Both paths end in the same place, which is why the
     * launcher and the direct start share [startTunnel].
     */
    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val start = awaitingConsent
            awaitingConsent = null
            // The activity may have been recreated while the dialog was up,
            // which forgets the pending start; agreeing must still connect.
            val pending = start ?: resolveStart()
            if (result.resultCode == RESULT_OK && pending != null) {
                startTunnel(pending)
            } else {
                // Refusing is a decision, not an error. Saying so beats a
                // button that was pressed and did nothing.
                TunnelController.report(TunnelState.Failed(R.string.tunnel_no_consent))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The first frame's bars, before the composition has read the theme.
        // From the stored setting, so a light app on a dark system does not
        // flash white icons for a frame.
        val storedTheme =
            (application as SomewhereApplication)
                .settings.settings.value.theme
        if (storedTheme == ThemeMode.System) {
            enableEdgeToEdge()
        } else {
            val dark = storedTheme == ThemeMode.Dark
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            )
        }
        super.onCreate(savedInstanceState)
        // Only on a fresh start: a recreation re-delivers the same intent, and
        // the link it carried has already been handled.
        if (savedInstanceState == null) pendingLink = linkFrom(intent)
        backupMessage = savedInstanceState?.getInt(BACKUP_MESSAGE)?.takeIf { it != 0 }

        val application = application as SomewhereApplication
        repository = application.nodes
        val apps =
            AppsController(
                store = application.appSelection,
                apps = application.installedApps,
                scope = lifecycleScope,
                io = Dispatchers.IO,
                engaged = { TunnelController.isEngaged },
            )
        val routingController =
            RoutingController(
                rules = application.rules,
                preferences = application.routing,
                scope = lifecycleScope,
                io = Dispatchers.IO,
                engaged = { TunnelController.isEngaged },
                openAsset = { assets.open(it).bufferedReader().use { reader -> reader.readText() } },
            ).also { routing = it }

        // Only when the user opened the app from the launcher. Any app can
        // start this activity — it is exported for links and for the tile —
        // and none of them should be able to bring the tunnel up through it.
        val fromLauncher = intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        if (savedInstanceState == null && fromLauncher && application.settings.settings.value.connectOnLaunch) connectOnLaunch()
        if (savedInstanceState == null) application.updates.checkIfDue(application.settings.settings.value.checkUpdates)

        // Recents follows the switch while the app is open, so turning it on
        // takes effect without a restart.
        lifecycleScope.launch {
            application.settings.settings.collect { settings ->
                getSystemService(android.app.ActivityManager::class.java)
                    ?.appTasks
                    ?.forEach { it.setExcludeFromRecents(settings.hideFromRecents) }
            }
        }

        setContent {
            val appSettings by application.settings.settings.collectAsState()
            val dark =
                when (appSettings.theme) {
                    ThemeMode.System -> isSystemInDarkTheme()
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }
            // The bars' icons follow the app's theme, not the system's: a
            // light app on a dark system would otherwise draw white clock
            // digits on its own near-white ground.
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            }
            SomewhereTheme(darkTheme = dark) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(SomewhereTheme.colors.ground),
                ) {
                    SomewhereApp(
                        nodes = repository,
                        apps = apps,
                        routing = routingController,
                        pendingLink = pendingLink,
                        onLinkHandled = { pendingLink = null },
                        onToggleTunnel = ::toggleTunnel,
                        onChoose = ::choose,
                        // What the tunnel would be started with now, not what
                        // this activity remembers starting: a tunnel from the
                        // tile, a shortcut or before a recreation is one this
                        // instance never saw.
                        onReconnect = { if (TunnelController.isEngaged) resolveStart()?.let(::startTunnel) },
                        onImportRules = { importRules.launch(ruleMimeTypes) },
                        refresh = application.refreshPreferences,
                        onRefreshScheduleChanged = { SubscriptionRefreshJob.apply(this@MainActivity) },
                        settings = application.settings,
                        network = application.network,
                        onExportBackup = { exportBackup.launch(backupFileName()) },
                        onImportBackup = { importBackup.launch(arrayOf("application/json", "application/octet-stream", "text/*")) },
                        backupMessage = backupMessage,
                        updates = application.updates,
                    )
                }
            }
        }
    }

    /**
     * Connects on its own, once per launch, unless the last two launches that
     * tried died doing it. See [eu.nodepass.somewhere.data.LaunchGuard].
     *
     * Only when consent is already held: a system dialog thrown up the moment
     * the app opens, before the user has asked for anything, is not something
     * a switch in Settings should be able to cause.
     */
    private fun connectOnLaunch() {
        if (TunnelController.isEngaged || VpnService.prepare(this) != null) return
        val start = resolveStart() ?: return
        val guard = (application as SomewhereApplication).launchGuard
        if (!guard.beginAttempt()) {
            android.util.Log.w("SomewhereLaunch", "the last automatic connects did not survive; not connecting this time")
            return
        }
        startTunnel(start)
        // On the process's main looper, not the activity's scope: a rotation,
        // a theme change or Back within the window destroys the activity, and
        // a reset tied to it never ran, counting an ordinary exit as a crash.
        // Only the process dying stops this, which is the event being counted.
        android.os
            .Handler(android.os.Looper.getMainLooper())
            .postDelayed({ guard.survived() }, eu.nodepass.somewhere.data.LaunchGuard.SURVIVAL_MILLIS)
    }

    /**
     * Asks for permission to post the tunnel's notification (API 33+), until
     * the user has answered.
     *
     * Without it the tunnel runs with nothing on screen saying so. Asked once
     * a tunnel is up — after VPN consent rather than beside it, where the two
     * system dialogs stacked and the first was lost — and again whenever the
     * app is opened with a tunnel up and no answer yet, which is what catches
     * a tunnel started from the tile, a shortcut or on launch. Only an answer
     * stops it; a dialog dismissed by something else is asked again. Settings
     * says what a refusal costs.
     */
    private fun askForNotificationsIfUnanswered() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (getPreferences(MODE_PRIVATE).getBoolean(ANSWERED_NOTIFICATIONS, false)) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Granted, or refused in a way the system will remember: either is
            // an answer. A refusal the system will still ask about again is
            // not, and is left for next time.
            val refusedForGood =
                !granted &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            if (granted || refusedForGood) {
                getPreferences(MODE_PRIVATE).edit().putBoolean(ANSWERED_NOTIFICATIONS, true).apply()
            }
        }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        backupMessage?.let { outState.putInt(BACKUP_MESSAGE, it) }
    }

    override fun onResume() {
        super.onResume()
        if (TunnelController.isEngaged) askForNotificationsIfUnanswered()
    }

    /**
     * Connect, or disconnect if something is already up.
     *
     * The consent dialog is asked for only when the system says it is needed:
     * `prepare()` returns null once the user has agreed, and asking again every
     * time would put a system dialog in front of an ordinary reconnect.
     */
    private fun toggleTunnel() {
        if (TunnelController.isEngaged) {
            SomewhereVpnService.stop(this)
            return
        }
        val start = resolveStart()
        if (start == null) {
            TunnelController.report(TunnelState.Failed(R.string.tunnel_no_node))
            return
        }
        val request = VpnService.prepare(this)
        if (request == null) {
            startTunnel(start)
        } else {
            awaitingConsent = start
            consent.launch(request)
        }
    }

    /**
     * Records a new choice and, if a tunnel is up, moves it there.
     *
     * Moving rather than waiting for the next connect: a user who picks a
     * different node while connected means "use this one", and a tunnel that
     * stayed on the old node would make the list's tick a lie until they
     * thought to reconnect. Consent is already held while a tunnel is up, so
     * no dialog stands in the way.
     */
    private fun choose(choice: NodeChoice) {
        repository.choose(choice)
        if (!TunnelController.isEngaged) return
        val next = resolveStart() ?: return
        // Picking the node the tunnel is already on, or Automatic when it comes
        // to that same node, changes nothing — and a restart would drop every
        // open connection to say so.
        if (next == running) return
        startTunnel(next)
    }

    private fun resolveStart(): Start? {
        val target = repository.target() ?: return null
        return Start(target.url, mayFailOver = repository.effectiveChoice().mayFailOver)
    }

    private fun startTunnel(start: Start) {
        running = start
        SomewhereVpnService.start(this, start.node, start.mayFailOver)
        askForNotificationsIfUnanswered()
    }

    /**
     * What the picker will offer.
     *
     * Rule files are text and are named `.list`, `.txt`, `.conf` and a dozen
     * other things, so the type filter is the broad one; a file that is not a
     * rule set fails at the parser with a line number, which is a better
     * answer than a picker that would not show it.
     */
    private val ruleMimeTypes = arrayOf("text/*", "application/octet-stream")

    /**
     * Reads a rule file the user picked, through the system's own picker.
     *
     * The picker rather than a text box: rule sets are thousands of lines and
     * arrive as files. The document is read here and handed to the controller
     * as text, so everything that decides whether something is a rule set
     * stays in one place, where a test can reach it.
     *
     * A file this app cannot read is reported the same way a malformed one is.
     * "Permission denied" and "line 12 is not a rule" are both answers to the
     * same question the user just asked.
     */
    private val importRules =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val text =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            contentResolver.openInputStream(target)?.use { it.readBytes().decodeToString() }
                        }.getOrNull()
                    }
                routing?.import(text ?: "")
            }
        }

    /**
     * Up to [limit] bytes. `InputStream.readNBytes` arrived on Android in API
     * 33, and a backup larger than the limit is refused by size anyway.
     */
    private fun readAtMost(
        input: java.io.InputStream,
        limit: Int,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** A dated name, so two exports do not overwrite each other in Downloads. */
    private fun backupFileName(): String = "somewhere-backup-${java.time.LocalDate.now()}.json"

    /**
     * Writes a backup where the user chose, through the system's own picker.
     *
     * The picker rather than a fixed path: the file holds every key, and where
     * it goes is the user's decision — a folder, a USB drive, a cloud they
     * trust — not one this app should make by writing to shared storage.
     */
    private val exportBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val target = uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val written =
                    withContext(Dispatchers.IO + NonCancellable) {
                        val text = Backup.export(filesDir, BuildConfig.VERSION_NAME)
                        // "w" rather than "wt": some document providers refuse
                        // truncate, and the picker made this file just now.
                        val ok =
                            runCatching {
                                contentResolver
                                    .openOutputStream(
                                        target,
                                        "w",
                                    )?.use { it.write(text.toByteArray()) } != null
                            }.getOrDefault(false)
                        // A file the picker created and the write did not fill
                        // is deleted rather than left half-written: half of a
                        // backup is still some of the keys.
                        if (!ok) runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, target) }
                        ok
                    }
                backupMessage = if (written) R.string.backup_exported else R.string.backup_failed
            }
        }

    /**
     * Restores a backup the user chose, after the confirmation Settings showed.
     *
     * Read and checked in full before anything is written, so a wrong file
     * changes nothing. Then everything held in memory is re-read, and the
     * subscription schedule is put where the restored preference says.
     */
    private val importBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val source = uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val application = application as SomewhereApplication
                // Not cancellable: a rotation during the restore would
                // otherwise replace the files and skip the reload below, and
                // the stores' stale memory would later be written back over
                // what was restored.
                val outcome =
                    withContext(Dispatchers.IO + NonCancellable) {
                        val bytes =
                            runCatching {
                                contentResolver.openInputStream(source)?.use { readAtMost(it, Backup.MAX_BYTES + 1) }
                            }.getOrNull() ?: return@withContext R.string.backup_failed
                        when (val read = Backup.read(bytes)) {
                            is Backup.Read.Refused ->
                                when (read.why) {
                                    Backup.Refusal.TooLarge -> R.string.backup_too_large
                                    Backup.Refusal.NotABackup -> R.string.backup_not_backup
                                    Backup.Refusal.FromNewerVersion -> R.string.backup_newer
                                    Backup.Refusal.Empty -> R.string.backup_empty
                                }
                            is Backup.Read.Ok -> {
                                // A tunnel built from the old setup is stopped
                                // first: one carrying a node the backup replaced
                                // would go on doing so until someone noticed.
                                if (TunnelController.isEngaged) SomewhereVpnService.stop(this@MainActivity)
                                // Under the node store's lock, so a refresh
                                // already in flight cannot write its old list
                                // and old subscription back over the restore.
                                val restored = application.nodes.exclusive { Backup.restore(filesDir, read.files) }
                                // Re-read whether or not every file made it: a
                                // failure part-way leaves a mixed disk, and
                                // memory must at least agree with it.
                                application.nodes.reload()
                                application.settings.reload()
                                application.nodeHealth.clear()
                                SubscriptionRefreshJob.apply(this@MainActivity)
                                if (restored) R.string.backup_restored else R.string.backup_failed
                            }
                        }
                    }
                backupMessage = outcome
                // Every screen's own copy — the refresh switch, the per-app and
                // routing controllers — is rebuilt from the restored files.
                if (outcome == R.string.backup_restored || outcome == R.string.backup_failed) recreate()
            }
        }

    /**
     * The activity is `singleTop`-shaped in practice: tapping a second import
     * link while it is already open delivers here rather than to [onCreate], so
     * without this the second link would be silently dropped.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        linkFrom(intent)?.let { pendingLink = it }
    }

    /**
     * The link a VIEW intent carries, whatever its scheme.
     *
     * All three declared schemes are passed through unchanged rather than
     * filtered here. `nowhere://` is the one that parses; `somewhere://` and
     * `anywhere://` reach the import screen and are refused there, by the
     * parser, with its own reason — which is a better answer than an app that
     * opens and shows nothing.
     */
    private fun linkFrom(intent: Intent?): String? =
        intent
            ?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.data
            ?.toString()

    private companion object {
        const val ANSWERED_NOTIFICATIONS = "answered-notifications"
        const val BACKUP_MESSAGE = "backup-message"
    }
}
