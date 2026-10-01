package dev.srimi.antigravitymobile

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GitHubUser(val login: String, val name: String)
data class GitHubRepo(val fullName: String, val cloneUrl: String, val defaultBranch: String, val private: Boolean,
                      val description: String, val updatedAt: String)
data class GitHubPull(val number: Int, val url: String)

/**
 * GitHub REST API with the user's personal access token (saved in Keystore-encrypted Git credentials).
 * Talks only to api.github.com; error messages never include the token.
 */
class GitHubService(private val token: () -> String?) {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun request(path: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null, tokenOverride: String? = null,
                        anonymous: Boolean = false): String {
        val key = tokenOverride ?: token()?.takeIf { it.isNotBlank() } ?: if (anonymous) null else error("Sign in to GitHub in Accounts first")
        val url = HttpUrl.Builder().scheme("https").host("api.github.com").addPathSegments(path.trimStart('/'))
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val builder = Request.Builder().url(url).apply { if (key != null) header("Authorization", "Bearer $key") }
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(GitHubWire.describe(response.code, text))
            return text
        }
    }

    fun verify(token: String): GitHubUser = GitHubWire.user(JSONObject(request("user", tokenOverride = token.trim())))
    fun repos(page: Int = 1): List<GitHubRepo> = GitHubWire.repos(JSONArray(request("user/repos",
        mapOf("per_page" to "100", "page" to page.toString(), "sort" to "updated", "affiliation" to "owner,collaborator,organization_member"))))
    fun search(text: String): List<GitHubRepo> = GitHubWire.repos(JSONObject(request("search/repositories",
        mapOf("q" to "$text in:name fork:true", "per_page" to "50"), anonymous = true)).optJSONArray("items") ?: JSONArray())
    fun createRepository(name: String, private: Boolean, description: String): GitHubRepo = GitHubWire.repo(JSONObject(request("user/repos",
        body = JSONObject().put("name", name).put("private", private).put("description", description).put("auto_init", false))))
    fun repository(fullName: String): GitHubRepo = GitHubWire.repo(JSONObject(request("repos/$fullName")))
    fun createPullRequest(fullName: String, head: String, base: String, title: String, body: String): GitHubPull =
        JSONObject(request("repos/$fullName/pulls", body = JSONObject().put("title", title).put("head", head).put("base", base).put("body", body)))
            .let { GitHubPull(it.getInt("number"), it.getString("html_url")) }
}

object GitHubWire {
    fun user(json: JSONObject) = GitHubUser(json.getString("login"), json.optString("name").takeIf { it != "null" }.orEmpty())
    fun repo(json: JSONObject) = GitHubRepo(json.getString("full_name"), json.getString("clone_url"), json.optString("default_branch", "main"),
        json.optBoolean("private"), json.optString("description").takeIf { it != "null" }.orEmpty(), json.optString("updated_at"))
    fun repos(array: JSONArray): List<GitHubRepo> = (0 until array.length()).map { repo(array.getJSONObject(it)) }

    /** "owner/name" for github.com HTTPS remotes, otherwise null. */
    fun fullName(remote: String?): String? {
        val match = Regex("^https://(?:[^@/]+@)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$").find(remote?.trim().orEmpty()) ?: return null
        return "${match.groupValues[1]}/${match.groupValues[2]}"
    }

    fun describe(status: Int, body: String): String {
        val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
            .replace(Regex("gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}"), "[token]").take(200)
        val errors = runCatching { JSONObject(body).optJSONArray("errors") }.getOrNull()
        val detail = (0 until (errors?.length() ?: 0)).mapNotNull { errors?.optJSONObject(it)?.optString("message")?.takeIf(String::isNotBlank) }
            .joinToString("; ").take(200)
        val hint = when (status) {
            401 -> " The token is invalid or expired; create a new one in Accounts."
            403 -> " The token lacks permission (needs repo access) or the rate limit was reached."
            404 -> " Not found, or the token cannot see this repository."
            422 -> ""
            else -> ""
        }
        return "GitHub replied HTTP $status" + (if (message.isNotBlank()) ": $message" else "") + (if (detail.isNotBlank()) " ($detail)" else "") + "." + hint
    }

    /** Opens github.com's token page with the scopes this app needs pre-selected. */
    const val TOKEN_PAGE = "https://github.com/settings/tokens/new?scopes=repo,workflow&description=Antigravity%20Mobile"
}
