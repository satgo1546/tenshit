// Rev. 16
// - 升级到Mirai 2.10.0、Shadow 7.1.2。
// - tenshit-settings.txt增加到5行：可设置心跳策略，协议改用字符串表示。
// - tenshit-input.txt中支持Resource::Image、Resource::Sticker、Resource::Audio、非骰子的Sticker。
// - tenshit-output.txt中支持非骰子的Sticker。
// - tenshit-output.txt中动作分隔符由ESC<Send>改为RS。
// - tenshit-output.txt中删除了按名称发送表情的可能性，因为可以按ID发送表情，接收也已按ID。
// - tenshit-input.txt和tenshit-output.txt中戳一戳动作的词汇由Nudge改为Poke。
// - 借助mirai-silk-converter（需要FFmpeg）支持WAV、MP3、OGG、FLAC、Opus等各种音频格式作为语音消息发送到好友和群。
// - 清理代码，构建脚本由Groovy DSL改为Kotlin DSL，减少class、var、硬编码索引substring的使用，不再设定独立应用程序不必要的包名。

import kotlinx.coroutines.*
import net.mamoe.mirai.BotFactory
import net.mamoe.mirai.LowLevelApi
import net.mamoe.mirai.Mirai
import net.mamoe.mirai.alsoLogin
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.contact.Contact.Companion.sendImage
import net.mamoe.mirai.data.GroupHonorType
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.*
import net.mamoe.mirai.message.code.MiraiCode.deserializeMiraiCode
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.utils.BotConfiguration
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.MiraiExperimentalApi
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import kotlin.random.Random
import kotlin.system.exitProcess

const val revision = 16
val seqSqr = ReentrantLock()
var exp: Pattern = Pattern.compile("Tenshit")

@Suppress("unused")
fun inspect(s: String): String {
	val r = s.replace("\\", "\\\\")
		.replace("\"", "\\\"")
		.replace("\n", "\\n")
		.replace("\t", "\\t")
		.replace("\r", "\\r")
		.replace("\b", "\\b")
		.replace("\u000b", "\\v")
		.replace("\u000c", "\\f")
		.replace("\u0007", "\\a")
		.replace("\u001b", "\\e")
	return "\"${r}\""
}

data class D2D2D(
	val initId: Int,
	val initName: String,
) : MarketFace {
	override val name: String
		get() = initName

	@MiraiExperimentalApi
	override val id: Int
		get() = initId
}

