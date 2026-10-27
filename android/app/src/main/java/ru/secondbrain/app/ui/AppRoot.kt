package ru.secondbrain.app.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.secondbrain.app.AppContainer
import ru.secondbrain.app.core.Prefs
import ru.secondbrain.app.ui.agent.AgentHistoryScreen
import ru.secondbrain.app.ui.agent.AgentJobsScreen
import ru.secondbrain.app.ui.agent.PersonaScreen
import ru.secondbrain.app.ui.agent.ToolsScreen
import ru.secondbrain.app.ui.analytics.AnalyticsScreen
import ru.secondbrain.app.ui.chat.ChatScreen
import ru.secondbrain.app.ui.chat.ConversationsScreen
import ru.secondbrain.app.ui.components.LocalBack
import ru.secondbrain.app.ui.components.LocalContainer
import ru.secondbrain.app.ui.components.LocalNavigate
import ru.secondbrain.app.ui.components.LocalSnackbar
import ru.secondbrain.app.ui.finance.AccountsScreen
import ru.secondbrain.app.ui.finance.CategoriesScreen
import ru.secondbrain.app.ui.finance.DebtsScreen
import ru.secondbrain.app.ui.finance.FinanceScreen
import ru.secondbrain.app.ui.finance.GoalsScreen
import ru.secondbrain.app.ui.finance.ImportStatementScreen
import ru.secondbrain.app.ui.finance.ReviewScreen
import ru.secondbrain.app.ui.finance.SubscriptionsScreen
import ru.secondbrain.app.ui.finance.TaxesScreen
import ru.secondbrain.app.ui.finance.TransactionScreen
import ru.secondbrain.app.ui.finance.TransactionsScreen
import ru.secondbrain.app.ui.finance.WishlistScreen
import ru.secondbrain.app.ui.fitness.ExerciseScreen
import ru.secondbrain.app.ui.fitness.FitnessScreen
import ru.secondbrain.app.ui.fitness.MetricsScreen
import ru.secondbrain.app.ui.fitness.NutritionScreen
import ru.secondbrain.app.ui.fitness.PhotosScreen
import ru.secondbrain.app.ui.fitness.PlanScreen
import ru.secondbrain.app.ui.fitness.WorkoutScreen
import ru.secondbrain.app.ui.help.HelpScreen
import ru.secondbrain.app.ui.home.HomeScreen
import ru.secondbrain.app.ui.home.MoreScreen
import ru.secondbrain.app.ui.home.PortraitScreen
import ru.secondbrain.app.ui.memory.ImportDetailScreen
import ru.secondbrain.app.ui.memory.ImportsScreen
import ru.secondbrain.app.ui.memory.MemoryItemScreen
import ru.secondbrain.app.ui.memory.MemoryScreen
import ru.secondbrain.app.ui.memory.PeopleScreen
import ru.secondbrain.app.ui.memory.PersonScreen
import ru.secondbrain.app.ui.memory.QuestionsScreen
import ru.secondbrain.app.ui.memory.SourcesScreen
import ru.secondbrain.app.ui.onboarding.OnboardingScreen
import ru.secondbrain.app.ui.settings.DiagnosticsScreen
import ru.secondbrain.app.ui.settings.DevicesScreen
import ru.secondbrain.app.ui.settings.InitiativeScreen
import ru.secondbrain.app.ui.settings.IntegrationsScreen
import ru.secondbrain.app.ui.settings.ModelsScreen
import ru.secondbrain.app.ui.settings.PrivacyScreen
import ru.secondbrain.app.ui.settings.ScheduleScreen
import ru.secondbrain.app.ui.settings.SettingsScreen
import ru.secondbrain.app.ui.settings.UpdatesScreen
import ru.secondbrain.app.ui.settings.UsageScreen
import ru.secondbrain.app.ui.tasks.ExperimentsScreen
import ru.secondbrain.app.ui.tasks.HabitsScreen
import ru.secondbrain.app.ui.tasks.MoodScreen
import ru.secondbrain.app.ui.tasks.NotesScreen
import ru.secondbrain.app.ui.tasks.TasksScreen
import ru.secondbrain.app.ui.theme.SecondBrainTheme
import ru.secondbrain.app.ui.theme.Tone

private data class Tab(val route: String, val label: String, val icon: ImageVector)

