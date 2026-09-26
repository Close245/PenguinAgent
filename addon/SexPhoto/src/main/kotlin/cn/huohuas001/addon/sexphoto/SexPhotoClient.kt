package cn.huohuas001.addon.sexphoto

import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** 一张作品的信息。字段与 API 的 `data[]` 一一对应。 */
data class SexPhoto(
    val pid: Long,
    val title: String,
    val author: String,
    val authorUid: Long,
    val page: String,
    val imageUrl: String,
    val width: Int,
    val height: Int,
    val tags: List<String>
)

/** 取图结果。 */
sealed interface SexPhotoResult {
    data class Ok(val photos: List<SexPhoto>) : SexPhotoResult
    data class Failed(val message: String) : SexPhotoResult
}

/**
 * SexPhoto API 客户端。
 *
 * 接口文档：https://sex.nyan.run/api.html
 * 只用 `GET /api/v2/`，返回 JSON；图片本身是外链，QQ 服务端自己去拉，插件不中转。
 *
 * ⚠️ 对方明确说了会对滥用做 **IP 封锁**，实测限流阈值很低（连续请求几轮就 429）。
 * 所以调用方必须自己做冷却 —— 本类不做节流，只做「被限流了怎么把话说清楚」。
 */
class SexPhotoClient(
    private val apiBase: String,
    private val timeoutMs: Int
) {
    /**
     * 取图。
     *
     * ⚠️ `r18=false` **不足以**保证拿到全年龄作品：实测该 API 在 r18=false 时仍有
     * 10%~30% 的结果带 `R-18` 标签（服务端的过滤是漏的）。所以这里在客户端再兜一层 ——
     * r18=false 时丢弃带 R-18/R-18G 标签的作品，必要时补一次请求凑数。
     *
     * @param tags    标签，可多个（API 上限 10 个，超出会被截断）
     * @param keyword 关键词，支持标题/作者/标签模糊搜索
     */
    fun fetch(
        r18: Boolean,
        num: Int,
        tags: List<String> = emptyList(),
        keyword: String? = null
    ): SexPhotoResult {
        val wanted = num.coerceIn(MIN_NUM, MAX_NUM)
        val collected = LinkedHashMap<Long, SexPhoto>()
        var filteredOut = 0

        for (attempt in 1..MAX_ATTEMPTS) {
            // 第一次之后稍微等一下：对方按 IP 限流，连着打必吃 429。
            if (attempt > 1) {
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            val response = try {
                request(buildUrl(r18, wanted, tags, keyword))
            } catch (error: IOException) {
                return SexPhotoResult.Failed("连接 SexPhoto API 失败：${error.message}")
            }

            val parsed = parse(response.body)
                ?: if (response.code !in 200..299) {
                    // 连错误信封都解析不出来时，至少把 HTTP 码说清楚。
                    return SexPhotoResult.Failed("SexPhoto API 返回 HTTP ${response.code}")
                } else {
                    return SexPhotoResult.Failed("解析 API 返回失败")
                }

            // 参数错 / 被拦截 / 限流这类问题，重试也没用，直接把原因带回去。
            if (parsed is SexPhotoResult.Failed) return parsed

            val photos = (parsed as SexPhotoResult.Ok).photos
            val usable = if (r18) photos else photos.filterNot { it.isR18Tagged }
            filteredOut += photos.size - usable.size
            usable.forEach { collected.putIfAbsent(it.pid, it) }

            if (collected.size >= wanted) break
        }

        if (collected.isEmpty()) {
            // 全被过滤掉时要把「过滤过」讲清楚，否则用户以为只是没搜到。
            return if (filteredOut > 0) {
                SexPhotoResult.Failed("取到的 $filteredOut 张全是 R-18，已按配置（r18: false）过滤")
            } else {
                SexPhotoResult.Failed("没有找到符合条件的作品，换个标签试试")
            }
        }
        return SexPhotoResult.Ok(collected.values.take(wanted))
    }

    // ------------------------------------------------------------------ HTTP

    private data class Response(val code: Int, val body: String)

    /**
     * 非 2xx 时也要读 body：限流（429）在响应体里带着 `status: 4290` 和一句人话，
     * 直接按 HTTP 码报错会把这句有用的说明丢掉。
     */
    private fun request(url: String): Response {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            Response(code, stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 解析 API 的错误/成功信封；不是信封就返回 null（调用方据此报 HTTP 码）。 */
    private fun parse(body: String): SexPhotoResult? {
        val root = try {
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        val statusElement = root.get("status") ?: return null
        val status = statusElement.takeIf { it.isJsonPrimitive }?.asInt ?: return null
        val message = root.get("message")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val success = root.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        if (!success || status != STATUS_OK) {
            return SexPhotoResult.Failed(describeStatus(status, message))
        }

        // ⚠️ 无结果时 API 返回 `"data": null`，而 gson 的 getAsJsonArray 碰到 JsonNull 会抛
        // ClassCastException —— 用它会把「没搜到」变成「解析失败」，误导用户。
        val dataElement = root.get("data")
        if (dataElement == null || dataElement.isJsonNull) {
            return SexPhotoResult.Failed("没有找到符合条件的作品，换个标签试试")
        }
        val array = dataElement.takeIf { it.isJsonArray }?.asJsonArray
            ?: return SexPhotoResult.Failed("API 返回的 data 字段格式异常")

        val photos = array.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val imageUrl = item.primitive("url")?.asString.orEmpty()
            if (imageUrl.isBlank()) return@mapNotNull null
            SexPhoto(
                pid = item.primitive("pid")?.asLong ?: 0L,
                title = item.primitive("title")?.asString.orEmpty(),
                author = item.primitive("author")?.asString.orEmpty(),
                authorUid = item.primitive("author_uid")?.asLong ?: 0L,
                page = item.primitive("page")?.asString.orEmpty(),
                imageUrl = imageUrl,
                width = item.primitive("width")?.asInt ?: 0,
                height = item.primitive("height")?.asInt ?: 0,
                tags = item.get("tags")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.mapNotNull { tag -> tag.takeIf { it.isJsonPrimitive }?.asString }
                    .orEmpty()
            )
        }

        return if (photos.isEmpty()) {
            SexPhotoResult.Failed("没有找到符合条件的作品，换个标签试试")
        } else {
            SexPhotoResult.Ok(photos)
        }
    }

    private fun com.google.gson.JsonObject.primitive(name: String) =
        get(name)?.takeIf { it.isJsonPrimitive }

    /** 标签里带 R-18 / R-18G 的作品 —— 正是 API 的 r18 参数漏掉的那批。 */
    private val SexPhoto.isR18Tagged: Boolean
        get() = tags.any { tag ->
            val normalized = tag.uppercase().replace(" ", "")
            normalized == TAG_R18 || normalized == TAG_R18G
        }

    /** 把 API 文档里的状态码翻译成群里能看懂的话。 */
    private fun describeStatus(status: Int, message: String): String {
        val hint = when (status) {
            STATUS_PARAM_TYPE -> "请求参数类型不正确"
            STATUS_PARAM_EMPTY -> "缺少必要参数"
            STATUS_PARAM_INVALID -> "提交的信息存在错误"
            STATUS_BLOCKED -> "请求被 API 拦截（可能是 IP 被限，或内容不符合对方规定）"
            STATUS_RATE_LIMIT -> "请求太频繁被 API 限流了，等一会儿再试"
            // 实测：搜一个根本不存在的标签，对方也回 5000，跟真实故障分不开。
            // 补一句提示，否则用户看到「服务器内部错误」会以为对面挂了。
            STATUS_SERVER_ERROR -> "SexPhoto API 报错（若你刚用了很冷门的标签，也可能是真的没搜到）"
            else -> "SexPhoto API 返回异常状态 $status"
        }
        return if (message.isBlank()) hint else "$hint（$message）"
    }

    // ------------------------------------------------------------------ 工具

    private fun buildUrl(r18: Boolean, num: Int, tags: List<String>, keyword: String?): String {
        val params = mutableListOf<String>()
        // r18=false 也显式带上：避免哪天 API 改了默认值把分级改了。
        params += "r18=${if (r18) "true" else "false"}"
        params += "num=${num.coerceIn(MIN_NUM, MAX_NUM)}"
        // 数组参数用「重复同名参数」的方式传，不是逗号分隔。
        tags.map(String::trim).filter(String::isNotEmpty).take(MAX_TAGS).forEach {
            params += "tag=${encode(it)}"
        }
        keyword?.trim()?.takeIf(String::isNotEmpty)?.let {
            params += "keyword=${encode(it)}"
        }
        val separator = if (apiBase.endsWith("/")) "" else "/"
        return "$apiBase${separator}api/v2/?${params.joinToString("&")}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val STATUS_OK = 2000
        const val STATUS_PARAM_TYPE = 4000
        const val STATUS_PARAM_EMPTY = 4001
        const val STATUS_PARAM_INVALID = 4006
        const val STATUS_BLOCKED = 4030
        const val STATUS_RATE_LIMIT = 4290
        const val STATUS_SERVER_ERROR = 5000

        const val MIN_NUM = 1
        const val MAX_NUM = 10
        const val MAX_TAGS = 10

        /** 为补足被过滤掉的数量最多请求几次。别调大：对方按 IP 限流，实测很敏感。 */
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 3000L

        const val TAG_R18 = "R-18"
        const val TAG_R18G = "R-18G"

        const val USER_AGENT = "HuHoBot-Addon-SexPhoto"
    }
}
