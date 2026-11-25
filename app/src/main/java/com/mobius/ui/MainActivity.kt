package com.mobius.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.mobius.ai.GoogleAuth
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobius.MobiusApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * v7 — MOBIUS palette (from Tuan's logo: glowing purple-blue loop on dark).
 * Background #04000F, surface #17122E, surface variant #221A3E,
 * primary #7C4DFF, secondary #4D7CFE, accent/glow #B388FF,
 * text #F2EDFF / muted #B9B3D6.
 * Semantic (dark-tuned): success #66BB6A, error #EF5350, warning #FFCA28.
 */
private val MobiusColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF7C4DFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF3D2A7A),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFF4D7CFE),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF1E2F6E),
    onSecondaryContainer = Color(0xFFDCE6FF),
    tertiary = Color(0xFFB388FF),
    onTertiary = Color(0xFF1B1030),
    tertiaryContainer = Color(0xFF2E1F5C),
    onTertiaryContainer = Color(0xFFE9DDFF),
    background = Color(0xFF04000F),
    onBackground = Color(0xFFF2EDFF),
    surface = Color(0xFF17122E),
    onSurface = Color(0xFFF2EDFF),
    surfaceVariant = Color(0xFF221A3E),
    onSurfaceVariant = Color(0xFFB9B3D6),
    surfaceContainerLowest = Color(0xFF04000F),
    surfaceContainerLow = Color(0xFF100A24),
    surfaceContainer = Color(0xFF17122E),
    surfaceContainerHigh = Color(0xFF221A3E),
    surfaceContainerHighest = Color(0xFF2C2350),
    error = Color(0xFFEF5350),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF5A1F1D),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF4A3F73),
    outlineVariant = Color(0xFF322A55),
)

/** Semantic colors outside Material's ColorScheme (dark-tuned, meanings kept). */
val MobiusSuccess: Color = Color(0xFF66BB6A)
val MobiusWarning: Color = Color(0xFFFFCA28)

/**
 * Logo gradient: deep blue → blue → purple. Used for the app title, the
 * bubble chip background, and primary buttons.
 */
val MobiusGradientBrush: Brush = Brush.linearGradient(
    listOf(Color(0xFF1A73E8), Color(0xFF4D7CFE), Color(0xFF7C4DFF)),
)

/** Logo glow color (also the unread-badge background). */
val MobiusGlow: Color = Color(0xFFB388FF)

/** Shared by the app screens and the overlay windows. */
@Composable
fun MobiusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MobiusColors, content = content)
}

/**
 * v7 — primary action button: logo gradient background, white label.
 * Disabled state uses a muted violet gradient (never plain grey).
 */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.background(
            brush = if (enabled) MobiusGradientBrush
                else Brush.linearGradient(listOf(Color(0xFF3A2E63), Color(0xFF2A2350))),
            shape = RoundedCornerShape(16.dp),
        ),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContentColor = Color(0xFF8F87B3),
        ),
        content = content,
    )
}

/**
 * v8.1-hotfix — top bar for the sub-screens (Settings, Log, Chat History):
 * a visible back arrow + title. Tapping the arrow (or the phone Back
 * button, see MainTabs) returns to HOME instead of closing the app.
 */
@Composable
fun BackTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = MobiusBackIcon,
                contentDescription = "Kembali",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
    }
}

// ------------------------------------------------------------------ bottom-nav icons
//
// Drawn from the canonical Material path data (ImageVector.Builder) so the
// nav needs NO new dependency: material-icons-core is only a *runtime*
// dependency of material3, so `Icons.Filled.*` does not compile here.
// Path strings were validated as well-formed SVG path data before use.

private const val HOME_PATH_DATA = "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"
private const val HISTORY_PATH_DATA =
    "M13,3c-4.97,0-9,4.03-9,9H1l3.89,3.89L9,12H6c0-3.87,3.13-7,7-7s7,3.13,7,7-3.13,7-7,7c-1.93,0-3.68-0.79-4.94-2.06l-1.42,1.42C8.27,19.99,10.51,21,13,21c4.97,0,9-4.03,9-9S17.97,3,13,3zM12,8v5l4.28,2.54l0.72-1.21l-3.5-2.08V8H12z"
