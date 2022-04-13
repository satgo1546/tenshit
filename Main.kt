// TenshitMirai

// Copyright © 2020–2022 Frog Chen
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

// Rev. 13
// - 升级到Mirai 2.0-M1-1、Kotlin 1.4.21。
// - tenshitaux-input.txt中支持Online、Offline、Relogin、群成员头衔变更、好友头像变更。
// - tenshitaux-output.txt中支持更复杂的标记，如行内提及。

// Rev. 14
// - 升级到Mirai 2.5-M1、Kotlin 1.4.31。
// - tenshitaux-output.txt中支持提及任意群成员、引用回复、骰子。
// - tenshitaux-input.txt的格式与tenshit-output.txt更加统一，如由[咖啡]改为ESC<Face 60>。
// - 主程序包名由io.github.tenshitmirai.MyLoader改为io.github.salenzo.tenshitmirai.MyLoader。

// Rev. 15
// - 升级到Mirai 2.6-M1。
// - tenshitaux-input.txt中支持Mute、Unmute、引用回复、合并转发。
// - tenshitaux-output.txt中支持老千骰子。

// Rev. 16
// - 升级到Mirai 2.10.0、Kotlin 1.6.10、Shadow 7.1.2。
// - tenshit-settings.txt增加到5行：可设置心跳策略，协议改用字符串表示。
// - tenshitaux-input.txt中支持Resource::Image、Resource::Sticker、Resource::Audio、非骰子的Sticker。
// - tenshitaux-output.txt中支持非骰子的Sticker。
// - tenshitaux-output.txt中动作分隔符由ESC<Send>改为RS。
// - tenshitaux-output.txt中删除了按名称发送表情的可能性，因为可以按ID发送表情，接收也已按ID。
// - tenshitaux-input.txt和tenshitaux-output.txt中戳一戳动作的词汇由Nudge改为Poke。
// - 借助mirai-silk-converter（需要FFmpeg）支持WAV、MP3、OGG、FLAC、Opus等各种音频格式作为语音消息发送到好友和群。
// - 清理代码，构建脚本由Groovy DSL改为Kotlin DSL，减少class、var、硬编码索引substring的使用，不再设定独立应用程序不必要的包名。

// Rev. 17
// - 升级到Mirai 2.11.0-M1。
// - tenshit-settings.txt增加到6行：可设置自动接受好友申请。
// - tenshitaux-input.txt中支持Poke目标。
// - tenshitaux-output.txt中支持Context change、Poke特定用户。
// - 修正tenshitaux-input.txt中时间戳时区异常。

// Rev. 18
// - tenshitaux的超时由4秒改为100秒。
// - tenshitaux-output.txt中支持Begin scope。
// - 修正tenshitaux超时后进程没有被当即销毁的问题。修复前，这可能导致回复内容错乱。
// - 修正tenshitaux-input.txt中时间戳时区异常（二度）。我观测到Mirai返回的时间戳是协调世界时。
// - 修正tenshitaux-input.txt中出现Longwang的不可能性。
// - 在独立线程等待tenshitaux的结果，因为等待进程结束的操作会阻塞线程。
// - 不再在启动时强制要求tenshitaux运行结果状态码为零。

// 修复笔记：mirai-silk-converter无法在Raspberry Pi Zero上运行，因为没有适用于ARMv6的预编译编码器。
// 通过手工编译不带汇编加速的编码器并在发送语音前手工转换格式可以修复。
// 参照：https://github.com/kn007/silk-v3-decoder
//   make decoder encoder CDEFINES=NO_ASM
//   ffmpeg -y -i a.mp3 -f s16le -ar 24000 -ac 1 a.pcm
//   silk/encoder a.pcm b.slk -tencent

// 修复笔记：tenshitaux运行时间过长时，会影响心跳收发。
// 原因是Raspberry Pi Zero的处理器只有一个核心，故Kotlin协程线程池中线程数量是2。
// 等待其他进程的方法来自Java，阻塞线程，令协程并行能力失效。为此专辟线程，今未再现堵塞之例。

// 已知问题：合并转发消息无法在Raspberry Pi Zero上接收。
// 原因是Java 9+ Server VM不支持ARMv6，Java 8被ktor抛弃，Mirai不知情，无法在运行时查找到子类的重载函数。
// 可怕吗？是的，很可怕。过保质期的设备没有发挥余热的可能。

// Rev. 19
// - 修正tenshitaux超时后进程树没有被销毁的问题。
// - 修正tenshitaux-output.txt中连续的RS产生message is empty异常。

