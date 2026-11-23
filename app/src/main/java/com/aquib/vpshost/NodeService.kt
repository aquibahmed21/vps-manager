package com.aquib.vpshost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Process
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

private interface LibNode : Library {
    /** int node::Start(int argc, char** argv) */
    fun _ZN4node5StartEiPPc(argc: Int, argv: Pointer): Int
}
private interface LibC : Library { fun setenv(name: String, value: String, overwrite: Int): Int }

/**
 * Runs the Express server inside embedded Node (nodejs-mobile libnode.so) in the ":node" process.
 * node::Start may only be called once per process, so "restart" means killing this process and letting it respawn.
 */
class NodeService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // stopSelf first so the system treats this as a deliberate stop (no sticky respawn), then end the process.
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            android.os.Handler(mainLooper).postDelayed({ Process.killProcess(Process.myPid()) }, 400)
            return START_NOT_STICKY
        }
        startForeground(2, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!started) { started = true; Thread(null, { runNode() }, "node-main", 8L * 1024 * 1024).start() }
        return START_STICKY // if the process dies it is brought back and node starts again
    }

    private fun runNode() {
        val repo = RepoManager(this)
        try {
            repo.baseDir.mkdirs()
            System.loadLibrary("c++_shared")
            val libc = Native.load("c", LibC::class.java)
            libc.setenv("HOME", filesDir.path, 1)
            libc.setenv("TMPDIR", cacheDir.path, 1)
            libc.setenv("PORT", Config.SERVER_PORT.toString(), 1)
            libc.setenv("MANAGE_PORT", Config.MANAGE_PORT.toString(), 1)
            // Family data lives beside the repo, not inside it, so "Update server" never wipes it.
            libc.setenv("FAMILY_DIR", java.io.File(repo.baseDir, "family").path, 1)
            // Credentials and roles configured from the Settings screen.
            val prefs = Prefs(this)
            if (prefs.rootPhone.isNotBlank())       libc.setenv("ROOT_PHONE",       prefs.rootPhone,       1)
            if (prefs.rootPassword.isNotBlank())  libc.setenv("ROOT_PASSWORD",    prefs.rootPassword,    1)
            if (prefs.adminPhones.isNotBlank())   libc.setenv("ADMIN_PHONES",     prefs.adminPhones,     1)
            if (prefs.adminPasswords.isNotBlank()) libc.setenv("ADMIN_PASSWORDS", prefs.adminPasswords,  1)
            if (prefs.stunServer.isNotBlank())    libc.setenv("STUN_SERVER",     prefs.stunServer,     1)
            if (prefs.turnServer.isNotBlank())    libc.setenv("TURN_SERVER",     prefs.turnServer,     1)
            if (prefs.turnUsername.isNotBlank())  libc.setenv("TURN_USERNAME",   prefs.turnUsername,   1)
            if (prefs.turnCredential.isNotBlank()) libc.setenv("TURN_CREDENTIAL", prefs.turnCredential, 1)
            if (prefs.rootPin.isNotBlank())       libc.setenv("ROOT_PIN",        prefs.rootPin,        1)
            val node = Native.load("node", LibNode::class.java)

            // node (libuv) requires argv strings in ONE contiguous block.
            val args = listOf("node", repo.bootstrap.path, repo.repoDir.path, repo.modulesDir.path, repo.logFile.path, java.io.File(filesDir, "llm.json").path)
            val bytes = args.map { it.toByteArray() }
            val block = Memory(bytes.sumOf { it.size + 1 }.toLong())
            val ptrs = Memory(8L * (args.size + 1))
            var off = 0L
            bytes.forEachIndexed { i, b ->
                block.write(off, b, 0, b.size); block.setByte(off + b.size, 0)
                ptrs.setPointer(8L * i, block.share(off)); off += b.size + 1
            }
            ptrs.setPointer(8L * args.size, Pointer.NULL)
            val code = node._ZN4node5StartEiPPc(args.size, ptrs)
            repo.logFile.appendText("node exited with code $code\n")
        } catch (t: Throwable) {
            runCatching { repo.logFile.appendText("NodeService failed: $t\n") }
        }
        Process.killProcess(Process.myPid()) // node finished or failed: die so the supervisor can respawn us
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("node", "Node server", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, "node").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Node server").setContentText("Embedded Node.js is running").setOngoing(true).build()
    }

    companion object {
        const val ACTION_STOP = "stop"
        @Volatile private var started = false
        fun start(c: Context) { c.startForegroundService(Intent(c, NodeService::class.java)) }
        fun stop(c: Context) { c.startService(Intent(c, NodeService::class.java).setAction(ACTION_STOP)) }
    }
}
