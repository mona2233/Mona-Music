package com.monamusic.android

import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLDecoder

class WebdavGateway(
    private val plainHttpClient: OkHttpClient,
    private val insecureHttpsClient: OkHttpClient,
    private val buildAuthHeader: (String, String) -> String
) {
    fun propfind(cfg: WebdavLibrary, path: String, depth: String = "1"): List<WebdavItem> {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/></d:prop></d:propfind>"
        val request = Request.Builder()
            .url("${cfg.protocol}://${cfg.host}:${cfg.port}${encodePath(normalized)}")
            .header("Depth", depth)
            .header("Content-Type", "application/xml; charset=utf-8")
            .apply {
                if (cfg.username.isNotBlank()) header("Authorization", buildAuthHeader(cfg.username, cfg.password))
            }
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        val client = if (cfg.protocol == "https") insecureHttpsClient else plainHttpClient
        val response = client.newCall(request).execute()
        val code = response.code
        val text = response.body?.string().orEmpty()
        response.close()
        if (!(code in 200..299 || code == 207)) throw IllegalStateException("WebDAV 请求失败: $code")
        val req = normalized.trimEnd('/')
        return WebdavXmlParser.parseResponses(text).filter { it.path.trimEnd('/') != req }
    }

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { if (it.isEmpty()) "" else Uri.encode(it) }
}

object WebdavXmlParser {
    fun parseResponses(xml: String): List<WebdavItem> {
        val out = mutableListOf<WebdavItem>()
        val responseRegex = Regex("<[^>]*response[^>]*>([\\s\\S]*?)</[^>]*response>", RegexOption.IGNORE_CASE)
        val hrefRegex = Regex("<[^>]*href[^>]*>([\\s\\S]*?)</[^>]*href>", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("<[^>]*displayname[^>]*>([\\s\\S]*?)</[^>]*displayname>", RegexOption.IGNORE_CASE)
        val collectionRegex = Regex("<[^>]*collection\\s*/?>", RegexOption.IGNORE_CASE)
        for (match in responseRegex.findAll(xml)) {
            val block = match.value
            val href = hrefRegex.find(block)?.groupValues?.getOrNull(1)?.trim() ?: continue
            val path = extractPathFromHref(decodeXmlEntities(href))
            val name = nameRegex.find(block)?.groupValues?.getOrNull(1)?.trim()?.let(::decodeXmlEntities)
                ?: path.substringAfterLast('/').ifBlank { "/" }
            val isCollection = collectionRegex.containsMatchIn(block) || path.endsWith("/")
            out.add(WebdavItem(path, isCollection, name))
        }
        return out
    }

    private fun decodeXmlEntities(v: String): String =
        v.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    private fun extractPathFromHref(href: String): String = runCatching {
        val uri = URI(href)
        URLDecoder.decode(uri.path ?: href, "UTF-8")
    }.getOrElse { href }
}
