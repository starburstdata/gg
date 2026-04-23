package com.jjuicy.intellij.data

import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

private val LOG = logger<GGHttpClient>()

val GG_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/**
 * Lightweight HTTP client for the gg web API.
 *
 * Uses java.net.HttpURLConnection (already used in the existing plugin) so
 * there are no extra runtime dependencies.
 */
class GGHttpClient(val baseUrl: String) {

    /** Send a POST to /api/query/{name} with the given request body and decode the response. */
    fun <TReq, TRes> query(
        name: String,
        requestBody: TReq,
        reqSerializer: SerializationStrategy<TReq>,
        resSerializer: DeserializationStrategy<TRes>,
    ): TRes {
        val bodyJson = GG_JSON.encodeToString(reqSerializer, requestBody)
        val responseJson = post("$baseUrl/api/query/$name", bodyJson)
        return GG_JSON.decodeFromString(resSerializer, responseJson)
    }

    /** Convenience overload using reified types. */
    inline fun <reified TReq, reified TRes> query(name: String, requestBody: TReq): TRes {
        return query(name, requestBody, serializer(), serializer())
    }

    /**
     * Send a POST to /api/mutate/{command} with the wrapped mutation body.
     *
     * Request format: {"mutation": <mutationBody>, "options": <options>}
     */
    fun <T> mutate(
        command: String,
        mutation: T,
        mutationSerializer: SerializationStrategy<T>,
        options: MutationOptions = MutationOptions(),
    ): MutationResult {
        val mutationJson = GG_JSON.encodeToString(mutationSerializer, mutation)
        val optionsJson = GG_JSON.encodeToString(MutationOptions.serializer(), options)
        val bodyJson = """{"mutation":$mutationJson,"options":$optionsJson}"""
        val responseJson = post("$baseUrl/api/mutate/$command", bodyJson)
        return GG_JSON.decodeFromString(MutationResult.serializer(), responseJson)
    }

    inline fun <reified T> mutate(
        command: String,
        mutation: T,
        options: MutationOptions = MutationOptions(),
    ): MutationResult {
        return mutate(command, mutation, serializer(), options)
    }

    /** Fire-and-forget POST to /api/trigger/{name}. */
    fun trigger(name: String, bodyJson: String = "{}") {
        try {
            post("$baseUrl/api/trigger/$name", bodyJson)
        } catch (e: Exception) {
            LOG.warn("trigger $name failed", e)
        }
    }

    /**
     * Start a Server-Sent Events listener on a daemon thread.
     *
     * Calls [onEvent] with the raw data string for each `data:` line received.
     * The thread exits on IOException (e.g. server shutdown) or when [onStop] is invoked.
     *
     * Returns the daemon thread so the caller can interrupt it on dispose.
     */
    fun streamEvents(onEvent: (String) -> Unit): Thread {
        val t = Thread({
            try {
                val conn = URI.create("$baseUrl/api/events").toURL().openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.setRequestProperty("Cache-Control", "no-cache")
                conn.connect()
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.startsWith("data:")) {
                        onEvent(l.removePrefix("data:").trim())
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                LOG.debug("SSE stream ended: ${e.message}")
            }
        }, "gg-sse-reader")
        t.isDaemon = true
        t.start()
        return t
    }

    private fun post(url: String, body: String): String {
        LOG.debug("POST $url")
        val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true
        conn.doInput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            throw RuntimeException("HTTP $code from $url: $err")
        }

        return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
    }
}
