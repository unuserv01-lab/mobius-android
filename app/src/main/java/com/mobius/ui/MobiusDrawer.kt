package com.mobius.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mobius.MobiusApp
import com.mobius.R
import com.mobius.ai.GoogleAuth
import com.mobius.bubble.BubbleService
import kotlinx.coroutines.launch

/** v8 — drawer destinations (replaces the v7 bottom nav). */
enum class MobiusDestination {
    HOME,
    CHAT_HISTORY,
    LOG,
    SETTINGS,
}

// ------------------------------------------------------------------ drawer icons
//
// Same no-new-dependency pattern as MainActivity.kt: canonical Material path
// data drawn with ImageVector.Builder. Helper copied from MainActivity.kt
// (renamed — MainActivity's mobiusIcon is file-private).

private const val EDIT_PATH_DATA =
    "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"
private const val LIST_PATH_DATA =
    "M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"
private const val CLOSE_PATH_DATA =
    "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"

private fun drawerIcon(name: String, vararg pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        pathData.forEach { path ->
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }
    }.build()

private val MobiusEditIcon: ImageVector by lazy { drawerIcon("Edit", EDIT_PATH_DATA) }
private val MobiusListIcon: ImageVector by lazy { drawerIcon("List", LIST_PATH_DATA) }
private val MobiusCloseIcon: ImageVector by lazy { drawerIcon("Close", CLOSE_PATH_DATA) }

/**
 * v8 — navigation drawer (Gemini-style layout, MOBIUS content only).
 *
 * Only real destinations: new chat, chat history, log, settings, plus the
 * floating-bubble toggle and the Google account row. No fake items.
 * Rendered inside [ModalNavigationDrawer] by MainTabs; [onNavigate] only
 * changes the destination (MainTabs closes the drawer), [onCloseDrawer]
 * just closes it.
 */
@Composable
fun MobiusDrawer(
    app: MobiusApp,
    currentDestination: MobiusDestination,
    onNavigate: (MobiusDestination) -> Unit,
    onCloseDrawer: () -> Unit,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bubbleOn by app.permissions.bubbleEnabled.collectAsState(initial = false)
    val perms by rememberPermissionStatus(app.permissions)
    // Synchronous reads (same as GoogleAccountSettings): cached tokens after
    // the first load, so this is cheap on recomposition.
    val signedIn = GoogleAuth.isSignedIn()
    val email = GoogleAuth.accountEmail()

    ModalDrawerSheet {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header: logo + gradient MOBIUS + close button.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MobiusGradientBrush),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "MOBIUS",
                        modifier = Modifier.size(36.dp).clip(CircleShape),
                    )
                }
                Text(
                    "MOBIUS",
                    style = MaterialTheme.typography.titleLarge.copy(
                        brush = MobiusGradientBrush,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCloseDrawer) {
                    Icon(
                        imageVector = MobiusCloseIcon,
                        contentDescription = strings.panel.panelClose,
                    )
                }
            }

            NavigationDrawerItem(
                label = { Text(strings.drawer.drawerNewChat) },
                icon = { Icon(imageVector = MobiusEditIcon, contentDescription = null) },
                selected = false,
                onClick = {
                    scope.launch { app.memoryStore.clearChat() }
                    onNavigate(MobiusDestination.HOME)
                    onCloseDrawer()
                },
            )
            NavigationDrawerItem(
                label = { Text(strings.tabs.tabHistory) },
                icon = { Icon(imageVector = MobiusHistoryIcon, contentDescription = null) },
                selected = currentDestination == MobiusDestination.CHAT_HISTORY,
                onClick = {
                    onNavigate(MobiusDestination.CHAT_HISTORY)
                    onCloseDrawer()
                },
            )
            NavigationDrawerItem(
                label = { Text(strings.drawer.drawerLog) },
                icon = { Icon(imageVector = MobiusListIcon, contentDescription = null) },
                selected = currentDestination == MobiusDestination.LOG,
                onClick = {
                    onNavigate(MobiusDestination.LOG)
                    onCloseDrawer()
                },
            )
            NavigationDrawerItem(
                label = { Text(strings.tabs.tabSettings) },
                icon = { Icon(imageVector = MobiusSettingsIcon, contentDescription = null) },
                selected = currentDestination == MobiusDestination.SETTINGS,
                onClick = {
                    onNavigate(MobiusDestination.SETTINGS)
                    onCloseDrawer()
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // Floating bubble toggle (same calls as the old Home card).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    strings.home.homeFloatingBubble,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                    checked = bubbleOn,
                    onCheckedChange = { on ->
                        if (on) {
                            scope.launch { app.permissions.setBubbleEnabled(true) }
                            BubbleService.start(context)
                        } else {
                            BubbleService.stop(context)
                            scope.launch { app.permissions.setBubbleEnabled(false) }
                        }
                    },
                )
            }
            if (!perms.overlay) {
                Text(
                    strings.home.homeBubbleNoOverlay,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.weight(1f))

            // Account row (bottom): signed in = initial + email -> Settings;
            // not signed in = login prompt -> Google Sign-In.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (signedIn) {
                            onNavigate(MobiusDestination.SETTINGS)
                            onCloseDrawer()
                        } else {
                            runCatching {
                                context.startActivity(GoogleAuth.buildAuthIntent())
                            }
                        }
                    },
            ) {
                if (signedIn && !email.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MobiusGradientBrush),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            email.take(1).uppercase(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                    Text(
                        email,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(
                            strings.drawer.drawerNotLoggedIn,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            strings.drawer.drawerTapToLogin,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = {
                        onNavigate(MobiusDestination.SETTINGS)
                        onCloseDrawer()
                    },
                ) {
                    Icon(
                        imageVector = MobiusSettingsIcon,
                        contentDescription = strings.tabs.tabSettings,
                    )
                }
            }
        }
    }
}