private const val SETTINGS_PATH_DATA =
    "M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.63-0.06-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61 l-1.92-3.32c-0.12-0.2-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41 h-3.84c-0.24,0-0.44,0.17-0.48,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.07-0.47,0.02-0.59,0.22L2.74,8.87 C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.8,11.69,4.8,12s0.02,0.64,0.06,0.94l-2.03,1.58 c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.2,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54 c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.48-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96 c0.22,0.07,0.47-0.02,0.59-0.22l1.92-3.32c0.12-0.2,0.08-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6 s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z"

private fun mobiusIcon(name: String, vararg pathData: String): ImageVector =
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

val MobiusHomeIcon: ImageVector by lazy { mobiusIcon("Home", HOME_PATH_DATA) }
val MobiusHistoryIcon: ImageVector by lazy { mobiusIcon("History", HISTORY_PATH_DATA) }
val MobiusSettingsIcon: ImageVector by lazy { mobiusIcon("Settings", SETTINGS_PATH_DATA) }

// ------------------------------------------------------------------ v7.6 quick-chip + input icons
//
// Same no-new-dependency pattern as the nav icons: canonical Material path
// data, drawn with ImageVector.Builder. The sparkle is a plain 4-point star.

private const val CHAT_PATH_DATA =
    "M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z"
private const val TAG_PATH_DATA =
    "M21.41 11.58l-9-9C12.05 2.22 11.55 2 11 2H4c-1.1 0-2 .9-2 2v7c0 .55.22 1.05.59 1.42l9 9c.36.36.86.58 1.41.58.55 0 1.05-.22 1.41-.59l7-7c.37-.36.59-.86.59-1.41 0-.55-.23-1.06-.59-1.42zM5.5 7C4.67 7 4 6.33 4 5.5S4.67 4 5.5 4 7 4.67 7 5.5 6.33 7 5.5 7z"
private const val DOC_PATH_DATA =
    "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"
private const val SEARCH_PATH_DATA =
    "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
private const val SPARKLE_PATH_DATA =
    "M12 2 L14.5 9.5 L22 12 L14.5 14.5 L12 22 L9.5 14.5 L2 12 L9.5 9.5 Z"
private const val MIC_PATH_DATA_1 =
    "M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z"
private const val MIC_PATH_DATA_2 =
    "M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"

// v8.1-hotfix — canonical Material arrow_back path data (same no-new-dependency
// pattern as the other icons): the back arrow for the sub-screen top bars.
private const val BACK_PATH_DATA = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"

val MobiusChatIcon: ImageVector by lazy { mobiusIcon("Chat", CHAT_PATH_DATA) }
val MobiusTagIcon: ImageVector by lazy { mobiusIcon("Tag", TAG_PATH_DATA) }
val MobiusDocIcon: ImageVector by lazy { mobiusIcon("Doc", DOC_PATH_DATA) }
val MobiusSearchIcon: ImageVector by lazy { mobiusIcon("Search", SEARCH_PATH_DATA) }
val MobiusSparkleIcon: ImageVector by lazy { mobiusIcon("Sparkle", SPARKLE_PATH_DATA) }
val MobiusMicIcon: ImageVector by lazy { mobiusIcon("Mic", MIC_PATH_DATA_1, MIC_PATH_DATA_2) }
val MobiusBackIcon: ImageVector by lazy { mobiusIcon("Back", BACK_PATH_DATA) }

// ------------------------------------------------------------------ activity

class MainActivity : ComponentActivity() {
    /**
     * v6 UI wiring — the bubble cannot receive ACTION_OPEN_DOCUMENT results
     * (a Service has no ActivityResultLauncher), so its attach button starts
     * this activity with [EXTRA_OPEN_FILE_PICKER] and Home opens the system
     * picker. Incremented in onCreate/onNewIntent; HomeScreen collects it.
     */
    val pickFileRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v7.9-auth: Google Sign-In needs a Context once per process.
        GoogleAuth.init(this)
        // v7.9-auth: the browser returns from Google Sign-In via the
        // com.mobius:/oauth2redirect deep link, which lands here.
        handleAuthRedirect(intent)
        if (intent?.getBooleanExtra(EXTRA_OPEN_FILE_PICKER, false) == true) {
            pickFileRequests.value++
        }
        setContent { MobiusTheme { MobiusRoot(pickFileRequests) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // v7.9-auth: redirect may arrive while the activity already exists.
        handleAuthRedirect(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_FILE_PICKER, false)) {
            pickFileRequests.value++
        }
    }

