package com.aquib.vpshost

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("vps_host", Context.MODE_PRIVATE)
    /** GitHub fine-grained token with Contents (+ Pages) write on the pointer repo. */
    var githubToken: String get() = p.getString("token", "") ?: ""; set(v) = p.edit().putString("token", v.trim()).apply()
    /** True once the user has pressed Start; stays true across restarts/reboots until Stop is pressed. */
    var wantRunning: Boolean get() = p.getBoolean("want", false); set(v) = p.edit().putBoolean("want", v).apply()
    var repoSha: String get() = p.getString("sha", "") ?: ""; set(v) = p.edit().putString("sha", v).apply()
    var lastPublishedUrl: String get() = p.getString("pub", "") ?: ""; set(v) = p.edit().putString("pub", v).apply()

    /** Secret that unlocks the remote /manage API (files, control). Generated on first use. */
    var adminToken: String
        get() {
            var k = p.getString("admin_token", "") ?: ""
            if (k.isEmpty()) { k = "adm-" + ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }; p.edit().putString("admin_token", k).apply() }
            return k
        }
        set(v) = p.edit().putString("admin_token", v).apply()
    var batteryAlarm: Boolean get() = p.getBoolean("batt_alarm", true); set(v) = p.edit().putBoolean("batt_alarm", v).apply()
    /** True while the user wants the LLM running (set by Start LLM, cleared by Stop LLM). */
    var llmWant: Boolean get() = p.getBoolean("llm_want", false); set(v) = p.edit().putBoolean("llm_want", v).apply()
    var llmModel: String get() = p.getString("llm_model", "") ?: ""; set(v) = p.edit().putString("llm_model", v).apply()
    var llmCtx: Int get() = p.getInt("llm_ctx", 4096); set(v) = p.edit().putInt("llm_ctx", v).apply()
    var llmThreads: Int get() = p.getInt("llm_threads", 4); set(v) = p.edit().putInt("llm_threads", v).apply()
    var llmRequireKey: Boolean get() = p.getBoolean("llm_reqkey", false); set(v) = p.edit().putBoolean("llm_reqkey", v).apply()
    /** Bearer key that protects the /v1 API on the public tunnel. Generated on first use. */
    var llmKey: String
        get() {
            var k = p.getString("llm_key", "") ?: ""
            if (k.isEmpty()) { k = newKey(); p.edit().putString("llm_key", k).apply() }
            return k
        }
        set(v) = p.edit().putString("llm_key", v).apply()

    companion object { fun newKey() = "sk-" + java.util.UUID.randomUUID().toString().replace("-", "") }
}
