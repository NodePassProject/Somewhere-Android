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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.nodepass.somewhere.R
import eu.nodepass.somewhere.apps.AppsController
import eu.nodepass.somewhere.data.NodeRepository
import eu.nodepass.somewhere.protocol.url.NowhereUrl
import eu.nodepass.somewhere.routing.RoutingController
import eu.nodepass.somewhere.ui.icons.SomewhereIcons
import eu.nodepass.somewhere.ui.screens.AppsScreen
import eu.nodepass.somewhere.ui.screens.DiagnosticsScreen
import eu.nodepass.somewhere.ui.screens.HomeScreen
import eu.nodepass.somewhere.ui.screens.ImportScreen
import eu.nodepass.somewhere.ui.screens.NodeEditorScreen
import eu.nodepass.somewhere.ui.screens.NodesScreen
import eu.nodepass.somewhere.ui.screens.RoutingScreen
import eu.nodepass.somewhere.ui.screens.SettingsScreen
import eu.nodepass.somewhere.ui.theme.SomewhereTheme
import eu.nodepass.somewhere.ui.theme.SomewhereType
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
}

@Composable
fun SomewhereApp(
    nodes: NodeRepository,
    apps: AppsController,
    routing: RoutingController,
    pendingLink: String? = null,
    onLinkHandled: () -> Unit = {},
    onToggleTunnel: (NowhereUrl) -> Unit = {},
    onReconnect: () -> Unit = {},
    onImportRules: () -> Unit = {},
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
                    NodesScreen(
                        nodes = nodes,
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
                    DiagnosticsScreen(entries = entries)
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
                    ImportScreen(
                        nodes = nodes,
                        link = pendingLink,
                        onClose = {
                            onLinkHandled()
                            navController.popBackStack()
                        },
                    )
                }
                composable(Routes.APPS) {
                    AppsScreen(
                        onBack = { navController.popBackStack() },
                        controller = apps,
                        onReconnect = onReconnect,
                    )
                }
                composable(Routes.SETTINGS) { SettingsScreen(onBack = { navController.popBackStack() }) }
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
