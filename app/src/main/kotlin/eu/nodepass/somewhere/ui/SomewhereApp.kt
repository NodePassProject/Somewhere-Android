// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.nodepass.somewhere.BuildConfig
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.apps.AppsController
import eu.nodepass.somewhere.data.AppSettingsStore
import eu.nodepass.somewhere.data.NodeRepository
import eu.nodepass.somewhere.nodes.NodeChoice
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.routing.RoutingController
import eu.nodepass.somewhere.subscription.RefreshPreferences
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.screens.AppsScreen
import eu.nodepass.somewhere.ui.screens.DiagnosticsScreen
import eu.nodepass.somewhere.ui.screens.HomeScreen
import eu.nodepass.somewhere.ui.screens.ImportScreen
import eu.nodepass.somewhere.ui.screens.NetworkScreen
import eu.nodepass.somewhere.ui.screens.NodeEditorScreen
import eu.nodepass.somewhere.ui.screens.NodesScreen
import eu.nodepass.somewhere.ui.screens.RoutingScreen
import eu.nodepass.somewhere.ui.screens.ScanScreen
import eu.nodepass.somewhere.ui.screens.SettingsScreen
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType
import eu.nodepass.somewhere.vpn.ActivityExport
import eu.nodepass.somewhere.vpn.NetworkSettingsStore
import eu.nodepass.somewhere.vpn.TunnelController

/** The four destinations that carry a tab. Everything else is pushed over them. */
enum class Tab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    Home("home", R.string.tab_home, SomewhereIcons.TabHome),
    Nodes("nodes", R.string.tab_nodes, SomewhereIcons.TabNodes),
    Routing("routing", R.string.tab_routing, SomewhereIcons.TabRouting),
    Logs("logs", R.string.tab_logs, SomewhereIcons.TabLogs),
}

object Routes {
    const val EDITOR = "editor"
    const val IMPORT = "import"
    const val APPS = "apps"
    const val SETTINGS = "settings"
    const val SCAN = "scan"
    const val NETWORK = "network"
}

@Composable
fun SomewhereApp(
    nodes: NodeRepository,
    apps: AppsController,
    routing: RoutingController,
    pendingLink: String? = null,
    onLinkHandled: () -> Unit = {},
    onToggleTunnel: () -> Unit = {},
    /** The user picked a node, or automatic. Persisting it, and reconnecting, is the caller's. */
    onChoose: (NodeChoice) -> Unit = {},
    onReconnect: () -> Unit = {},
    onImportRules: () -> Unit = {},
    /**
     * Whether the subscription refreshes on its own, and how often.
     *
     * Passed in rather than read here so that a preview and a UI test can hand
     * the screen a value without a filesystem.
     */
    refresh: RefreshPreferences = RefreshPreferences(java.io.File("")),
    /** Called after the preference changes, to put the schedule where it says. */
    onRefreshScheduleChanged: () -> Unit = {},
    /** The app's own preferences. A default store with no file serves previews and tests. */
    settings: AppSettingsStore = AppSettingsStore(null),
    /** How the TUN is built. A store with no file serves previews and tests. */
    network: NetworkSettingsStore = NetworkSettingsStore(java.io.File("/dev/null/none")),
    navController: NavHostController = rememberNavController(),
) {
    val colors = SomewhereTheme.colors
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tab = Tab.entries.firstOrNull { it.route == route }

    // A link that arrives while the app is open lands on the import screen
    // wherever the user happened to be. Navigating on arrival rather than
    // storing it for later, because an import the user asked for and did not
    // get is worse than one they have to dismiss.
    LaunchedEffect(pendingLink) {
        if (pendingLink != null) navController.navigate(Routes.IMPORT)
    }

    // The node being edited. Held here rather than passed through the route,
    // because a route argument carrying a node URL would put a shared key in
    // the navigation back stack, and from there into logs and crash reports.
    var editing by remember { mutableStateOf<NowhereUrl?>(null) }

    // What the scanner read, on its way to the import screen. Held here for the
    // same reason as [editing]: it may be a node URL with its shared key.
    var scanned by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.ground),
    ) {
        Box(Modifier.weight(1f)) {
            NavHost(navController, startDestination = Tab.Home.route) {
                composable(Tab.Home.route) {
                    HomeScreen(
                        nodes = nodes,
                        onOpenNodes = { navController.navigate(Tab.Nodes.route) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onToggleTunnel = onToggleTunnel,
                    )
                }
                composable(Tab.Nodes.route) {
                    val current by settings.settings.collectAsState()
                    NodesScreen(
                        nodes = nodes,
                        onChoose = onChoose,
                        settings = current,
                        onSettingsChange = { settings.update(it) },
                        onAdd = {
                            onLinkHandled()
                            navController.navigate(Routes.IMPORT)
                        },
                        onEdit = { entry ->
                            editing = entry.url
                            navController.navigate(Routes.EDITOR)
                        },
                    )
                }
                composable(Tab.Routing.route) {
                    RoutingScreen(
                        apps = apps,
                        routing = routing,
                        onOpenApps = { navController.navigate(Routes.APPS) },
                        onReconnect = onReconnect,
                        onImportRules = onImportRules,
                    )
                }
                composable(Tab.Logs.route) {
                    val entries by TunnelController.connectionLog.recent.collectAsState()
                    val live by TunnelController.connections.collectAsState()
                    val dns by TunnelController.dnsLog.recent.collectAsState()
                    // Its own clock. Keyed on the list it froze: an idle
                    // connection makes an equal snapshot, a StateFlow does not
                    // emit an equal value, and the age of exactly the
                    // connections whose age matters stopped.
                    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            now = android.os.SystemClock.elapsedRealtime()
                            kotlinx.coroutines.delay(1_000)
                        }
                    }
                    val context = LocalContext.current
                    val baseline = stringResource(R.string.protocol_baseline)
                    DiagnosticsScreen(
                        entries = entries,
                        connections = live,
                        dns = dns,
                        nowElapsedMillis = now,
                        onClose = TunnelController::closeConnection,
                        onCloseAll = TunnelController::closeAllConnections,
                        onExport = {
                            context.shareText(
                                ActivityExport.render(
                                    appVersion = BuildConfig.VERSION_NAME,
                                    protocolBaseline = baseline,
                                    exportedAt =
                                        java.time.OffsetDateTime
                                            .now()
                                            .withNano(0)
                                            .toString(),
                                    portalAnswers = entries,
                                    dns = dns,
                                    live = live,
                                    nowElapsedMillis = android.os.SystemClock.elapsedRealtime(),
                                ),
                            )
                        },
                    )
                }
                composable(Routes.EDITOR) {
                    NodeEditorScreen(
                        nodes = nodes,
                        editing = editing,
                        onBack = {
                            editing = null
                            navController.popBackStack()
                        },
                    )
                }
                composable(Routes.IMPORT) {
                    // Gone with the screen, however it closes. Cleared only on
                    // Add, a system back left the scan behind, and the next
                    // import opened pre-filled with it — a node key included.
                    DisposableEffect(Unit) { onDispose { scanned = null } }
                    ImportScreen(
                        nodes = nodes,
                        link = scanned ?: pendingLink,
                        onClose = {
                            scanned = null
                            onLinkHandled()
                            navController.popBackStack()
                        },
                        onScan = { navController.navigate(Routes.SCAN) },
                    )
                }
                composable(Routes.NETWORK) {
                    NetworkScreen(
                        initial = remember { network.load() },
                        onSave = { network.save(it) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.SCAN) {
                    ScanScreen(
                        onBack = { navController.popBackStack() },
                        onScanned = { text ->
                            scanned = text
                            navController.popBackStack()
                        },
                    )
                }
                composable(Routes.APPS) {
                    val current by settings.settings.collectAsState()
                    AppsScreen(
                        settings = current,
                        onSettingsChange = { settings.update(it) },
                        onBack = { navController.popBackStack() },
                        controller = apps,
                        onReconnect = onReconnect,
                    )
                }
                composable(Routes.SETTINGS) {
                    // Read on entry rather than held in a view model: the
                    // preference is a file the scheduled job also reads, and a
                    // screen holding a cached copy would disagree with it after
                    // the job cancelled its own schedule.
                    val refreshSettings = remember { refresh.load() }
                    var automatic by remember { mutableStateOf(refreshSettings.automatic) }
                    val current by settings.settings.collectAsState()
                    SettingsScreen(
                        onOpenNetwork = { navController.navigate(Routes.NETWORK) },
                        settings = current,
                        onSettingsChange = { settings.update(it) },
                        onBack = { navController.popBackStack() },
                        automaticRefresh = automatic,
                        refreshIntervalHours = refreshSettings.effectiveIntervalHours,
                        onToggleAutomaticRefresh = { wanted ->
                            automatic = wanted
                            refresh.save(refreshSettings.copy(automatic = wanted))
                            onRefreshScheduleChanged()
                        },
                    )
                }
            }
        }

        if (tab != null) {
            TabBar(selected = tab) { target ->
                if (target != tab) {
                    navController.navigate(target.route) {
                        popUpTo(Tab.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
        }
    }
}

@Composable
private fun TabBar(
    selected: Tab,
    onSelect: (Tab) -> Unit,
) {
    val colors = SomewhereTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.panel),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.panelLine),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(74.dp)
                .padding(start = 8.dp, end = 8.dp, top = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Tab.entries.forEach { tab ->
                val active = tab == selected
                val interaction = remember { MutableInteractionSource() }
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = { onSelect(tab) },
                            ).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        tint = if (active) colors.brand else colors.muted,
                    )
                    Text(
                        text = stringResource(tab.label),
                        fontFamily = SomewhereType.Body,
                        fontSize = 10.5.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        color = if (active) colors.brand else colors.muted,
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .width(0.dp),
        )
    }
}