// 服务消息支持是实验性的。
// 获取外部资源（图片、语音、文件）下载地址需要挂起。
// 获取文件下载地址还需要文件发送方。
@MiraiExperimentalApi
suspend fun buildMessageString(messageChain: MessageChain, context: Contact?): String {
	val sb = StringBuilder()
	for (m in messageChain) when (m) {
		// 嵌入在文本消息中的消息元素。
		is PlainText -> sb.append(m.content)
		is Face -> sb.append("\u001b<Emoticon ${m.id}>")
		is At -> sb.append("\u001b<Mention ${m.target}>${m.content}")
		is AtAll -> sb.append("\u001b<Mention everyone>")
		is Image -> {
			val subType = if (m.isEmoji) "Sticker" else "Image"
			val md5String = m.md5.joinToString("") { "%02x".format(it) }
			val formatName = when (m.imageType) {
				ImageType.PNG, ImageType.APNG -> ".png"
				ImageType.JPG -> ".jpg"
				ImageType.GIF -> ".gif"
				ImageType.BMP -> ".bmp"
				ImageType.UNKNOWN -> ""
			}
			// URL中不应存在>字符，但保险起见，作如下转义。
			val url = runBlocking { m.queryUrl() }.replace(">", "%3e")
			sb.append("\u001b<Resource::${subType} ${md5String}${formatName} ${m.width}x${m.height} ${url}>")
		}
		is QuoteReply -> {
			sb.append("\u001b<Begin quote ${m.source.ids.joinToString(",")}>")
			sb.append(buildMessageString(m.source.originalMessage, context))
			sb.append("\u001b<End>")
		}
		// 应单独发出的消息元素。
		is Dice -> sb.append("\u001b<Sticker::Dice ${m.value}>")
		is MarketFace -> sb.append("\u001b<Sticker::${m.id}>${m.name}")
		is OnlineAudio -> {
			val md5String = m.fileMd5.joinToString("") { "%02x".format(it) }
			val formatName = when (m.codec) {
				AudioCodec.AMR -> ".amr"
				AudioCodec.SILK -> ".slk"
			}
			sb.append("\u001b<Resource::Audio ${md5String}${formatName} ${m.length} ${m.urlForDownload}>")
		}
		is FileMessage -> {
			val url = if (context is FileSupported) {
				m.toAbsoluteFile(context)?.getUrl()
			} else null
			sb.append("\u001b<Resource::File ${m.size} ${url}>${m.name}")
		}
		is FlashImage -> sb.append(buildMessageString(m.image.toMessageChain(), context))
		is LightApp -> {
			sb.clear()
			sb.append("\u001b<Rich message::Xiaochengxu>")
			sb.append(m.content)
			break
		}
		is SimpleServiceMessage -> {
			sb.clear()
			sb.append("\u001b<Rich message::Service ${if (m.serviceId == 60) "XML" else "JSON"}>")
			sb.append(m.content)
			break
		}
		is ForwardMessage -> {
			// 此接口尚未完全确定，建议用户暂时不要识别Begin scope。
			sb.appendLine("\u001b<Begin scope>${m.title}")
			sb.append(m.nodeList.map {
				"${it.senderId}\n${it.senderName}\n${it.time}\n${buildMessageString(it.messageChain, context)}"
			}.joinToString("\u001e"))
			sb.append("\u001b<End>")
		}
		else -> sb.append(m.content)
	}
	return sb.toString()
}

fun runaux(): Int {
	val pb = if (System.getProperty("os.name").lowercase().contains("win")) {
		ProcessBuilder("cmd", "/c", "tenshitaux")
	} else {
		ProcessBuilder("./tenshitaux")
	}
	pb.directory(null)
	pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
	pb.redirectError(ProcessBuilder.Redirect.INHERIT)
	val p = pb.start()
	if (!p.waitFor(4000 + revision.toLong(), TimeUnit.MILLISECONDS)) return 2147483647
	return p.exitValue()
}

fun askaux(what: String): String {
	return try {
		seqSqr.lock()
		File("tenshitaux-input.txt").writeText(what, Charsets.UTF_8)
		runaux()
		File("tenshitaux-output.txt").readText(Charsets.UTF_8)
	} finally {
		seqSqr.unlock()
	}
}

