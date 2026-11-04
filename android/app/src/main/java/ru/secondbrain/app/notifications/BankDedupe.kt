package ru.secondbrain.app.notifications

import java.security.MessageDigest

/**
 * Local dedupe key for a captured bank notification. Built from the system notification key
 * (user|package|id|tag|uid) and the exact post time, so a repost of the same notification is dropped
 * while two identical purchases in the same minute are kept apart (the server dedupes by text+minute
 * on its own). Pure function: unit-tested on the JVM.
 */
fun bankDedupeKey(packageName: String, sbnKey: String?, postTime: Long, text: String): String =
    sha256("$packageName|${sbnKey ?: ""}|$postTime|$text")

internal fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    .joinToString("") { "%02x".format(it) }
