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

/** Task state is owned by runtime_tasks; this table projects it into conversation history. */
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
    @Query("SELECT * FROM messages WHERE id=:id") suspend fun message(id: String): MessageRecord?
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

/** A build runs from its approved immutable archive in a separate worker UID. */
@Entity(tableName = "build_runs")
data class BuildRecord(
    @PrimaryKey val id: String,
    val projectId: String,
    val tasks: String,
    val status: String,
    val detail: String,
    val snapshotHash: String,
    val createdAt: Long,
    val finishedAt: Long = 0,
    val durationMs: Long = 0,
    @ColumnInfo(defaultValue = "'PENDING'") val artifactState: String = "PENDING",
    val agentTaskId: String? = null,
)
@Dao
interface BuildDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(record: BuildRecord)
    @Query("SELECT * FROM build_runs WHERE id = :id") suspend fun find(id: String): BuildRecord?
    @Query("UPDATE build_runs SET status = 'DISPATCHING', detail = 'Starting build worker' WHERE id = :id AND status = 'AWAITING_APPROVAL'")
    suspend fun claimApproval(id: String): Int
    @Query("SELECT * FROM build_runs WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observe(projectId: String): Flow<List<BuildRecord>>
    @Query("SELECT * FROM build_runs WHERE status IN ('RUNNING', 'CANCEL_REQUESTED', 'DISPATCHING')")
    suspend fun unfinished(): List<BuildRecord>
    @Query("SELECT * FROM build_runs WHERE projectId = :projectId ORDER BY createdAt DESC")
    suspend fun forProject(projectId: String): List<BuildRecord>
    @Query("UPDATE build_runs SET status=:status, detail=:detail, finishedAt=:at WHERE id=:id AND status='AWAITING_APPROVAL'")
    suspend fun resolvePending(id: String, status: String, detail: String, at: Long): Int
    @Query("UPDATE build_runs SET status='INTERRUPTED', detail='Approval interrupted; command was not dispatched', finishedAt=:at WHERE status='AWAITING_APPROVAL'")
    suspend fun interruptPending(at: Long)
}

/** One nullable unique slot enforces one active task across native and CLI backends, including paused tasks. */
@Entity(tableName = "runtime_tasks", indices = [Index(value = ["activeSlot"], unique = true)])
data class RuntimeTaskRecord(
    @PrimaryKey val id: String,
    val projectId: String,
    val conversationId: String,
    val providerId: String,
    val backend: String,
    val prompt: String,
    val status: String,
    val detail: String,
    val recoveryAction: String?,
    val transcript: String,
    val completedSteps: Int,
    val nextStep: String,
    val historySize: Int,
    val changeSetId: String?,
    val autoApproveEdits: Boolean,
    val activeSlot: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val providerSelection: String? = null,
)

@Entity(tableName = "runtime_actions", indices = [Index(value = ["taskId", "toolCallId"], unique = true)])
data class RuntimeActionRecord(
    @PrimaryKey val id: String,
    val taskId: String,
    val toolCallId: String,
    val tool: String,
    val arguments: String,
    val summary: String,
    val preview: String,
    val category: String?,
    val buildId: String?,
    val status: String,
    val decision: String?,
    val decidedAt: Long?,
    val outcome: String?,
    val resultText: String?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val allEdits: Boolean = false,
)

