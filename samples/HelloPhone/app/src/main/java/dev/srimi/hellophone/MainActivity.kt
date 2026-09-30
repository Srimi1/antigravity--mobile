package dev.srimi.hellophone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        var count by remember { mutableIntStateOf(0) }
                        Text("Hello from your phone", style = MaterialTheme.typography.headlineMedium)
                        Text("Button presses: $count")
                        Button(onClick = { count++ }) { Text("Count") }
                    }
                }
            }
        }
    }
}
