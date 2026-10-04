package dev.srimi.antigravitymobile.runtime

object BuildProtocol {
    const val MAIN = "dev.srimi.antigravitymobile.probe"
    const val WORKER = "dev.srimi.antigravitymobile.worker"
    const val SERVICE = "$WORKER.BuildWorkerService"
    const val PREVIEW_ACTIVITY = "$WORKER.WebPreviewActivity"
    const val MIN_WORKER_VERSION = 3L
    const val PERMISSION = "dev.srimi.antigravitymobile.permission.BUILD_WORKER"
    const val HELLO = 1
    const val START = 2
    const val QUERY = 3
    const val CANCEL = 4
    const val ARTIFACT = 5
    const val PREVIEW = 6
    const val REPLY = 100
    val terminal = setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED")
    fun validateId(id: String) {
        require(id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) { "Invalid build ID" }
    }
    fun validateTasks(tasks: List<String>) {
        require(tasks.isNotEmpty() && tasks.size <= 10) { "Choose one to ten Gradle tasks" }
        require(tasks.all { it.matches(Regex("[A-Za-z:][A-Za-z0-9:_.-]{0,159}")) }) { "Enter Gradle task names, not command options" }
    }
}