// The agent is home; chats, memory and the rest are reached from it (history and ☰ buttons).
private val TABS = listOf(
    Tab("home", "Агент", Icons.Outlined.AutoAwesome),
    Tab("finance", "Деньги", Icons.Outlined.AccountBalanceWallet),
    Tab("fitness", "Тренировки", Icons.Outlined.FitnessCenter),
)

@Composable
fun AppRoot(container: AppContainer, activity: FragmentActivity, pendingRoute: MutableState<String?>) {
    val theme by container.prefs.flow(Prefs.Keys.theme).collectAsState(initial = null)
    SecondBrainTheme(theme) {
        val snackbar = remember { SnackbarHostState() }
        var paired by remember { mutableStateOf<Boolean?>(null) }
        var onboarded by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            onboarded = container.prefs.get(Prefs.Keys.onboardingDone) == true
            // Follows the token, so a device revoked elsewhere drops back to pairing (see SyncManager).
            container.prefs.flow(Prefs.Keys.tokenEnc).collect { paired = it != null }
        }
        CompositionLocalProvider(LocalContainer provides container, LocalSnackbar provides snackbar) {
            when {
                paired == null -> Box(Modifier.fillMaxSize())
                paired == false || !onboarded -> OnboardingScreen(startAtSetup = paired == true,
                    pairingOnly = paired == false && onboarded) {
                    paired = true; onboarded = true
                }
                else -> Locked(container, activity) { MainScaffold(container, pendingRoute) }
            }
        }
    }
}

/** Biometric lock on start and after the app was in background longer than the timeout. */
@Composable
private fun Locked(container: AppContainer, activity: FragmentActivity, content: @Composable () -> Unit) {
    val enabled by container.prefs.flow(Prefs.Keys.biometricEnabled).collectAsState(initial = null)
    val timeout by container.prefs.flow(Prefs.Keys.lockTimeoutMin).collectAsState(initial = 5)
    var unlocked by remember { mutableStateOf(false) }
    var backgroundAt by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { backgroundAt = System.currentTimeMillis() }
            override fun onStart(owner: LifecycleOwner) {
                val limit = (timeout ?: 5) * 60_000L
                if (backgroundAt > 0 && System.currentTimeMillis() - backgroundAt > limit) unlocked = false
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
    }

    fun prompt() {
        val authenticators = if (Build.VERSION.SDK_INT >= 30)
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            unlocked = true  // no biometrics and no screen lock on the device: nothing to check against
            return
        }
        val bp = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
        })
        bp.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Второй мозг")
            .setSubtitle("Подтвердите, что это вы").setAllowedAuthenticators(authenticators).build())
    }

    when {
        enabled == null -> Box(Modifier.fillMaxSize())
        enabled == false || unlocked -> content()
        else -> {
            LaunchedEffect(unlocked) { if (!unlocked) prompt() }
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Lock, null, tint = Tone.current.accent)
                Text("Приложение заблокировано", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 24.dp))
                Button(onClick = { prompt() }) { Text("Разблокировать") }
            }
        }
    }
}