import kotlinx.coroutines.*
import net.mamoe.mirai.*
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.data.GroupHonorType
import net.mamoe.mirai.event.events.*
import net.mamoe.mirai.event.globalEventChannel
import net.mamoe.mirai.message.code.MiraiCode.deserializeMiraiCode
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.utils.BotConfiguration
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.MiraiExperimentalApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

const val revision = 19
var exp = Regex("Tenshit")

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
		.replace("\u001e", "\\x1e")
	return "\"${r}\""
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
			// 2022-03-19：合并转发已经坏了，下载不到内容了（悲）
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

// 在单一线程中执行tenshitaux和等待结果，自然排队。
@OptIn(DelicateCoroutinesApi::class)
val seqSqr = newSingleThreadContext("for_tenshitaux")
// 不知道怎么关闭全局资源，就扔给操作系统吧。

@Suppress("BlockingMethodInNonBlockingContext")
suspend fun askaux(what: String): String = withContext(seqSqr) {
	File("tenshitaux-input.txt").writeText(what, Charsets.UTF_8)
	val win = System.getProperty("os.name").lowercase().contains("win")
	val pb = ProcessBuilder().directory(null)
		.redirectOutput(ProcessBuilder.Redirect.INHERIT)
		.redirectError(ProcessBuilder.Redirect.INHERIT)
	val p = if (win) {
		pb.command("cmd", "/c", "tenshitaux")
	} else {
		pb.command("timeout", "--signal=KILL", "100", "./tenshitaux")
	}.start()
	if (p.waitFor(100000 + revision.toLong(), TimeUnit.MILLISECONDS)) {
		if (p.exitValue() == 0) {
			File("tenshitaux-output.txt").readText(Charsets.UTF_8)
		} else {
			"\u267b\ufe0f tenshitaux exit code ${p.exitValue()}"
		}
	} else {
		// p.destroy()和p.destroyForcibly()不终结子进程。Java开发人员可能认为这是常识而为将其写入文档。
		// pkill --parent选项不终结孙进程。
		if (win) {
			pb.command("taskkill", "/f", "/t", "/pid", p.pid().toString()).start().waitFor()
		}
		println("A tenshitaux process has been running for too long.")
		"\u231b\ufe0f tenshitaux time out"
	}
}