    /**
     * v7.9-auth: forwards a Google Sign-In redirect to [GoogleAuth]. Only
     * intents with the com.mobius scheme are forwarded; everything else
     * (file picker, launcher) is untouched. The sign-in result itself is
     * NOT shown here — the Settings screen (Google sign-in UI) re-reads
     * GoogleAuth.isSignedIn() / accountEmail(). The outcome is only logged
     * as true/false, never with any token or code.
     */
    private fun handleAuthRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != GoogleAuth.REDIRECT_SCHEME) return
        lifecycleScope.launch {
            val ok = runCatching { GoogleAuth.handleRedirect(uri) }.getOrDefault(false)
            Log.i("MainActivity", "Google sign-in redirect handled: $ok")
        }
    }

    companion object {
        const val EXTRA_OPEN_FILE_PICKER = "com.mobius.extra.OPEN_FILE_PICKER"
    }
}

@Composable
private fun MobiusRoot(pickFileRequests: StateFlow<Int>) {
    val app = LocalContext.current.applicationContext as MobiusApp
    val onboardingDone by app.permissions.onboardingDone.collectAsState(initial = null)
    // v7 — language: DataStore setting, default Bahasa Indonesia; both roots
    // (this Activity and the bubble Service) read the same store, so a change
    // recomposes everywhere immediately — no restart, no reinstall.
    val languageManager = remember { LanguageManager(app.applicationContext) }
    val lang by languageManager.language.collectAsState(initial = AppLanguage.ID)
    val strings = stringsFor(lang)
    // v8.1 — core (RemoteGateway) reads the same language as the UI.
    SideEffect { currentStrings = strings }
    CompositionLocalProvider(LocalStrings provides strings) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (onboardingDone) {
                null -> Unit // still reading preferences
                false -> OnboardingScreen(app) { }
                true -> MainTabs(app, pickFileRequests)
            }
        }
    }
}

@Composable
private fun MainTabs(app: MobiusApp, pickFileRequests: StateFlow<Int>) {
    var destination by remember { mutableStateOf(MobiusDestination.HOME) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // v8.1-hotfix bug 2 — Back goes to HOME from any sub-screen instead of
    // closing the app. When the drawer is open, Back closes the drawer first
    // (ModalNavigationDrawer's own handler wins while open; this one covers
    // the closed-drawer case). On HOME, Back keeps its default (exit app).
    BackHandler(enabled = drawerState.isOpen || destination != MobiusDestination.HOME) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            destination = MobiusDestination.HOME
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MobiusDrawer(
                app = app,
                currentDestination = destination,
                onNavigate = { destination = it },
                onCloseDrawer = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        // v8.1-hotfix bug 1 — root fix: Compose's LocalContentColor defaults
        // to BLACK, and no Scaffold/Surface in this tree ever provided
        // onSurface, so every Text() without an explicit color rendered
        // black on the dark background. Provide onSurface here (exactly what
        // a Scaffold does) so no text in any sub-screen can fall back to
        // black again. Components that set their own content color
        // (buttons, chips, cards, drawer items) override this locally.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            when (destination) {
                MobiusDestination.HOME -> HomeScreen(
                    app,
                    pickFileRequests,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenSettings = {
                        destination = MobiusDestination.SETTINGS
                        scope.launch { drawerState.close() }
                    },
                )
                MobiusDestination.CHAT_HISTORY -> ChatHistoryScreen(app, onBack = { destination = MobiusDestination.HOME })
                MobiusDestination.LOG -> LogScreen(app, onBack = { destination = MobiusDestination.HOME })
                MobiusDestination.SETTINGS -> SettingsScreen(app, onRerunSetup = { }, onBack = { destination = MobiusDestination.HOME })
            }
        }
    }
}
