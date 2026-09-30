package dev.srimi.antigravitymobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    PROJECTS("Projects", Icons.Default.Home),
    AGENT("Agent", Icons.Default.Face),
    CHANGES("Changes", Icons.Default.Edit),
    BUILD("Build", Icons.Default.Build),
    ACCOUNTS("Accounts", Icons.Default.AccountCircle),
}

class MainActivity : ComponentActivity() {
    private val projects: ProjectsViewModel by viewModels()
    private val agent: AgentViewModel by viewModels()
    private val changes: ChangesViewModel by viewModels()
    private val build: BuildViewModel by viewModels()
    private val accounts: AccountsViewModel by viewModels()
    private val probe: ProbeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AntigravityTheme {
                var tab by rememberSaveable { mutableStateOf(Tab.PROJECTS) }
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val notify: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text, withDismissAction = true) } }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { item ->
                                NavigationBarItem(selected = tab == item, onClick = {
                                    tab = item
                                    if (item == Tab.AGENT) agent.refreshAccount()
                                    if (item == Tab.BUILD) build.inspect()
                                }, icon = { Icon(item.icon, contentDescription = null) }, label = { Text(item.label) })
                            }
                        }
                    },
                ) { padding ->
                    Surface(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                        when (tab) {
                            Tab.PROJECTS -> ProjectsScreen(projects, notify)
                            Tab.AGENT -> AgentScreen(agent, onOpenProjects = { tab = Tab.PROJECTS }, onOpenAccounts = { tab = Tab.ACCOUNTS })
                            Tab.CHANGES -> ChangesScreen(changes, notify, onOpenProjects = { tab = Tab.PROJECTS })
                            Tab.BUILD -> BuildScreen(build, probe, notify)
                            Tab.ACCOUNTS -> AccountsScreen(accounts, notify)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the browser sign-in or Android's installer can change account and file state.
        accounts.refresh()
        agent.refreshAccount()
    }
}