@Composable
private fun MainScaffold(container: AppContainer, pendingRoute: MutableState<String?>) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    LaunchedEffect(Unit) {
        container.refreshLocalSettings()
        container.sync.requestSync(0)
        container.updates.checkIfStale()
        container.initPush()
    }
    LaunchedEffect(pendingRoute.value) {
        pendingRoute.value?.let { route ->
            runCatching { nav.navigate(route) }
            pendingRoute.value = null
        }
    }
    val navigate: (String) -> Unit = { route -> runCatching { nav.navigate(route) } }
    CompositionLocalProvider(LocalNavigate provides navigate, LocalBack provides { nav.popBackStack() }) {
        Scaffold(
            containerColor = Tone.current.bg,
            bottomBar = {
                if (TABS.any { it.route == current }) Column {
                    HorizontalDivider(color = Tone.current.divider)
                    NavigationBar(containerColor = Tone.current.bg, tonalElevation = 0.dp) {
                        TABS.forEach { tab ->
                            NavigationBarItem(
                                selected = current == tab.route,
                                onClick = { navigateTab(nav, tab.route) },
                                icon = { Icon(tab.icon, tab.label) },
                                // One line always: on narrow phones or with a large system font "Тренировки" used to
                                // wrap its last letter; shrink a little instead.
                                label = {
                                    BasicText(tab.label, maxLines = 1, softWrap = false,
                                        style = MaterialTheme.typography.labelMedium.copy(color = LocalContentColor.current),
                                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = 0.5.sp))
                                },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = Tone.current.fill,
                                    selectedIconColor = Tone.current.text, selectedTextColor = Tone.current.text,
                                    unselectedIconColor = Tone.current.textSecondary, unselectedTextColor = Tone.current.textSecondary),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(bottom = padding.calculateBottomPadding())) { Routes(nav) }
        }
    }
}

private fun navigateTab(nav: NavHostController, route: String) {
    // Home is the start destination: navigating to it with restoreState would restore whatever tab stack was
    // last saved against it (popUpTo("home") { saveState } maps that state to "home"), so pop back instead.
    if (route == "home") {
        nav.popBackStack("home", inclusive = false, saveState = true)
        return
    }
    nav.navigate(route) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun Routes(nav: NavHostController) {
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen() }
        composable("more") { MoreScreen() }
        composable("finance") { FinanceScreen() }
        composable("finance/transactions") { TransactionsScreen() }
        composable("finance/tx/{id}") { TransactionScreen(it.arguments?.getString("id")!!) }
        composable("finance/new-tx") { TransactionScreen(null) }
        composable("finance/accounts") { AccountsScreen() }
        composable("finance/categories") { CategoriesScreen() }
        composable("finance/subscriptions") { SubscriptionsScreen() }
        composable("finance/debts") { DebtsScreen() }
        composable("finance/goals") { GoalsScreen() }
        composable("finance/taxes") { TaxesScreen() }
        composable("finance/review") { ReviewScreen() }
        composable("finance/wishlist") { WishlistScreen() }
        composable("finance/import") { ImportStatementScreen() }
        composable("fitness") { FitnessScreen() }
        composable("fitness/workout/{id}") { WorkoutScreen(it.arguments?.getString("id")!!) }
        composable("fitness/plan/{id}") { PlanScreen(it.arguments?.getString("id")!!) }
        composable("fitness/exercise/{id}") { ExerciseScreen(it.arguments?.getString("id")!!) }
        composable("fitness/metrics") { MetricsScreen() }
        composable("fitness/photos") { PhotosScreen() }
        composable("fitness/nutrition") { NutritionScreen() }
        composable("chat") { ConversationsScreen() }
        composable("chat/{id}?q={q}", arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true })) {
            ChatScreen(it.arguments?.getString("id")!!, initial = it.arguments?.getString("q"))
        }
        composable("memory") { MemoryScreen() }
        composable("memory/item/{id}") { MemoryItemScreen(it.arguments?.getString("id")!!) }
        composable("memory/people") { PeopleScreen() }
        composable("memory/person/{id}") { PersonScreen(it.arguments?.getString("id")!!) }
        composable("memory/questions") { QuestionsScreen() }
        composable("memory/sources") { SourcesScreen() }
        composable("memory/imports") { ImportsScreen() }
        composable("memory/import/{id}") { ImportDetailScreen(it.arguments?.getString("id")!!) }
        composable("tasks") { TasksScreen() }
        composable("habits") { HabitsScreen() }
        composable("experiments") { ExperimentsScreen() }
        composable("mood") { MoodScreen() }
        composable("notes") { NotesScreen() }
        composable("analytics") { AnalyticsScreen() }
        composable("agent/history") { AgentHistoryScreen() }
        composable("agent/jobs") { AgentJobsScreen() }
        composable("agent/persona") { PersonaScreen() }
        composable("agent/tools") { ToolsScreen() }
        composable("settings") { SettingsScreen() }
        composable("settings/models") { ModelsScreen() }
        composable("settings/usage") { UsageScreen() }
        composable("settings/initiative") { InitiativeScreen() }
        composable("settings/schedule") { ScheduleScreen() }
        composable("settings/privacy") { PrivacyScreen() }
        composable("settings/integrations") { IntegrationsScreen() }
        composable("settings/devices") { DevicesScreen() }
        composable("updates") { UpdatesScreen() }
        composable("help") { HelpScreen() }
        composable("portrait") { PortraitScreen() }
        composable("diagnostics") { DiagnosticsScreen() }
    }
}
