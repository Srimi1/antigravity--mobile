package androidx.activity
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.*
open class ComponentActivity : android.app.Activity(), LifecycleOwner {
    fun <I, O> registerForActivityResult(contract: ActivityResultContract<I, O>, callback: ActivityResultCallback<O>): ActivityResultLauncher<I> = TODO()
}
inline fun <reified VM : ViewModel> ComponentActivity.viewModels(): Lazy<VM> = TODO()
fun ComponentActivity.enableEdgeToEdge() {}
