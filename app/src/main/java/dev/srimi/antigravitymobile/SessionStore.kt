package dev.srimi.antigravitymobile

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** A project lives in app-private storage under `projects/<directory>`. */
@Entity(tableName = "projects")
data class ProjectRecord(
    @PrimaryKey val id: String,
    val name: String,
    val directory: String,
    val createdAt: Long,
    val openedAt: Long,
)
@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(project: ProjectRecord)
    @Query("SELECT * FROM projects ORDER BY openedAt DESC") fun observe(): Flow<List<ProjectRecord>>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun find(id: String): ProjectRecord?
    @Query("SELECT * FROM projects WHERE directory = :directory") suspend fun findByDirectory(directory: String): ProjectRecord?
    @Query("DELETE FROM projects WHERE id = :id") suspend fun delete(id: String)
}

/** status: IDLE, RUNNING or INTERRUPTED. A RUNNING task found at startup is never resumed. */
@Entity(tableName = "conversations")
data class ConversationRecord(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val provider: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)
/** role: user, assistant, tool, notice or error. Credentials and raw provider payloads are never stored here. */
@Entity(tableName = "messages")
data class MessageRecord(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)
/** status: AWAITING_APPROVAL, RUNNING, COMPLETED, DECLINED, FAILED or INTERRUPTED. */
@Entity(tableName = "actions")
data class ActionRecord(
    @PrimaryKey val id: String,
    val conversationId: String,
    val projectId: String,
    val tool: String,
    val summary: String,
    val status: String,
    val detail: String,
    val createdAt: Long,
    val updatedAt: Long,
)
@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(conversation: ConversationRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveMessage(message: MessageRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAction(action: ActionRecord)
    @Query("SELECT * FROM conversations WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeForProject(projectId: String): Flow<List<ConversationRecord>>
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun find(id: String): ConversationRecord?
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageRecord>>
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun messages(conversationId: String): List<MessageRecord>
    @Query("SELECT * FROM actions WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeActions(conversationId: String): Flow<List<ActionRecord>>
    @Query("SELECT * FROM conversations WHERE status = 'RUNNING'") suspend fun running(): List<ConversationRecord>
    @Query("UPDATE conversations SET status = 'INTERRUPTED' WHERE status = 'RUNNING'") suspend fun interruptRunning()
    @Query("UPDATE actions SET status = 'INTERRUPTED', detail = 'App process stopped; action was not replayed.' WHERE status IN ('AWAITING_APPROVAL', 'RUNNING')")
    suspend fun interruptActions()
    @Query("DELETE FROM messages WHERE conversationId IN (SELECT id FROM conversations WHERE projectId = :projectId)")
    suspend fun deleteMessagesForProject(projectId: String)
    @Query("DELETE FROM actions WHERE projectId = :projectId") suspend fun deleteActionsForProject(projectId: String)
    @Query("DELETE FROM conversations WHERE projectId = :projectId") suspend fun deleteForProject(projectId: String)
    @Query("DELETE FROM messages WHERE conversationId = :id") suspend fun deleteMessages(id: String)
    @Query("DELETE FROM actions WHERE conversationId = :id") suspend fun deleteActions(id: String)
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun delete(id: String)
}

/** status: OPEN (agent task still editing), REVIEW, ACCEPTED, REVERTED or COMMITTED. */
@Entity(tableName = "change_sets")
data class ChangeSetRecord(
    @PrimaryKey val id: String,
    val projectId: String,
    val conversationId: String?,
    val summary: String,
    val status: String,
    val detail: String,
    val createdAt: Long,
    val updatedAt: Long,
)
/** File snapshots live under `changesets/<changeSetId>/{before,after}/<path>`; only flags are stored in Room. */
@Entity(tableName = "change_files", primaryKeys = ["changeSetId", "path"])
data class ChangeFileRecord(
    val changeSetId: String,
    val path: String,
    val beforeExists: Boolean,
    val afterExists: Boolean,
    val updatedAt: Long,
)
@Dao
interface ChangeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSet(set: ChangeSetRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveFile(file: ChangeFileRecord)
    @Query("SELECT * FROM change_sets WHERE id = :id") suspend fun findSet(id: String): ChangeSetRecord?
    @Query("SELECT * FROM change_sets WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observeSets(projectId: String): Flow<List<ChangeSetRecord>>
    @Query("SELECT * FROM change_sets WHERE projectId = :projectId ORDER BY createdAt DESC")
    suspend fun sets(projectId: String): List<ChangeSetRecord>
    @Query("SELECT * FROM change_sets WHERE status = :status") suspend fun setsWithStatus(status: String): List<ChangeSetRecord>
    @Query("SELECT * FROM change_files WHERE changeSetId = :changeSetId ORDER BY path")
    suspend fun files(changeSetId: String): List<ChangeFileRecord>
    @Query("SELECT * FROM change_files WHERE changeSetId = :changeSetId AND path = :path")
    suspend fun file(changeSetId: String, path: String): ChangeFileRecord?
    @Query("DELETE FROM change_files WHERE changeSetId = :changeSetId") suspend fun deleteFiles(changeSetId: String)
    @Query("DELETE FROM change_sets WHERE id = :id") suspend fun deleteSet(id: String)
}

@Database(
    entities = [CheckRecord::class, ProjectRecord::class, ConversationRecord::class, MessageRecord::class,
        ActionRecord::class, ChangeSetRecord::class, ChangeFileRecord::class],
    version = 2, exportSchema = false,
)
abstract class SessionStore : RoomDatabase() {
    abstract fun checks(): CheckDao
    abstract fun projects(): ProjectDao
    abstract fun conversations(): ConversationDao
    abstract fun changes(): ChangeDao

    companion object {
        /** Adds the full-app tables. Version 1 check history is left untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach(db::execSQL)
            }
        }
        val MIGRATION_1_2_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `projects` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `directory` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `openedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`provider` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `actions` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `projectId` TEXT NOT NULL, " +
                "`tool` TEXT NOT NULL, `summary` TEXT NOT NULL, `status` TEXT NOT NULL, `detail` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `change_sets` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `conversationId` TEXT, " +
                "`summary` TEXT NOT NULL, `status` TEXT NOT NULL, `detail` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `change_files` (`changeSetId` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                "`beforeExists` INTEGER NOT NULL, `afterExists` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`changeSetId`, `path`))",
        )
    }
}