@OptIn(DelicateCoroutinesApi::class)
@LowLevelApi
@MiraiExperimentalApi
fun main() {
	println("This is Tenshit! based on Mirai, revision number ${revision}.")
	var qqId = 1145141919810L // Bot的QQ号，需为Long类型，在结尾处添加大写L
	var password = "password"
	var protocolId = ""
	var hbStrategy = ""
	var i = 0
	if (!File("tenshit-settings.txt").exists()) {
		println("I find no tenshit-settings.txt. I need it to log into your account.")
		println("The first line in the file is the QQ ID number.")
		println("The second line is the password, unencrypted. Your password should not begin or end with spaces.")
		println("The third line is a protocol like ANDROID_PHONE.")
		println("The fourth line is a heartbeat strategy like STAT_HB.")
		println("The fifth line is a Java regular expression. Only messages that match it will be passed to tenshitaux.")
		exitProcess(1)
	}
	File("tenshit-settings.txt").forEachLine(Charsets.UTF_8) {
		i++
		when (i) {
			1 -> qqId = it.toLongOrNull() ?: throw Exception("What a bad-smelling QQ ID!")
			2 -> password = it.trim()
			3 -> protocolId = it.trim()
			4 -> hbStrategy = it.trim()
			5 -> exp = Pattern.compile(it.trim())
		}
	}
	if (i < 5) {
		println("Missing configuration items.")
		exitProcess(1)
	}
	if (File("tenshitaux-input.txt").exists()) {
		File("tenshitaux-input.txt").delete()
	}
	println("Try to run tenshitaux with no input. Should this lock or fail, I must die.")
	if (runaux() != 0) {
		println("It failed. Quitting.")
		exitProcess(1)
	}
	runBlocking {
		val miraiBot = BotFactory.newBot(qqId, password) {
			this.protocol = BotConfiguration.MiraiProtocol.valueOf(protocolId)
			this.heartbeatStrategy = BotConfiguration.HeartbeatStrategy.valueOf(hbStrategy)
			fileBasedDeviceInfo()
			redirectBotLogToDirectory(File("tenshit-mirai.log"))
		}.alsoLogin()
		println("Thank goodness, I am alive.")
		GlobalEventChannel.subscribeAlways<MessageEvent> {
			// 由于https://github.com/mamoe/mirai/issues/1850，禁止陌生人和临时会话触发消息事件。
			if (this is StrangerMessageEvent || this is GroupTempMessageEvent) return@subscribeAlways
			auxEvent(sender, subject, buildMessageString(message, subject), messageSource = source)
		}
		GlobalEventChannel.subscribeAlways<NudgeEvent> {
			val from = this.from
			if (from !is User) return@subscribeAlways // from is Bot
			val subject: Contact = if (from is Member) from.group else from
			auxEvent(from, subject, "\u001b<Poke>\n${this.action}\n${this.suffix}")
		}
		GlobalEventChannel.subscribeAlways<BotMuteEvent> {
			auxEvent(group.botAsMember, group, "\u001b<Mute ${durationSeconds}>${operator.id}\n${operator.nameCardOrNick}")
		}
		GlobalEventChannel.subscribeAlways<BotUnmuteEvent> {
			auxEvent(group.botAsMember, group, "\u001b<Unmute>${operator.id}\n${operator.nameCardOrNick}")
		}
		GlobalEventChannel.subscribeAlways<MemberMuteEvent> {
			auxEvent(member, group, "\u001b<Mute ${durationSeconds}>${operatorOrBot.id}\n${operatorOrBot.nameCardOrNick}")
		}
		GlobalEventChannel.subscribeAlways<MemberUnmuteEvent> {
			auxEvent(member, group, "\u001b<Unmute>${operatorOrBot.id}\n${operatorOrBot.nameCardOrNick}")
		}
		GlobalEventChannel.subscribeAlways<MemberJoinEvent> {
			auxEvent(member, group, "\u001b<Member join>")
		}
		GlobalEventChannel.subscribeAlways<MemberLeaveEvent> {
			auxEvent(member, group, "\u001b<Member quit>")
		}
		GlobalEventChannel.subscribeAlways<GroupNameChangeEvent> {
			println("GroupNameChangeEvent. For debugging purposes:")
			println(origin)
			println(new)
			auxEvent(operatorOrBot, group, "\u001b<Group name change>${new}", contextNameOverride = origin)
		}
		GlobalEventChannel.subscribeAlways<BotJoinGroupEvent> {
			auxEvent(group.botAsMember, group, "\u001b<Join>")
		}
		GlobalEventChannel.subscribeAlways<BotOnlineEvent> {
			auxEvent(bot.asFriend, bot.asFriend, "\u001b<Online>")
		}
		GlobalEventChannel.subscribeAlways<BotOfflineEvent> {
			auxEvent(bot.asFriend, bot.asFriend, "\u001b<Offline>")
		}
		GlobalEventChannel.subscribeAlways<BotReloginEvent> {
			auxEvent(bot.asFriend, bot.asFriend, "\u001b<Relogin>${cause?.message}\n${cause?.localizedMessage}")
		}
		GlobalEventChannel.subscribeAlways<MessageRecallEvent> {
			val (sender, context) = when (this) {
				is MessageRecallEvent.FriendRecall -> Pair(operator, operator)
				is MessageRecallEvent.GroupRecall -> Pair(operatorOrBot, group)
			}
			auxEvent(sender, context, "\u001b<Delete>", messageIds = messageIds)
		}
		GlobalEventChannel.subscribeAlways<ImageUploadEvent.Failed> {
			try {
				target.sendMessage("\u267b\ufe0f ImageUploadEvent.Failed: ${message} (${errno})")
			} catch (_: Throwable) {
			}
		}
		GlobalEventChannel.subscribeAlways<MemberSpecialTitleChangeEvent> {
			auxEvent(member, group, "\u001b<Member badge>${origin}\n${new}")
		}
		GlobalEventChannel.subscribeAlways<FriendAvatarChangedEvent> {
			auxEvent(friend, friend, "\u001b<Friend avatar>${friend.avatarUrl}")
		}
		GlobalEventChannel.subscribeAlways<GroupTalkativeChangeEvent> {
			val rawData = Mirai.getRawGroupHonorListData(bot, group.id, GroupHonorType.TALKATIVE)
			val dayCount = rawData?.currentTalkative?.dayCount ?: 0
			auxEvent(now, group, "\u001b<Longwang ${dayCount}>")
		}
		GlobalScope.launch {
			println("Yet another coroutine has been started as expected. What would happen?")
			while (true) {
				delay(49000)
				try {
					print("The coroutine says it's " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date()))
					if (miraiBot.isOnline) {
						println(" and I am still alive.")
					} else {
						println(" and what the hell?")
						delay(7456)
						continue
					}
					File(".").listFiles()?.forEach { file ->
						if (file.name.startsWith("tenshit-periodic-")) {
							file.nameWithoutExtension.substringAfter("periodic-").toLongOrNull()?.let {
								println("The async coroutine is dealing with periodic event ${it}.")
								val sender = if (it < 0) miraiBot.getGroup(-it) else miraiBot.getFriend(it)
								if (sender == null) {
									println("The async coroutine failed to find ${it}.")
									return@let
								}
								auxEvent(sender, sender, "\u001b<Periodic event>")
								delay(114)
							}
						}
					}
					if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) == 12 && Calendar.getInstance().get(Calendar.MINUTE) == 0
						|| File("tenshit-trigger").exists()) {
						println("The async coroutine is checking longwang...")
						for (group in miraiBot.groups) {
							println("...for group ${group.id}.")
							val rawData = Mirai.getRawGroupHonorListData(miraiBot, group.id, GroupHonorType.TALKATIVE)?.currentTalkative
							if (rawData?.uin == null || rawData.dayCount == null) continue
							val member = group[rawData.uin!!] ?: continue
							auxEvent(member, group, "\u001b<Longwang ${rawData.dayCount}>")
							delay(5140)
						}
					}
				} catch (e: Throwable) {
					println("An exception was thrown in the coroutine: ${e.message}")
				}
			}
		}
		// 等待bot离线，避免主线程退出。
		miraiBot.join()
	}
}

