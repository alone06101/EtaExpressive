package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * WebDAV 客户端：只做备份需要的几件事——测连通、建目录、上传、下载、列目录。
 *
 * 认证只走 Basic（WebDAV 的通用做法），凭据放 Authorization 头，永远不拼进 URL：
 * URL 会被日志、异常栈与界面回显带走，头不会。
 *
 * 备份包动辄几十兆，超时按大文件给：连接 20s、读写 10 分钟。不跟随重定向，
 * 避免把 Authorization 头带到别的主机。
 */
internal class WebDavClient(
    serverUrl: String,
    username: String,
    password: String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val base = serverUrl.trim().trimEnd('/')
    private val authorization = Credentials.basic(username, password, Charsets.UTF_8)

    /** 服务器地址是否可接受（只认 http/https 且能解析）。 */
    fun validBase(): Boolean = base.toHttpUrlOrNull() != null

    /** 测连通：对目标目录发 PROPFIND；401/403 由 execute 转成「账号或密码不正确」。 */
    fun probe(directory: String) {
        val url = requireNotNull(urlOf(directory))
        execute(Request.Builder().url(url).method("PROPFIND", emptyPropfind()).header("Depth", "0").build()).close()
    }

    /** 逐级补建远端目录；已存在（405）视为成功。 */
    fun ensureDirectory(directory: String) {
        var path = ""
        for (segment in directory.split('/').filter { it.isNotBlank() }) {
            path = if (path.isEmpty()) segment else "$path/$segment"
            val url = requireNotNull(urlOf(path))
            val response = execute(Request.Builder().url(url).method("MKCOL", EMPTY_BODY).build())
            val code = response.code
            response.close()
            if (code !in 200..299 && code != 405) throw WebDavException("创建目录失败（HTTP $code）")
        }
    }

    /** 流式上传，避免把整个备份包读进内存。 */
    fun upload(directory: String, fileName: String, file: File) {
        val url = requireNotNull(urlOf(joinPath(directory, fileName)))
        val body = file.asRequestBody(ZIP_MEDIA_TYPE)
        execute(Request.Builder().url(url).put(body).build()).close()
    }

    /** 下载到本地文件；调用方负责清理。 */
    fun download(directory: String, fileName: String, target: File) {
        val url = requireNotNull(urlOf(joinPath(directory, fileName)))
        execute(Request.Builder().url(url).get().build()).use { response ->
            val body = response.body ?: throw WebDavException("服务器没有返回内容")
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    /** 列出远端 .zip 备份包；文件名带时间戳，倒序即最新在前。 */
    fun list(directory: String): List<WebDavBackupEntry> {
        val url = requireNotNull(urlOf(directory))
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", emptyPropfind())
            .header("Depth", "1")
            .build()
        val xml = execute(request).use { response -> response.body?.string().orEmpty() }
        return parseEntries(xml)
    }

    private fun execute(request: Request): okhttp3.Response {
        val authorized = request.newBuilder().header("Authorization", authorization).build()
        val response = try {
            client.newCall(authorized).execute()
        } catch (io: IOException) {
            throw WebDavException("连接服务器失败：${io.javaClass.simpleName}")
        }
        if (response.code == 401 || response.code == 403) {
            val code = response.code
            response.close()
            throw WebDavException("账号或密码不正确（HTTP $code）")
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw WebDavException("服务器返回 HTTP $code")
        }
        return response
    }

    private fun joinPath(directory: String, fileName: String): String =
        if (directory.isBlank()) fileName else "$directory/$fileName"

    private fun urlOf(path: String): String? {
        val encoded = path.split('/').filter { it.isNotBlank() }.joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        val full = if (encoded.isEmpty()) base else "$base/$encoded"
        return full.takeIf { it.toHttpUrlOrNull() != null }
    }

    private fun emptyPropfind(): RequestBody = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>
""".trimIndent().toRequestBody(XML_MEDIA_TYPE)

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val ZIP_MEDIA_TYPE = "application/zip".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        fun defaultClient(): OkHttpClient = AgentHttpClient.client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()

        /**
         * 极简 PROPFIND 解析：只取 href 与 getlastmodified。
         *
         * 不引入 XML 解析器：这里只需要「有哪些 zip」。解析不出时返回空表，
         * 由调用方提示「云端没有备份」。每个条目只在自己的 response 块内取时间。
         */
        fun parseEntries(xml: String): List<WebDavBackupEntry> {
            if (xml.isBlank()) return emptyList()
            // 按 <d:response> 块解析，而不是把全表的 href 与 lastmodified 各自拉成列表再按索引配对：
            // 目录条目通常没有 getlastmodified，一旦缺失，后面对齐会整体错位，时间全配错。
            return RESPONSE.findAll(xml).mapNotNull { block ->
                val body = block.value
                val href = HREF.find(body)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                val name = href.trimEnd('/').substringAfterLast('/')
                if (!name.endsWith(".zip", ignoreCase = true)) return@mapNotNull null
                WebDavBackupEntry(
                    fileName = java.net.URLDecoder.decode(name, "UTF-8"),
                    lastModified = LASTMODIFIED.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty(),
                )
            }.distinctBy { it.fileName }.sortedByDescending { it.fileName }.toList()
        }

        private val RESPONSE = Regex("<(?:d:|D:)?response>(.*?)</(?:d:|D:)?response>", RegexOption.DOT_MATCHES_ALL)
        private val HREF = Regex("<(?:d:|D:)?href>([^<]+)</(?:d:|D:)?href>")
        private val LASTMODIFIED = Regex("<(?:d:|D:)?getlastmodified>([^<]+)</(?:d:|D:)?getlastmodified>")
    }
}

/** 远端备份包。 */
internal data class WebDavBackupEntry(
    val fileName: String,
    val lastModified: String,
)

/** WebDAV 操作失败；message 直接面向用户展示。 */
internal class WebDavException(message: String, cause: Throwable? = null) : IOException(message, cause)
