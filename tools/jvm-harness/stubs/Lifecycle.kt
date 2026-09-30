package androidx.lifecycle
import kotlinx.coroutines.CoroutineScope
abstract class ViewModel { protected open fun onCleared() {} }
open class AndroidViewModel(application: android.app.Application) : ViewModel() { fun <T : android.app.Application> getApplication(): T = TODO() }
val ViewModel.viewModelScope: CoroutineScope get() = TODO()
interface LifecycleOwner
val LifecycleOwner.lifecycleScope: LifecycleCoroutineScope get() = TODO()
abstract class LifecycleCoroutineScope : CoroutineScope