@MiraiExperimentalApi
suspend fun auxEvent(
	sender: Contact,
	context: Contact,
	message: String,
	messageSource: MessageSource? = null,
	messageIds: IntArray = messageSource?.ids ?: intArrayOf(),
	contextNameOverride: String? = null
) {
	// 返回代数ID（正为用户，负为群）和名称（有群名片则用群名片）。
	fun contactInfo(contact: Contact?): Pair<Long, String> {
		return Pair(if (contact is Group) -contact.id else contact?.id ?: 0L, when (contact) {
			is User ->contact.nameCardOrNick
			is Group -> contact.name
			else -> ""
		})
	}

	try {
		val (algebraicSenderId, senderName) = contactInfo(sender)
		val (algebraicContextId, contextName) = contactInfo(context)
		// 转换服务器时间（UTC+8）到协调世界时。
		val time = (messageSource?.time ?: 0) - TimeUnit.HOURS.toSeconds(8)
		val header = "${algebraicSenderId}\n${senderName}\n" +
			"${algebraicContextId}\n${contextNameOverride ?: contextName}\n" +
			"${time}\n" +
			"${messageIds.joinToString(",")}\n"
		if (!exp.matcher(message).find()) return
		replyOne(sender, context, messageSource, header, askaux(header + message))
	} catch (e: Throwable) {
		try {
			val err = e.message
			println("An exception was thrown when processing the message \"${message}\": $err")
			e.printStackTrace()
			if (err != null && !err.contains("for 5000 ms")) {
				context.sendMessage("\u267b\ufe0f $err")
			}
		} catch (e: Throwable) {
			println("An exception was thrown when trying to send the exception as a message: ${e.message}")
		}
	}
}

