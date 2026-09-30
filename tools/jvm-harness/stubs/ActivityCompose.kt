package androidx.activity.compose
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
fun ComponentActivity.setContent(parent: CompositionContext? = null, content: @Composable () -> Unit) {}
@Composable fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): ActivityResultLauncher<I> = TODO()
@Composable fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {}
