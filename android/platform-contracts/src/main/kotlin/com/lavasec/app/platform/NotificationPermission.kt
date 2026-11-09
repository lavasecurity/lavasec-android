package com.lavasec.app.platform

enum class NotificationPermissionResult {
    Granted,
    Denied,
    NotRequired,
}

fun interface NotificationPermission {
    suspend fun request(): NotificationPermissionResult
}
