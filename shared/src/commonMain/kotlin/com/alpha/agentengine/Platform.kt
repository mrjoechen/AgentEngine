package com.alpha.agentengine

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform