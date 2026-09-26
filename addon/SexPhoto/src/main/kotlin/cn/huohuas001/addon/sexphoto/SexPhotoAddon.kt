package cn.huohuas001.addon.sexphoto

import cn.huohuas001.huhobotPenguin.nukkit.HuHoBotNukkit
import cn.huohuas001.huhobotPenguin.nukkit.events.OnBotCommand
import cn.nukkit.event.EventHandler
import cn.nukkit.event.Listener
import cn.nukkit.plugin.PluginBase
import java.util.concurrent.ConcurrentHashMap

/**
 * HuHoBot 扩展：色图。
 *
 * 通过 HuHoBot 的扩展 API 挂接：注册一个自定义命令 key，然后监听 [OnBotCommand] 接管它。
 * 之所以要「接管」而不是让自定义命令直接干活 —— 自定义命令的本职是在服务器上执行一条命令，
 * 而这里需要发 HTTP 请求、把图片发回 QQ 群，所以取消事件让命令模板不执行，改由本扩展自己回复。
 *
 * 数据来源：https://sex.nyan.run/api.html
 */
class SexPhotoAddon : PluginBase(), Listener {

    private var hub: HuHoBotNukkit? = null
    private lateinit var client: SexPhotoClient

    private var commandKey = DEFAULT_COMMAND_KEY
    private var r18 = false
    private var num = 1
    private var defaultTags: List<String> = emptyList()
    private var lockedTags: List<String> = emptyList()
    private var cooldownSeconds = 15L
    private var replyFormat = ""

    /** 冷却表，key = "群:人"。 */
    private val lastUse = ConcurrentHashMap<String, Long>()

    override fun onEnable() {
        saveDefaultConfig()
        loadConfig()

        val plugin = server.pluginManager.getPlugin(HUB_PLUGIN_NAME)
        if (plugin !is HuHoBotNukkit) {
            // 理论上 depend 保证不会走到这；真走到了说明依赖被谁改过。
            logger.error("找不到 HuHoBot 插件（$HUB_PLUGIN_NAME），色图扩展无法工作")
            return
        }
        hub = plugin

        server.pluginManager.registerEvents(this, this)

        plugin.registerAddon(ADDON_NAME, description.version, ADDON_DESCRIPTION, ADDON_AUTHOR)
        val registered = plugin.registerBotCommand(
            addonName = ADDON_NAME,
            key = commandKey,
            // 命令模板只是占位：本扩展会取消事件，这条服务器命令永远不会被执行。
            // 万一真被执行了（扩展没接住事件），这句话就是最直接的线索。
            command = "say [SexPhoto] 该命令应由扩展处理，看到这句话说明扩展没接住事件",
            permission = 0,
            pushMenu = true
        )
        if (!registered) {
            logger.warning("注册命令 `$commandKey` 失败 —— 可能 key 已被 custom-commands 或其它扩展占用")
        }

        logger.info(
            "色图扩展已启用：/$commandKey  分级=${if (r18) "R18" else "全年龄"}  " +
                "每次 $num 张  冷却 ${cooldownSeconds}s"
        )
    }

    override fun onDisable() {
        hub?.unregisterBotCommand(commandKey)
        lastUse.clear()
        logger.info("色图扩展已卸载")
    }

    /**
     * 接管自定义命令。
     *
     * 事件是在主线程派发的，而这里要发 HTTP 请求，所以立刻取消事件（让命令模板不执行），
     * 再把真正的活儿丢到异步任务里。
     */
    @EventHandler
    fun onBotCommand(event: OnBotCommand) {
        if (event.message.commandKey != commandKey) return

        // 先取消：不管后面成功还是失败，那条占位服务器命令都不该执行。
        event.isCancelled = true

        // 每次使用都重读配置，改 config.yml 不用重启服务端。
        loadConfig()

        val groupId = event.message.groupOpenId
        val userId = event.message.sender.openId ?: event.message.sender.id ?: "unknown"

        val cooldownKey = "$groupId:$userId"
        val now = System.currentTimeMillis()
        val last = lastUse[cooldownKey]
        if (last != null && cooldownSeconds > 0) {
            val remainMs = cooldownSeconds * 1000 - (now - last)
            if (remainMs > 0) {
                event.reply("冷却中，还要等 ${(remainMs + 999) / 1000} 秒")
                return
            }
        }
        lastUse[cooldownKey] = now

        val keyword = event.message.commandArguments.orEmpty().trim().takeIf(String::isNotEmpty)
        // 异步执行：HttpURLConnection 是阻塞的，绝不能占着主线程。
        server.scheduler.scheduleTask(this, Runnable { handle(event, keyword) }, true)
    }

    private fun handle(event: OnBotCommand, keyword: String?) {
        // 用户参数优先；没给参数时退回配置里的默认标签。locked-tags 永远附加，用户覆盖不掉。
        val tags = buildList {
            addAll(lockedTags)
            if (keyword.isNullOrBlank()) addAll(defaultTags) else add(keyword)
        }.distinct()

        try {
            when (val result = client.fetch(r18 = r18, num = num, tags = tags)) {
                is SexPhotoResult.Failed -> event.reply(result.message)
                is SexPhotoResult.Ok -> result.photos.forEach { photo ->
                    event.replyImage(renderText(photo), photo.imageUrl)
                }
            }
        } catch (error: Throwable) {
            // 扩展里的异常不该把 QQ 消息处理线程带崩
            logger.error("取图失败: ${error.message}")
            event.reply("取图出错了：${error.message}")
        }
    }

    private fun renderText(photo: SexPhoto): String =
        replyFormat
            .replace("{title}", photo.title)
            .replace("{author}", photo.author)
            .replace("{pid}", photo.pid.toString())
            .replace("{page}", photo.page)

    private fun loadConfig() {
        reloadConfig()
        val cfg = config
        commandKey = cfg.getString("command-key", DEFAULT_COMMAND_KEY).trim().ifBlank { DEFAULT_COMMAND_KEY }
        r18 = cfg.getBoolean("r18", false)
        // QQ 被动回复一条消息最多挂 5 条回复，发太多会被拒，所以这里封顶。
        num = cfg.getInt("num", 1).coerceIn(1, MAX_REPLY_IMAGES)
        defaultTags = cfg.getStringList("default-tags").filter(String::isNotBlank)
        lockedTags = cfg.getStringList("locked-tags").filter(String::isNotBlank)
        cooldownSeconds = cfg.getLong("cooldown-seconds", 15L).coerceAtLeast(0L)
        replyFormat = cfg.getString("reply-format", "")
        client = SexPhotoClient(
            apiBase = cfg.getString("api-base", DEFAULT_API_BASE).ifBlank { DEFAULT_API_BASE },
            timeoutMs = cfg.getInt("timeout-ms", 15000).coerceAtLeast(1000)
        )
    }

    private companion object {
        const val HUB_PLUGIN_NAME = "HuHoBotPenguin-NukkitPlatform"

        const val ADDON_NAME = "SexPhoto"
        const val ADDON_DESCRIPTION = "调用 SexPhoto API 取一张 Pixiv 插画发到群里"
        const val ADDON_AUTHOR = "Close245"

        const val DEFAULT_COMMAND_KEY = "色图"
        const val DEFAULT_API_BASE = "https://sex.nyan.run"

        /** 一条 QQ 消息最多挂 5 条被动回复，发图数量据此封顶。 */
        const val MAX_REPLY_IMAGES = 5
    }
}
