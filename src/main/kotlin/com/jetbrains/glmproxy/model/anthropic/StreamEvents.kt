package com.jetbrains.glmproxy.model.anthropic

data class SseEvent(
    val type: String,
    val data: String,
)