@Dao
interface RuntimeDao {
    @Insert suspend fun createTask(task: RuntimeTaskRecord)
    @Query("SELECT * FROM runtime_tasks WHERE id=:id") suspend fun task(id: String): RuntimeTaskRecord?
    @Query("SELECT * FROM runtime_tasks WHERE id=:id") fun observeTask(id: String): Flow<RuntimeTaskRecord?>
    @Query("SELECT * FROM runtime_tasks WHERE activeSlot=1 LIMIT 1") suspend fun active(): RuntimeTaskRecord?
    @Query("SELECT * FROM runtime_tasks WHERE activeSlot=1 LIMIT 1") fun observeActive(): Flow<RuntimeTaskRecord?>
    @Query("SELECT * FROM runtime_tasks WHERE conversationId=:id ORDER BY createdAt, id")
    suspend fun forConversation(id: String): List<RuntimeTaskRecord>
    @Query("SELECT * FROM runtime_tasks WHERE conversationId=:id ORDER BY createdAt DESC LIMIT 1")
    fun observeLatest(id: String): Flow<RuntimeTaskRecord?>
    @Query("UPDATE runtime_tasks SET transcript=:transcript, completedSteps=:steps, nextStep=:next, updatedAt=:at WHERE id=:id")
    suspend fun checkpoint(id: String, transcript: String, steps: Int, next: String, at: Long)
    @Query("UPDATE runtime_tasks SET status=:status, detail=:detail, recoveryAction=:recovery, activeSlot=:slot, updatedAt=:at WHERE id=:id AND activeSlot=1 AND status!='Cancelled'")
    suspend fun phase(id: String, status: String, detail: String, recovery: String?, slot: Int?, at: Long): Int
    @Query("UPDATE runtime_tasks SET changeSetId=:setId WHERE id=:id") suspend fun changeSet(id: String, setId: String?)
    @Query("UPDATE runtime_tasks SET activeSlot=NULL WHERE id=:id AND status='Cancelled'") suspend fun releaseCancelled(id: String)
    @Query("UPDATE runtime_tasks SET detail=:detail, recoveryAction=:recovery, updatedAt=:at WHERE id=:id AND status='Cancelled' AND activeSlot=1")
    suspend fun cancellationDetail(id: String, detail: String, recovery: String?, at: Long)
    @Query("UPDATE runtime_tasks SET autoApproveEdits=1 WHERE id=:id AND activeSlot=1") suspend fun approveEdits(id: String)
    @Insert suspend fun createAction(action: RuntimeActionRecord)
    @Query("SELECT * FROM runtime_actions WHERE taskId=:taskId AND toolCallId=:callId")
    suspend fun action(taskId: String, callId: String): RuntimeActionRecord?
    @Query("SELECT * FROM runtime_actions WHERE taskId=:id ORDER BY createdAt, id") suspend fun actions(id: String): List<RuntimeActionRecord>
    @Query("SELECT * FROM runtime_actions WHERE taskId=:id ORDER BY createdAt, id") fun observeActions(id: String): Flow<List<RuntimeActionRecord>>
    @Query("UPDATE runtime_actions SET summary=:summary, preview=:preview, category=:category, buildId=:buildId, status=:status, updatedAt=:at WHERE id=:id AND status='PREPARING'")
    suspend fun prepared(id: String, summary: String, preview: String, category: String?, buildId: String?, status: String, at: Long): Int
    @Query("UPDATE runtime_actions SET decision=:decision, allEdits=:allEdits, decidedAt=:at, status=:decision, updatedAt=:at WHERE id=:actionId AND taskId=:taskId AND toolCallId=:callId AND ((buildId IS NULL AND :buildId IS NULL) OR buildId=:buildId) AND status='AWAITING_APPROVAL' AND decision IS NULL AND EXISTS (SELECT 1 FROM runtime_tasks WHERE id=:taskId AND activeSlot=1 AND status='AwaitingApproval')")
    suspend fun decide(taskId: String, actionId: String, callId: String, buildId: String?, decision: String, allEdits: Boolean, at: Long): Int
    @Query("UPDATE runtime_actions SET status='RUNNING', updatedAt=:at WHERE id=:id AND status IN ('READY','APPROVED') AND (category IS NULL OR decision='APPROVED') AND EXISTS (SELECT 1 FROM runtime_tasks WHERE id=runtime_actions.taskId AND activeSlot=1 AND status IN ('Running','AwaitingApproval'))")
    suspend fun claim(id: String, at: Long): Int
    @Query("UPDATE runtime_actions SET outcome=:outcome, resultText=:text, status=:status, updatedAt=:at WHERE id=:id AND (outcome IS NULL OR (status='INTERRUPTED' AND :status!='INTERRUPTED' AND ((tool='build_project' AND buildId IS NOT NULL) OR tool IN ('cli_command','cli_file_change'))))")
    suspend fun finish(id: String, outcome: String, text: String, status: String, at: Long): Int
    @Query("SELECT * FROM runtime_actions WHERE tool='build_project' AND buildId IS NOT NULL AND (outcome IS NULL OR status='INTERRUPTED') AND status IN ('RUNNING','INTERRUPTED')")
    suspend fun unresolvedBuilds(): List<RuntimeActionRecord>
    @Query("SELECT * FROM runtime_actions WHERE taskId=:taskId AND buildId=:buildId AND tool='build_project' AND decision='APPROVED' AND status='RUNNING' AND outcome IS NULL LIMIT 1")
    suspend fun authorizedBuild(taskId: String, buildId: String): RuntimeActionRecord?
    @Query("UPDATE runtime_actions SET decision=:decision, decidedAt=:at WHERE taskId=:id AND status='AWAITING_APPROVAL' AND decision IS NULL")
    suspend fun closePending(id: String, decision: String, at: Long)
    @Query("UPDATE runtime_tasks SET autoApproveEdits=0, status='Paused', detail='App process stopped; recorded actions will be checked before retry', recoveryAction='Review Changes and build receipts, then retry this provider.', updatedAt=:at WHERE activeSlot=1 AND backend=:backend AND status IN ('Queued','Running','AwaitingApproval')")
    suspend fun pauseAfterDeath(at: Long, backend: String = "Native")
    @Query("DELETE FROM runtime_actions WHERE taskId IN (SELECT id FROM runtime_tasks WHERE conversationId=:id)")
    suspend fun deleteActionsForConversation(id: String)
    @Query("DELETE FROM runtime_tasks WHERE conversationId=:id AND activeSlot IS NULL")
    suspend fun deleteTasksForConversation(id: String)

    @Transaction suspend fun answer(taskId: String, actionId: String, callId: String, buildId: String?, decision: String, allEdits: Boolean, at: Long): Boolean {
        if (decision !in setOf("APPROVED", "DECLINED", "CANCELLED", "INTERRUPTED")) return false
        val action = action(taskId, callId) ?: return false
        if (allEdits && (decision != "APPROVED" || action.category != "Edit")) return false
        if (decide(taskId, actionId, callId, buildId, decision, allEdits, at) != 1) return false
        if (allEdits) approveEdits(taskId)
        return true
    }
}

/** Exact v4 provider schemas agreed with Lane B. Never store credentials or guessed allowances here. */
@Entity(tableName = "provider_models", primaryKeys = ["providerId", "modelId"])
data class ProviderModelRecord(val providerId: String, val modelId: String, val toolCallingVerified: Boolean, val verifiedAt: Long)
@Entity(tableName = "provider_usage")
data class ProviderUsageRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: String,
    val modelId: String,
    val taskId: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val requests: Int,
    val recordedAt: Long,
)
@Dao
interface ProviderUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveModel(record: ProviderModelRecord)
    @Query("SELECT * FROM provider_models WHERE providerId=:providerId") suspend fun models(providerId: String): List<ProviderModelRecord>
    @Insert suspend fun recordUsage(record: ProviderUsageRecord)
    @Query("SELECT * FROM provider_usage WHERE providerId=:providerId AND recordedAt>=:since ORDER BY recordedAt")
    suspend fun usage(providerId: String, since: Long): List<ProviderUsageRecord>
}

@Database(
    entities = [CheckRecord::class, ProjectRecord::class, ConversationRecord::class, MessageRecord::class,
        ActionRecord::class, ChangeSetRecord::class, ChangeFileRecord::class, BuildRecord::class,
        RuntimeTaskRecord::class, RuntimeActionRecord::class, ProviderModelRecord::class, ProviderUsageRecord::class],
    version = 4, exportSchema = false,
)
abstract class SessionStore : RoomDatabase() {
    abstract fun checks(): CheckDao
    abstract fun projects(): ProjectDao
    abstract fun conversations(): ConversationDao
    abstract fun changes(): ChangeDao
    abstract fun builds(): BuildDao
    abstract fun runtime(): RuntimeDao
    abstract fun providerUsage(): ProviderUsageDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) { MIGRATION_3_4_SQL.forEach(db::execSQL) }
        }
        val MIGRATION_3_4_SQL = listOf(
            "ALTER TABLE `build_runs` ADD COLUMN `artifactState` TEXT NOT NULL DEFAULT 'PENDING'",
            "ALTER TABLE `build_runs` ADD COLUMN `agentTaskId` TEXT",
            "CREATE TABLE IF NOT EXISTS `runtime_tasks` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `providerId` TEXT NOT NULL, `backend` TEXT NOT NULL, `prompt` TEXT NOT NULL, `status` TEXT NOT NULL, `detail` TEXT NOT NULL, `recoveryAction` TEXT, `transcript` TEXT NOT NULL, `completedSteps` INTEGER NOT NULL, `nextStep` TEXT NOT NULL, `historySize` INTEGER NOT NULL, `changeSetId` TEXT, `autoApproveEdits` INTEGER NOT NULL, `activeSlot` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `providerSelection` TEXT, PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_tasks_activeSlot` ON `runtime_tasks` (`activeSlot`)",
            "CREATE TABLE IF NOT EXISTS `runtime_actions` (`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, `toolCallId` TEXT NOT NULL, `tool` TEXT NOT NULL, `arguments` TEXT NOT NULL, `summary` TEXT NOT NULL, `preview` TEXT NOT NULL, `category` TEXT, `buildId` TEXT, `status` TEXT NOT NULL, `decision` TEXT, `decidedAt` INTEGER, `outcome` TEXT, `resultText` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `allEdits` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_actions_taskId_toolCallId` ON `runtime_actions` (`taskId`, `toolCallId`)",
            "CREATE TABLE IF NOT EXISTS `provider_models` (`providerId` TEXT NOT NULL, `modelId` TEXT NOT NULL, `toolCallingVerified` INTEGER NOT NULL, `verifiedAt` INTEGER NOT NULL, PRIMARY KEY(`providerId`, `modelId`))",
            "CREATE TABLE IF NOT EXISTS `provider_usage` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `providerId` TEXT NOT NULL, `modelId` TEXT NOT NULL, `taskId` TEXT, `inputTokens` INTEGER, `outputTokens` INTEGER, `requests` INTEGER NOT NULL, `recordedAt` INTEGER NOT NULL)",
        )
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `build_runs` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, " +
                    "`tasks` TEXT NOT NULL, `status` TEXT NOT NULL, `detail` TEXT NOT NULL, `snapshotHash` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

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
