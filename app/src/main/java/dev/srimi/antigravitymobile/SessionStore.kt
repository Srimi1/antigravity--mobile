package dev.srimi.antigravitymobile

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "checks")
data class CheckRecord(
    @PrimaryKey val id: String,
    val name: String,
    val status: String,
    val detail: String,
    val startedAt: Long,
    val durationMs: Long = 0,
)
@Dao
interface CheckDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(record: CheckRecord)
    @Query("SELECT * FROM checks ORDER BY startedAt DESC") fun observe(): Flow<List<CheckRecord>>
    @Query("SELECT * FROM checks ORDER BY startedAt DESC") suspend fun all(): List<CheckRecord>
    @Query("UPDATE checks SET status='INTERRUPTED', detail='App process stopped; action was not replayed.' WHERE status='RUNNING'")
    suspend fun interruptUnfinished()
}
@Database(entities = [CheckRecord::class], version = 1, exportSchema = false)
abstract class SessionStore : RoomDatabase() { abstract fun checks(): CheckDao }
