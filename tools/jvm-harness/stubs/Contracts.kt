package androidx.activity.result.contract
import android.net.Uri
abstract class ActivityResultContract<I, O>
object ActivityResultContracts {
    open class CreateDocument(mimeType: String) : ActivityResultContract<String, Uri?>()
    open class OpenDocument : ActivityResultContract<Array<String>, Uri?>()
    open class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>()
}
