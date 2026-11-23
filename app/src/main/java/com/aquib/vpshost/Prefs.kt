package com.aquib.vpshost

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("vps_host", Context.MODE_PRIVATE)
    /** GitHub fine-grained token with Contents (+ Pages) write on the pointer repo. */
    var githubToken: String get() = p.getString("token", "") ?: ""; set(v) = p.edit().putString("token", v.trim()).apply()
    /** True once the user has pressed Start; stays true across restarts/reboots until Stop is pressed. */
    var wantRunning: Boolean get() = p.getBoolean("want", false); set(v) = p.edit().putBoolean("want", v).apply()
    var repoDate: String get() = p.getString("sha_date", "") ?: ""; set(v) = p.edit().putString("sha_date", v).apply()
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

    // ── VoIP user roles ───────────────────────────────────────────────────────
    /** Phone number granted root role in MeshCall (can manage all users). */
    var rootPhone: String get() = p.getString("root_phone", "") ?: ""; set(v) = p.edit().putString("root_phone", v.trim()).apply()
    /** Password for the root account — applied on every server start. Blank = keep existing. */
    var rootPassword: String get() = p.getString("root_password", "") ?: ""; set(v) = p.edit().putString("root_password", v).apply()
    /** Comma-separated phone numbers granted admin role in MeshCall. */
    var adminPhones: String get() = p.getString("admin_phones", "") ?: ""; set(v) = p.edit().putString("admin_phones", v.trim()).apply()
    /** phone:password pairs for admin accounts, comma-separated. Blank = keep existing passwords. */
    var adminPasswords: String get() = p.getString("admin_passwords", "") ?: ""; set(v) = p.edit().putString("admin_passwords", v).apply()

    // ── TURN / STUN ───────────────────────────────────────────────────────────
    /** e.g. stun:stun.example.com:3478 — leave blank to use Google STUN only. */
    var stunServer: String get() = p.getString("stun_server", "") ?: ""; set(v) = p.edit().putString("stun_server", v.trim()).apply()
    /** e.g. turn:turn.example.com:3478 */
    var turnServer: String get() = p.getString("turn_server", "") ?: ""; set(v) = p.edit().putString("turn_server", v.trim()).apply()
    var turnUsername: String get() = p.getString("turn_user", "") ?: ""; set(v) = p.edit().putString("turn_user", v.trim()).apply()
    var turnCredential: String get() = p.getString("turn_cred", "") ?: ""; set(v) = p.edit().putString("turn_cred", v.trim()).apply()

    // ── Family module ─────────────────────────────────────────────────────────
    /** Master PIN that unlocks the family database. */
    var rootPin: String get() = p.getString("root_pin", "") ?: ""; set(v) = p.edit().putString("root_pin", v.trim()).apply()

    companion object { fun newKey() = "sk-" + java.util.UUID.randomUUID().toString().replace("-", "") }
}
