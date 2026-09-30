package androidx.lifecycle
class ViewModelStore { fun clear() {} }
class ViewModelProvider(store: ViewModelStore, factory: Factory) {
    interface Factory
    open class AndroidViewModelFactory(application: android.app.Application) : Factory
    fun <T : ViewModel> get(klass: Class<T>): T = TODO()
}
