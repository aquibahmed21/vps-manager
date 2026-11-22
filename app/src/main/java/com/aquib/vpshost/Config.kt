package com.aquib.vpshost

object Config {
    const val EXPRESS_OWNER = "aquibahmed21"
    const val EXPRESS_REPO = "express"
    const val EXPRESS_BRANCH = "main"
    const val EXPRESS_ZIP = "https://codeload.github.com/$EXPRESS_OWNER/$EXPRESS_REPO/zip/refs/heads/$EXPRESS_BRANCH"
    const val EXPRESS_SHA_API = "https://api.github.com/repos/$EXPRESS_OWNER/$EXPRESS_REPO/commits/$EXPRESS_BRANCH"

    const val POINTER_OWNER = "aquibahmed21"
    const val POINTER_REPO = "server"
    const val POINTER_BRANCH = "main"
    const val POINTER_FILE = "server-url.json"
    const val POINTER_PAGES_URL = "https://aquibahmed21.github.io/server/server-url.json"

    const val SERVER_PORT = 3000
    const val LLM_PORT = 8080
    /** Loopback port of the remote-management API; the Node gateway forwards /manage here. */
    const val MANAGE_PORT = 3001
}
