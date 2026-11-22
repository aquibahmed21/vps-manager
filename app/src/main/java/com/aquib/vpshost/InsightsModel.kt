package com.aquib.vpshost

import org.json.JSONArray
import org.json.JSONObject

/** Something that happened, written for a person ("The server was restarted on request"). kind: restart, update, tunnel, network, llm, battery, remote, info, problem. */
data class Event(val t: Long, val kind: String, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("t", t).put("kind", kind).put("text", text)
    companion object { fun fromJson(o: JSONObject) = Event(o.getLong("t"), o.optString("kind", "info"), o.optString("text")) }
}

/** Measurements for one time slot (counts are totals for the slot, flags are the fraction of time they were true). */
data class Bucket(
    val t: Long, val secs: Int, val battery: Int, val charging: Boolean, val tempC: Double, val memPct: Int, val storPct: Int,
    val reqs: Int, val ai: Int, val manage: Int, val other: Int, val bytesIn: Long, val bytesOut: Long, val rtMs: Int,
    val nodeUp: Double, val tunnelUp: Double, val online: Double, val llmReady: Double, val denied: Int, val errors: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("t", t).put("secs", secs).put("battery", battery).put("charging", charging).put("tempC", tempC)
        .put("memPct", memPct).put("storPct", storPct).put("reqs", reqs).put("ai", ai).put("manage", manage).put("other", other)
        .put("bytesIn", bytesIn).put("bytesOut", bytesOut).put("rtMs", rtMs).put("nodeUp", nodeUp).put("tunnelUp", tunnelUp)
        .put("online", online).put("llmReady", llmReady).put("denied", denied).put("errors", errors)
    companion object {
        fun fromJson(o: JSONObject) = Bucket(o.getLong("t"), o.optInt("secs", 30), o.optInt("battery", -1), o.optBoolean("charging"), o.optDouble("tempC", 0.0),
            o.optInt("memPct"), o.optInt("storPct"), o.optInt("reqs"), o.optInt("ai"), o.optInt("manage"), o.optInt("other"),
            o.optLong("bytesIn"), o.optLong("bytesOut"), o.optInt("rtMs"), o.optDouble("nodeUp"), o.optDouble("tunnelUp"), o.optDouble("online"),
            o.optDouble("llmReady"), o.optInt("denied"), o.optInt("errors"))
    }
}

data class Insights(val hours: Int, val buckets: List<Bucket>, val events: List<Event>) {
    fun toJson(): JSONObject = JSONObject().put("hours", hours)
        .put("buckets", JSONArray().also { a -> buckets.forEach { a.put(it.toJson()) } })
        .put("events", JSONArray().also { a -> events.forEach { a.put(it.toJson()) } })
    companion object {
        fun fromJson(j: JSONObject): Insights {
            val b = j.getJSONArray("buckets"); val e = j.getJSONArray("events")
            return Insights(j.optInt("hours", 24), (0 until b.length()).map { Bucket.fromJson(b.getJSONObject(it)) }, (0 until e.length()).map { Event.fromJson(e.getJSONObject(it)) })
        }
    }
}
