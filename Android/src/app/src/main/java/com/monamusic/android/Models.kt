package com.monamusic.android

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: String,
    val source: String,
    val authHeader: String = ""
)

data class WebdavLibrary(
    val id: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val folderPath: String
)

data class WebdavItem(
    val path: String,
    val isCollection: Boolean,
    val name: String
)
