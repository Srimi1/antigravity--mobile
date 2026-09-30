// Compile-only signature stubs for the harness. Real classes come from Google Maven in the app build.
package androidx.room
import kotlin.reflect.KClass
@Target(AnnotationTarget.CLASS) annotation class Entity(val tableName: String = "", val indices: Array<Index> = [], val primaryKeys: Array<String> = [])
annotation class Index(vararg val value: String, val unique: Boolean = false)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER) annotation class PrimaryKey(val autoGenerate: Boolean = false)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER) annotation class ColumnInfo(val name: String = "")
@Target(AnnotationTarget.CLASS) annotation class Dao
annotation class Insert(val onConflict: Int = OnConflictStrategy.ABORT)
annotation class Update(val onConflict: Int = OnConflictStrategy.ABORT)
annotation class Delete
annotation class Query(val value: String)
annotation class Transaction
annotation class Database(val entities: Array<KClass<*>>, val version: Int, val exportSchema: Boolean = true)
annotation class OnConflictStrategy { companion object { const val REPLACE = 1; const val ABORT = 3; const val IGNORE = 5 } }
abstract class RoomDatabase {
    open fun close() {}
    open fun runInTransaction(body: Runnable) {}
    class Builder<T : RoomDatabase> {
        fun addMigrations(vararg migrations: androidx.room.migration.Migration): Builder<T> = this
        fun allowMainThreadQueries(): Builder<T> = this
        fun build(): T = TODO()
    }
}
object Room {
    fun <T : RoomDatabase> databaseBuilder(context: android.content.Context, klass: Class<T>, name: String?): RoomDatabase.Builder<T> = TODO()
    fun <T : RoomDatabase> inMemoryDatabaseBuilder(context: android.content.Context, klass: Class<T>): RoomDatabase.Builder<T> = TODO()
}
suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R = block()
