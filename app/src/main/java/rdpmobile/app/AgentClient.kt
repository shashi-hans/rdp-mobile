package rdpmobile.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AgentStatus(
    val hostname: String,
    val locked: Boolean?,
    val keepAwake: Boolean,
    val batteryPct: Int?,
    val charging: Boolean,
    val rdpListening: Boolean,
)

/** [popup] is true when the laptop itself showed the note as a desktop pop-up. */
data class LaptopNote(
    val id: Long,
    val app: String,
    val title: String,
    val body: String,
    val timeSec: Long,
    val popup: Boolean,
)

data class LaptopNotes(val epoch: String, val latest: Long, val items: List<LaptopNote>)

class AgentException(message: String) : IOException(message)

/**
 * Talks to rdpagent on the laptop over plain HTTP inside the Tailscale tunnel.
 * The request goes to a single-use 127.0.0.1 port that the embedded Tailscale
 * node relays to the laptop. The configured host must be a 100.64.0.0/10 literal,
 * so the bearer token only ever travels over the tailnet.
 */
class AgentClient(private val config: LaptopConfig) {

    suspend fun status(): AgentStatus {
        val j = request("GET", "/v1/status", null)
        return AgentStatus(
            hostname = j.optString("hostname"),
            locked = if (j.isNull("locked")) null else j.getBoolean("locked"),
            keepAwake = j.optBoolean("keep_awake"),
            batteryPct = if (j.isNull("battery_pct")) null else j.getInt("battery_pct"),
            charging = j.optBoolean("charging"),
            rdpListening = j.optBoolean("rdp_listening"),
        )
    }

    suspend fun lock() {
        request("POST", "/v1/lock", null)
    }

    suspend fun setKeepAwake(on: Boolean): Boolean =
        request("PUT", "/v1/keep-awake", JSONObject().put("enabled", on)).optBoolean("keep_awake")

    /**
     * Notifications with id > [after], waiting up to [waitSec] on the laptop
     * for one to arrive. after = -1 returns only the current cursor.
     */
    suspend fun notifications(after: Long, waitSec: Int): LaptopNotes {
        val j = request(
            "GET", "/v1/notifications?after=$after&wait=$waitSec", null,
            readTimeoutMs = (waitSec + 15) * 1000,
        )
        val arr = j.optJSONArray("items")
        val items = (0 until (arr?.length() ?: 0)).map { i ->
            val o = arr!!.getJSONObject(i)
            LaptopNote(
                id = o.getLong("id"),
                app = o.optString("app"),
                title = o.optString("title"),
                body = o.optString("body"),
                timeSec = o.optLong("time"),
                popup = o.optBoolean("popup"),
            )
        }
        return LaptopNotes(j.optString("epoch"), j.optLong("latest"), items)
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject?,
        readTimeoutMs: Int = 6000,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val host = config.host.trim()
            if (!Tailnet.isTailnetLiteral(host)) {
                throw AgentException("Laptop address must be a Tailscale IP (100.x.y.z)")
            }
            if (Tailnet.status.value != TailnetStatus.Running) {
                throw AgentException("Tailscale is not connected")
            }
            // One connection, used at once: the relay port closes right after.
            val port = Tailnet.localPort(host, config.agentPort, maxConns = 1, windowSec = 10)
            val url = URL("http", "127.0.0.1", port, path)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.setRequestProperty("Connection", "close")
                conn.connectTimeout = 4000
                conn.readTimeout = readTimeoutMs
                conn.useCaches = false
                conn.setRequestProperty("Authorization", "Bearer ${config.agentToken}")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code < 400) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                when {
                    code == 401 -> throw AgentException("Agent rejected the token")
                    code == 429 -> throw AgentException("Too many bad tokens; wait a minute")
                    code >= 400 -> throw AgentException(
                        runCatching { JSONObject(text).optString("error") }.getOrNull()
                            ?.ifBlank { null } ?: "Agent error $code",
                    )
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } finally {
                conn.disconnect()
            }
        }
}