@LowLevelApi
@MiraiExperimentalApi
fun main() {
	println("This is Tenshit! based on Mirai, revision number ${revision}.")
	var qqId = 1145141919810L // Bot的QQ号，需为Long类型，在结尾处添加大写L
	var password = "password"
	var protocolId = ""
	var hbStrategy = ""
	var i = 0
	var friendRequestAnswer = Regex("^114514$")
	if (!File("tenshit-settings.txt").exists()) {
		println("I find no tenshit-settings.txt. I need it to log into your account.")
		println("The first line in the file is the QQ ID number.")
		println("The second line is the password, unencrypted. Your password should not begin or end with spaces.")
		println("The third line is a protocol like ANDROID_PHONE.")
		println("The fourth line is a heartbeat strategy like STAT_HB.")
		println("The fifth line is a Java regular expression. Only messages that match it will be passed to tenshitaux.")
		println("The sixth line is a Java regular expression. A friend request will be accepted only if the message matches it.")
		exitProcess(1)
	}
	File("tenshit-settings.txt").forEachLine(Charsets.UTF_8) {
		i++
		when (i) {
			1 -> qqId = it.toLongOrNull() ?: throw Exception("What a bad-smelling QQ ID!")
			2 -> password = it.trim()
			3 -> protocolId = it.trim()
			4 -> hbStrategy = it.trim()
			5 -> exp = Regex(it.trim())
			6 -> friendRequestAnswer = Regex(it.trim())
		}
	}
	if (i < 6) {
		println("Missing configuration items.")
		exitProcess(1)
	}
	if (!File("tenshitaux").exists()) {
		println("tenshitaux not found.")
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
		with (globalEventChannel()) {
			subscribeAlways<MessageEvent> {
				// 由于https://github.com/mamoe/mirai/issues/1850，禁止陌生人和临时会话触发消息事件。
				if (this is StrangerMessageEvent || this is GroupTempMessageEvent) return@subscribeAlways
				auxEvent(sender, subject, buildMessageString(message, subject), messageSource = source)
			}
			subscribeAlways<NudgeEvent> {
				val from = this.from
				if (from !is User) return@subscribeAlways // from is Bot
				val subject: Contact = if (from is Member) from.group else from
				auxEvent(from, subject, "\u001b<Poke ${target.id}>\n${action}\n${suffix}")
			}
			subscribeAlways<BotMuteEvent> {
				auxEvent(group.botAsMember, group, "\u001b<Mute ${durationSeconds}>${operator.id}\n${operator.nameCardOrNick}")
			}
			subscribeAlways<BotUnmuteEvent> {
				auxEvent(group.botAsMember, group, "\u001b<Unmute>${operator.id}\n${operator.nameCardOrNick}")
			}
			subscribeAlways<MemberMuteEvent> {
				auxEvent(member, group, "\u001b<Mute ${durationSeconds}>${operatorOrBot.id}\n${operatorOrBot.nameCardOrNick}")
			}
			subscribeAlways<MemberUnmuteEvent> {
				auxEvent(member, group, "\u001b<Unmute>${operatorOrBot.id}\n${operatorOrBot.nameCardOrNick}")
			}
			subscribeAlways<MemberJoinEvent> {
				auxEvent(member, group, "\u001b<Member join>")
			}
			subscribeAlways<MemberLeaveEvent> {
				auxEvent(member, group, "\u001b<Member quit>")
			}
			subscribeAlways<GroupNameChangeEvent> {
				println("GroupNameChangeEvent. For debugging purposes:")
				println(origin)
				println(new)
				auxEvent(operatorOrBot, group, "\u001b<Group name change>${new}", contextNameOverride = origin)
			}
			subscribeAlways<BotJoinGroupEvent> {
				auxEvent(group.botAsMember, group, "\u001b<Join>")
			}
			subscribeAlways<BotOnlineEvent> {
				auxEvent(bot.asFriend, bot.asFriend, "\u001b<Online>")
			}
			subscribeAlways<BotOfflineEvent> {
				auxEvent(bot.asFriend, bot.asFriend, "\u001b<Offline>")
			}
			subscribeAlways<BotReloginEvent> {
				auxEvent(bot.asFriend, bot.asFriend, "\u001b<Relogin>${cause?.message}\n${cause?.localizedMessage}")
			}
			subscribeAlways<MessageRecallEvent> {
				val (sender, context) = when (this) {
					is MessageRecallEvent.FriendRecall -> Pair(operator, operator)
					is MessageRecallEvent.GroupRecall -> Pair(operatorOrBot, group)
				}
				auxEvent(sender, context, "\u001b<Delete>", messageIds = messageIds)
			}
			subscribeAlways<ImageUploadEvent.Failed> {
				try {
					target.sendMessage("\u267b\ufe0f ImageUploadEvent.Failed: $message (${errno})")
				} catch (_: Throwable) {
				}
			}
			subscribeAlways<MemberSpecialTitleChangeEvent> {
				auxEvent(member, group, "\u001b<Member badge>${origin}\n${new}")
			}
			subscribeAlways<NewFriendRequestEvent> {
				print("NewFriendRequestEvent: ${inspect(message)}, ")
				delay(514)
				// 希望可以将好友验证问题作为消息对接到aux程序去，但是回复模型和正常消息不同，不太好做。
				if (friendRequestAnswer.containsMatchIn(message)) {
					accept()
					println("accepted.")
				} else {
					println("suspended.")
				}
			}
			subscribeAlways<FriendAvatarChangedEvent> {
				auxEvent(friend, friend, "\u001b<Friend avatar>${friend.avatarUrl}")
			}
			subscribeAlways<MemberHonorChangeEvent> {
				if (honorType != GroupHonorType.TALKATIVE) return@subscribeAlways
				val rawData = Mirai.getRawGroupHonorListData(bot, group.id, GroupHonorType.TALKATIVE)
				val dayCount = rawData?.currentTalkative?.dayCount ?: 0
				auxEvent(member, group, "\u001b<Longwang ${dayCount}>")
			}
		}
		launch {
			println("Yet another coroutine has been started as expected. What would happen?")
			while (true) {
				delay(49000)
				try {
					if (!miraiBot.isOnline) {
						val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
						println("The coroutine says it's $now and what the hell?")
						delay(7456)
						continue
					}
					File(".").listFiles()?.forEach { file ->
						if (file.name.startsWith("tenshit-periodic-")) {
							// The async coroutine is dealing with periodic event ${it}.
							val senderId = file.nameWithoutExtension.substringAfter("periodic-").toLongOrNull()
							val sender = getContactFromAlgebraicId(miraiBot, senderId)
							if (sender != null) {
								auxEvent(sender, sender, "\u001b<Periodic event>")
								delay(114)
							}
						}
					}
					if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) == 12 && Calendar.getInstance().get(Calendar.MINUTE) == 0
						|| File("tenshit-trigger").exists()) {
						// The async coroutine is checking longwang...
						for (group in miraiBot.groups) {
							// ...for group ${group.id}.
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

fun getContactFromAlgebraicId(bot: Bot, algebraicId: Long?): Contact? = algebraicId?.let {
	if (it == 0L || it == bot.id) return@let bot.asFriend
	if (it < 0) bot.getGroup(-it) else bot.getFriend(it)
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
			is User -> contact.nameCardOrNick
			is Group -> contact.name
			else -> ""
		})
	}

	try {
		val (algebraicSenderId, senderName) = contactInfo(sender)
		val (algebraicContextId, contextName) = contactInfo(context)
		val time = messageSource?.time ?: 0
		val header = "${algebraicSenderId}\n${senderName}\n" +
			"${algebraicContextId}\n${contextNameOverride ?: contextName}\n" +
			"${time}\n" +
			"${messageIds.joinToString(",")}\n"
		if (!exp.containsMatchIn(message)) return
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
suspend fun replyOne(sender: Contact, ctx: Contact, messageSource: MessageSource?, x: String, text: String) {
	var context = ctx
	var str = text
	while (str.isNotEmpty()) {
		if (str.startsWith("\u001b<Callback after ")) {
			str.substringBefore('>').substringAfter("after ").toLongOrNull()?.let {
				println("Now scheduling a ${it}ms timer.")
				delay(it)
				println("The timer gets fired.")
				replyOne(sender, context, messageSource, x, askaux("$x\u001b<Callback>"))
			}
		} else if (str.startsWith("\u001b<Poke ")) {
			val target: User? = when (val targetString = str.substringBefore('>').substringAfter(' ')) {
				"sender" -> sender as? User
				"self" -> sender.bot.asFriend
				else -> {
					val targetId = targetString.toLong()
					when {
						context is Group -> context[targetId]
						sender.id == targetId && sender is User -> sender
						sender.bot.id == targetId -> sender.bot.asFriend
						else -> null
					}
				}
			}
			try {
				if (target != null) {
					target.nudge().sendTo(context)
					println("Poked ${target.id} successfully.")
				} else {
					println("Can't find who to poke.")
				}
			} catch (e: Throwable) {
				replyOne(sender, context, messageSource, x, askaux("$x\u001b<Poke failure>"))
			}
		} else if (str.startsWith("\u001b<Context change ")) {
			val algebraicTargetId = str.substringBefore('>').substringAfter("change ").toLongOrNull()
			context = getContactFromAlgebraicId(context.bot, algebraicTargetId) ?: run {
				println("An attempt of context change failed (target not found). Redirecting the following replies to the bot.")
				context.bot.asFriend
			}
		} else if (str.startsWith("\u001b<Resource::Audio ")) {
			if (context is AudioSupported) {
				File(str.substringBefore('>').substringAfter(' ')).toExternalResource("silk").use {
					context.uploadAudio(it).sendTo(context)
				}
			}
		} else if (str.isNotEmpty()) {
			val (msg, newStr) = parseStringAsMessageChain(sender, context, messageSource, str)
			if (!msg.isContentEmpty()) {
				context.sendMessage(msg)
			}
			str = newStr
			continue
		}
		str = str.substringAfter('\u001e', "")
	}
}

@MiraiExperimentalApi
suspend fun parseStringAsMessageChain(sender: Contact, context: Contact, messageSource: MessageSource?, s: String): Pair<Message, String> {
	var str = s.replace("\u001b<Revision>", revision.toString(), false)
	var msg: Message = EmptyMessageChain
	val forwardMessageStack = mutableListOf<ForwardMessageBuilder>()
	var forwardMessageMetadataPending = false
	var forwardMessageSenderId = context.bot.id
	var forwardMessageSenderName = context.bot.nameCardOrNick
	var forwardMessageTime: Int = messageSource?.time ?: (System.currentTimeMillis() / 1000L).toInt()
	while (true) {
		val escapeIndex = str.indexOfAny(listOf("\u001b<", "\u001e"))
		if (escapeIndex < 0) {
			if (str.isNotEmpty()) msg += PlainText(str)
			str = ""
			break
		} else if (escapeIndex > 0) {
			if (forwardMessageStack.isNotEmpty() && forwardMessageMetadataPending) {
				val invalid = "Invalid tenshitaux-output.txt!"
				forwardMessageMetadataPending = false
				forwardMessageSenderId = str.substringBefore('\n', invalid).toLong()
				if (forwardMessageSenderId == 0L) forwardMessageSenderId = context.bot.id
				str = str.substringAfter('\n')
				forwardMessageSenderName = str.substringBefore('\n', "undefined")
				str = str.substringAfter('\n')
				str.substringBefore('\n', invalid).toInt().let {
					if (it < 114514) forwardMessageTime += it else forwardMessageTime = it
				}
				str = str.substringAfter('\n')
			} else {
				msg += PlainText(str.substring(0, escapeIndex))
				str = str.substring(escapeIndex)
			}
			continue
		} else {
			if (str.startsWith('\u001e')) {
				str = str.substring(1)
				if (forwardMessageStack.isEmpty()) {
					break
				} else {
					if (!msg.isContentEmpty()) {
						forwardMessageStack.last().add(forwardMessageSenderId, forwardMessageSenderName, msg, forwardMessageTime)
					}
					msg = EmptyMessageChain
					forwardMessageMetadataPending = true
				}
				continue
			} else if (str.startsWith("\u001b<Mention everyone>") && sender is Member) {
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
				if (messageSource != null) {
					msg += QuoteReply(messageSource)
				}
			} else if (str.startsWith("\u001b<Begin quote ")) {
				val ids = str.substring(14, str.indexOf(">")).split(",").map { it.toInt() }.toIntArray()
				val contents = str.substring(str.indexOf(">") + 1, str.indexOf("\u001b<End>"))
				str = str.substring(str.indexOf("\u001b<End>"))
				if (messageSource != null) {
					val newMsgSrc = messageSource.copyAmend {
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
			} else if (str.startsWith("\u001b<Begin scope ")) {
				val scopeContextId = str.substringBefore('>').substringAfter("scope ").toLongOrNull()
				val scopeContext = getContactFromAlgebraicId(context.bot, scopeContextId) ?: context.bot.asFriend
				val strategy = object : ForwardMessage.DisplayStrategy {
					val preamble = str.substringBefore('\u001e', "").substringAfter('>').split('\n')
					override fun generateTitle(forward: RawForwardMessage) = preamble.first()
					override fun generatePreview(forward: RawForwardMessage) =
						if (preamble.size < 2) preamble else preamble.subList(1, preamble.size - 1)
					override fun generateSummary(forward: RawForwardMessage) = preamble.last()
				}
				str = '\u001e' + str.substringAfter('\u001e')
				forwardMessageStack.add(ForwardMessageBuilder(scopeContext).apply {
					displayStrategy = strategy
				})
				continue
			} else if (str.startsWith("\u001b<End>")) {
				forwardMessageStack.removeLastOrNull()?.let {
					if (!msg.isContentEmpty()) {
						it.add(forwardMessageSenderId, forwardMessageSenderName, msg, forwardMessageTime)
					}
					msg = it.build()
				} ?: println("Nothing to <End>.")
			} else if (str.startsWith("\u001b<Emoticon ")) {
				msg += Face(str.substringBefore('>').substringAfter(' ').toIntOrNull() ?: Face.KA_FEI)
			} else if (str.startsWith("\u001b<Sticker::Dice ")) {
				str.substringBefore('>').substringAfter(' ').toIntOrNull()?.let {
					msg += Dice((it - 1) % 6 + 1)
				}
			} else if (str.startsWith("\u001b<Sticker::")) {
				str.substringBefore('>').substringAfterLast(':').toIntOrNull()?.let {
					val name = PlainText(str.substringAfter('>')).serializeToMiraiCode()
					val marketFace = "[mirai:marketface:$it,$name]".deserializeMiraiCode()
					if (marketFace.last() !is MarketFace) {
						val classes = marketFace.joinToString { it.javaClass.name }
						println("A Sticker cannot be constructed from mirai code (got $classes).")
					}
					msg = marketFace
					str = ""
				}
			} else if (str.startsWith("\u001b<Resource::Image ")) {
				File(str.substringBefore('>').substringAfter(' ')).toExternalResource().use {
					msg += context.uploadImage(it)
				}
			} else if (str.startsWith('\u001b')) {
				val seq = str.substring(1).substringBefore('>')
				println("Unrecognized escape sequence ${seq}>.")
			} else {
				println("Control should not reach here. This is a bug in Tenshit.")
			}
			str = str.substringAfter('>', "\u267b\ufe0f Missing '>'?")
		}
	}
	if (forwardMessageStack.isNotEmpty()) {
		println("Missing <End> for ${forwardMessageStack.size} <Begin scope>(s).")
	}
	return Pair(msg, str)
}
