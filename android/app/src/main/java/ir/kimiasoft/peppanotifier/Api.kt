package ir.kimiasoft.peppanotifier

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Api {
    /** Single edit point for the deployment URL. HTTP only — see PLAN.md. */
    const val BASE_URL = "http://chat.kimiasoft.ir"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    class ApiException(val status: Int, val errorBody: String?, message: String) : IOException(message)

    private fun url(action: String) = "$BASE_URL/api.php?action=$action"

    @Throws(IOException::class)
    private fun call(ctx: Context, action: String, post: JSONObject?): String? {
        val builder = Request.Builder().url(url(action))
        val tok = Prefs.token(ctx)
        if (tok != null) builder.header("Authorization", "Bearer $tok")
        if (post != null) {
            builder.post(post.toString().toRequestBody(JSON))
        } else {
            builder.get()
        }
        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 204) return null
            val body = resp.body?.string()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, body, "HTTP ${resp.code}")
            }
            return body
        }
    }

    data class LoginResult(val token: String, val user: String)

    @Throws(IOException::class)
    fun login(ctx: Context, username: String, password: String): LoginResult {
        val body = JSONObject().put("username", username).put("password", password)
        val resp = call(ctx, "login", body) ?: throw IOException("empty response")
        val o = JSONObject(resp)
        return LoginResult(o.getString("token"), o.getString("user"))
    }

    @Throws(IOException::class)
    fun logout(ctx: Context) {
        try { call(ctx, "logout", JSONObject()) } catch (_: IOException) { /* best effort */ }
    }

    data class PollResult(
        val newMessages: List<Message>,
        val sentUpdates: List<Message>,
        val unreadCount: Int,
        val serverTime: Long,
        val myMutedUntil: Long,
        val mutes: Map<String, Long>,
    )

    @Throws(IOException::class)
    fun poll(ctx: Context): PollResult {
        val resp = call(ctx, "poll", null) ?: throw IOException("empty response")
        val o = JSONObject(resp)
        val mutes = HashMap<String, Long>()
        o.optJSONObject("mutes")?.let { obj ->
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                mutes[k] = obj.optLong(k, 0)
            }
        }
        return PollResult(
            newMessages = parseMessages(o.optJSONArray("new_messages")),
            sentUpdates = parseMessages(o.optJSONArray("sent_updates")),
            unreadCount = o.optInt("unread_count", 0),
            serverTime = o.optLong("server_time", System.currentTimeMillis() / 1000),
            myMutedUntil = o.optLong("my_muted_until", 0),
            mutes = mutes,
        )
    }

    @Throws(IOException::class)
    fun mute(ctx: Context, until: Long): Long {
        val resp = call(ctx, "mute", JSONObject().put("until", until))
            ?: throw IOException("empty response")
        return JSONObject(resp).optLong("muted_until", 0)
    }

    @Throws(IOException::class)
    fun history(ctx: Context): List<Message> {
        val resp = call(ctx, "history", null) ?: return emptyList()
        return parseMessages(JSONArray(resp))
    }

    @Throws(IOException::class)
    fun users(ctx: Context): List<UserInfo> {
        val resp = call(ctx, "users", null) ?: return emptyList()
        val arr = JSONArray(resp)
        val out = ArrayList<UserInfo>(arr.length())
        for (i in 0 until arr.length()) out.add(UserInfo.fromJson(arr.getJSONObject(i)))
        return out
    }

    data class SendResult(val ids: List<String>, val messages: List<Message>)

    @Throws(IOException::class)
    fun send(ctx: Context, recipient: String, text: String): SendResult {
        val resp = call(ctx, "send", JSONObject().put("recipient", recipient).put("text", text))
            ?: return SendResult(emptyList(), emptyList())
        val o = JSONObject(resp)
        val idsArr = o.optJSONArray("ids")
        val ids = if (idsArr == null) emptyList() else (0 until idsArr.length()).map { idsArr.getString(it) }
        val messages = parseMessages(o.optJSONArray("messages"))
        return SendResult(ids, messages)
    }

    @Throws(IOException::class)
    fun ackOpened(ctx: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        call(ctx, "ack_opened", JSONObject().put("ids", arr))
    }

    private fun parseMessages(arr: JSONArray?): List<Message> {
        if (arr == null) return emptyList()
        val out = ArrayList<Message>(arr.length())
        for (i in 0 until arr.length()) out.add(Message.fromJson(arr.getJSONObject(i)))
        return out
    }
}