@MiraiExperimentalApi
suspend fun replyOne(sender: Contact, context: Contact, msgSrc: MessageSource?, x: String, text: String) {
	// 此中Int为类型：1表图片，2表语音。
	val resourceTypeFilenameList = mutableListOf<Pair<Int, String>>()
	val fs = FileSystems.getDefault()
	for ((ext, type) in listOf(
		Pair("png", 1), Pair("jpg", 1), Pair("jpeg", 1), Pair("gif", 1), Pair("bmp", 1),
		Pair("amr", 2), Pair("slk", 2), Pair("silk", 2), Pair("wav", 2), Pair("wave", 2), Pair("mp3", 2),
		Pair("ogg", 2), Pair("flac", 2), Pair("opus", 2),
	)) {
		val filename = "tenshitaux-output.${ext}"
		if (File(filename).exists()) {
			val newFilename = "${Random.nextLong()}.${ext}"
			@Suppress("BlockingMethodInNonBlockingContext")
			Files.move(fs.getPath(filename), fs.getPath(newFilename), StandardCopyOption.REPLACE_EXISTING)
			if (!File(newFilename).exists()) throw Exception("An resource file disappeared immediately after being renamed. Why?")
			resourceTypeFilenameList.add(Pair(type, newFilename))
		}
	}
	for (str in text.split('\u001e')) {
		if (str.startsWith("\u001b<Callback after ")) {
			str.substring(17, str.length - 1).toLongOrNull()?.let {
				println("Now scheduling a ${it}ms timer.")
				delay(it)
				println("The timer gets fired.")
				replyOne(sender, context, msgSrc, x, askaux("$x\u001b<Callback>"))
			}
		} else if (str == "\u001b<Poke sender>") {
			if (sender is User) {
				try {
					sender.nudge().sendTo(context)
					println("Nudged ${sender.id} successfully.")
				} catch (e: Throwable) {
					replyOne(sender, context, msgSrc, x, askaux("$x\u001b<Poke failure>"))
				}
			}
		} else if (str == "\u001b<To ") {
			// TODO
			throw NotImplementedError()
		} else if (str.isNotEmpty()) {
			@Suppress("NAME_SHADOWING")
			var str = str.replace("\u001b<Revision>", revision.toString(), false)
			var msg: Message = EmptyMessageChain
			while (true) {
				val escapeIndex = str.indexOf("\u001b<")
				if (escapeIndex < 0) {
					if (str.isNotEmpty()) msg += PlainText(str)
					break
				} else if (escapeIndex > 0) {
					msg += PlainText(str.substring(0, escapeIndex))
					str = str.substring(escapeIndex)
					continue
				} else {
					if (str.startsWith("\u001b<Mention everyone>") && sender is Member) {
						msg += AtAll
					} else if (str.startsWith("\u001b<Mention ") && sender is Member && context is Group) {
						val targetId = str.substringBefore('>').substringAfter(' ').toLongOrNull()
						val target = if (targetId == null) sender else context.getMember(targetId)
						if (target != null) {
							msg += At(target)
						} else {
							println("A <Mention ...> in the following message to be sent is ignored, because such a member does not exist.")
						}
					} else if (str.startsWith("\u001b<Quote>")) {
						if (msgSrc != null) {
							msg += QuoteReply(msgSrc)
						}
					} else if (str.startsWith("\u001b<Begin quote ")) {
						val ids = str.substring(14, str.indexOf(">")).split(",").map { it.toInt() }.toIntArray()
						val contents = str.substring(str.indexOf(">") + 1, str.indexOf("\u001b<End>"))
						str = str.substring(str.indexOf("\u001b<End>"))
						if (msgSrc != null) {
							val newMsgSrc = msgSrc.copyAmend {
								this.originalMessage = contents.toPlainText().toMessageChain()
								if (!this.ids.contentEquals(ids)) {
									println("Not very good. The supplied ids in <Begin quote ...> doesn't match the message. This is not supported for the time being.")
									this.internalIds = IntArray(ids.size) { 0 }
									this.time = (System.currentTimeMillis() / 1000).toInt()
								}
								this.ids = ids
							}
							msg += QuoteReply(newMsgSrc)
						}
					} else if (str.startsWith("\u001b<Rich message::Xiaochengxu>")) {
						msg = LightApp(str.substringAfter('>'))
						str = ""
					} else if (str.startsWith("\u001b<Rich message::Service JSON>")) {
						msg = SimpleServiceMessage(1, str.substringAfter('>'))
						str = ""
					} else if (str.startsWith("\u001b<Rich message::Service XML>")) {
						msg = SimpleServiceMessage(60, str.substringAfter('>'))
						str = ""
					} else if (str.startsWith("\u001b<Begin scope>")) {
						val strategy = object : ForwardMessage.DisplayStrategy {
							val title = str.substringBefore('\n').substringAfter('>')
							override fun generateTitle(forward: RawForwardMessage): String {
								return title
							}
						}
						str = str.substringAfter('\n')
						msg = buildForwardMessage(context) {
							displayStrategy = strategy
							100200300 named "鸽子 C" at 1582315452 says "咕咕咕"
						}
						str = ""
					} else if (str.startsWith("\u001b<Emoticon ")) {
						msg += Face(str.substringBefore('>').substringAfter(' ').toIntOrNull() ?: Face.KA_FEI)
					} else if (str.startsWith("\u001b<Sticker::Dice ")) {
						str.substringBefore('>').substringAfter(' ').toIntOrNull()?.let {
							msg += Dice((it - 1) % 6 + 1)
						}
					} else if (str.startsWith("\u001b<Sticker::")) {
						str.substringBefore('>').substringAfterLast(':').toIntOrNull()?.let {
							val name = PlainText(str.substringAfter('>')).serializeToMiraiCode()
							var marketFace = "[mirai:marketface:$it,$name]".deserializeMiraiCode()
							if (marketFace.last() !is MarketFace) {
								val classes = marketFace.joinToString { it.javaClass.name }
								println("A Sticker cannot be constructed from mirai code (got $classes). Updated Mirai?")
								marketFace = D2D2D(it, name).toMessageChain()
							}
							msg = marketFace
							str = ""
						}
					}
					str = str.substringAfter('>', "")
				}
			}
			context.sendMessage(msg)
		}
	}
	resourceTypeFilenameList.forEach {
		// it.first为类型，it.second为文件名。
		when (it.first) {
			1 -> context.sendImage(File(it.second))
			2 -> {
				if (context is AudioSupported) {
					File(it.second).toExternalResource("silk").use {
						context.uploadAudio(it).sendTo(context)
					}
				}
			}
			else -> println("Unknown resource type ${it.first}. This should not happen and must be a bug in Tenshit.")
		}
		@Suppress("BlockingMethodInNonBlockingContext")
		Files.delete(fs.getPath(it.second))
	}
}