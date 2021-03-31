package io.github.salenzo.tenshitmirai

import kotlinx.coroutines.*
import net.mamoe.mirai.*
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.contact.Contact.Companion.sendImage
import net.mamoe.mirai.data.GroupHonorType
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.*
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.BotConfiguration
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.MiraiExperimentalApi
import java.io.File
import java.lang.Exception
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlin.system.exitProcess

class MyLoader {
	@Suppress("RemoveCurlyBracesFromTemplate")
	companion object {
		const val revision = 15
		val seqSqr = ReentrantLock()
		var exp: Pattern = Pattern.compile("Tenshit")
		val emptyIntArray = IntArray(0)
		@Suppress("unused")
		fun inspect(s: String): String {
			var r = s
			r = r.replace("\\", "\\\\")
			r = r.replace("\"", "\\\"")
			r = r.replace("\n", "\\n")
			r = r.replace("\t", "\\t")
			r = r.replace("\r", "\\r")
			r = r.replace("\b", "\\b")
			r = r.replace("\u000b", "\\v")
			r = r.replace("\u000c", "\\f")
			r = r.replace("\u0007", "\\a")
			r = r.replace("\u001b", "\\e")
			return "\"${r}\""
		}
		@MiraiExperimentalApi
		fun buildMessageString(m: MessageChain): String {
			val sb = StringBuilder()
			run {
				m.forEach {
					when (it) {
						is PlainText -> sb.append(it.content)
						is Face -> sb.append("\u001b<Emoticon ${it.id}>")
						is At -> sb.append("\u001b<Mention ${it.target}>${it.content}")
						is AtAll -> sb.append("\u001b<Mention everyone>")
						is LightApp -> {
							sb.clear()
							sb.append("\u001b<Rich message::Xiaochengxu>")
							sb.append(it.content)
							return@run
						}
						is SimpleServiceMessage -> {
							sb.clear()
							sb.append("\u001b<Rich message::Service ${if (it.serviceId == 60) "XML" else "JSON"}>")
							sb.append(it.content)
							return@run
						}
						is QuoteReply -> {
							sb.append("\u001b<Begin quote ${it.source.ids.joinToString(",")}>")
							sb.append(it.source.originalMessage.content)
							sb.append("\u001b<End>")
						}
						is ForwardMessage -> {
							// 此接口尚未完全确定，建议用户暂时不要识别Begin scope
							var firstNode = true
							sb.append("\u001b<Begin scope>${it.title}\n")
							it.nodeList.forEach {
								if (firstNode) {
									firstNode = false
								} else {
									sb.append("\u001b<Send>")
								}
								sb.append("${it.senderId}\n${it.senderName}\n${it.time}\n${buildMessageString(it.messageChain)}")
							}
							sb.append("\u001b<End>")
						}
						else -> sb.append(it.content)
					}
				}
			}
			return sb.toString()
		}
		fun runaux(): Int {
			val pb = if (System.getProperty("os.name").toLowerCase().contains("win")) {
				ProcessBuilder("cmd", "/c", "tenshitaux")
			} else {
				ProcessBuilder("./tenshitaux")
			}
			pb.directory(null)
			pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
			pb.redirectError(ProcessBuilder.Redirect.INHERIT)
			val p = pb.start()
			if (!p.waitFor(4000 + revision.toLong(), TimeUnit.MILLISECONDS)) {
				return 2147483647
			}
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
		@LowLevelApi
		@MiraiExperimentalApi
		@JvmStatic
		fun main(args: Array<String>) {
			println("This is Tenshit! based on Mirai, revision number ${revision}.")
			var qqId = 1145141919810L // Bot的QQ号，需为Long类型，在结尾处添加大写L
			var password = "password"
			var protocolId = 0
			var i = 0
			if (!File("tenshit-settings.txt").exists()) {
				println("I find no tenshit-settings.txt. I need it to log into your account.")
				println("The first line in the file is the QQ ID number.")
				println("The second line is the password, unencrypted, without leading or trailing space.")
				println("The third line is 1 for Android smartphone, 2 for Android tablet, 3 for Android watch.")
				println("The fourth line is a Java regular expression. Only messages that match it will be passed to tenshitaux.")
				exitProcess(1)
			}
			File("tenshit-settings.txt").forEachLine(Charsets.UTF_8) {
				i++
				when (i) {
					1 -> qqId = it.toLongOrNull() ?: 1145141919810L
					2 -> password = it.trim()
					3 -> protocolId = it.toIntOrNull() ?: 0
					4 -> exp = Pattern.compile(it.trim())
				}
			}
			if (qqId == 1145141919810L) {
				println("What a bad-smelling QQ ID!")
				exitProcess(1)
			}
			val protocol = when (protocolId) {
				1 -> BotConfiguration.MiraiProtocol.ANDROID_PHONE
				2 -> BotConfiguration.MiraiProtocol.ANDROID_PAD
				3 -> BotConfiguration.MiraiProtocol.ANDROID_WATCH
				else -> {
					println("Protocol is out of range.")
					exitProcess(1)
				}
			}
			if (i < 4) {
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
					this.protocol = protocol
					fileBasedDeviceInfo()
					redirectBotLogToFile(File("tenshit-mirai.log"))
				}
				miraiBot.alsoLogin()
				println("Thank goodness, I am alive.")
				GlobalEventChannel.subscribeAlways<MessageEvent>  {
					val subject = this.subject
					val context = when (subject) {
						is User -> subject.nick
						is Friend -> subject.nick
						is Group -> subject.name
						is Member -> subject.nameCardOrNick
						else -> ""
					}
					auxEvent(this.sender, subject, this.source, this.sender.id, this.senderName, subject.id, context, this.time.toLong(), this.source.ids, buildMessageString(this.message))
				}
				GlobalEventChannel.subscribeAlways<NudgeEvent> {
					val from = this.from
					if (from !is User) return@subscribeAlways // from is Bot
					var subject: Contact = from
					if (subject is Member) subject = subject.group
					val context = when (subject) {
						is User -> subject.nick
						is Group -> subject.name
						else -> ""
					}
					auxEvent(from, subject, null, from.id, from.nameCardOrNick, subject.id, context, 0, emptyIntArray, "\u001b<Nudge>\n${this.action}\n${this.suffix}")
				}
				GlobalEventChannel.subscribeAlways<BotMuteEvent> {
					auxEvent(this.group.botAsMember, this.group, null, this.bot.id, this.group.botAsMember.nameCardOrNick, this.groupId, this.group.name, 0, emptyIntArray, "\u001b<Mute ${this.durationSeconds}>${this.operator.id}\n${this.operator.nameCardOrNick}")
				}
				GlobalEventChannel.subscribeAlways<BotUnmuteEvent> {
					auxEvent(this.group.botAsMember, this.group, null, this.bot.id, this.group.botAsMember.nameCardOrNick, this.groupId, this.group.name, 0, emptyIntArray, "\u001b<Unmute>${this.operator.id}\n${this.operator.nameCardOrNick}")
				}
				GlobalEventChannel.subscribeAlways<MemberMuteEvent> {
					auxEvent(this.member, this.group, null, this.member.id, this.member.nameCardOrNick, this.groupId, this.group.name, 0, emptyIntArray, "\u001b<Mute ${this.durationSeconds}>${this.operatorOrBot.id}\n${this.operatorOrBot.nameCardOrNick}")
				}
				GlobalEventChannel.subscribeAlways<MemberUnmuteEvent> {
					auxEvent(this.member, this.group, null, this.member.id, this.member.nameCardOrNick, this.groupId, this.group.name, 0, emptyIntArray, "\u001b<Unmute>${this.operatorOrBot.id}\n${this.operatorOrBot.nameCardOrNick}")
				}
				GlobalEventChannel.subscribeAlways<MemberJoinEvent> {
					auxEvent(this.member, this.group, null, this.member.id, this.member.nameCardOrNick, this.groupId, this.group.name, 0, emptyIntArray, "\u001b<Member join>")
				}
				GlobalEventChannel.subscribeAlways<MemberLeaveEvent> {
					auxEvent(this.member, this.group, null, this.member.id, this.member.nameCardOrNick, this.groupId, this.group.name, 0, emptyIntArray, "\u001b<Member quit>")
				}
				GlobalEventChannel.subscribeAlways<GroupNameChangeEvent> {
					println("GroupNameChangeEvent. For debugging purposes:")
					println(this.origin)
					println(this.new)
					auxEvent(this.operatorOrBot, this.group, null, this.operator?.id ?: 0L, this.operator?.nameCardOrNick ?: "", this.group.id, this.origin, 0, emptyIntArray, "\u001b<Group name change>${this.new}")
				}
				GlobalEventChannel.subscribeAlways<BotJoinGroupEvent> {
					auxEvent(this.group.botAsMember, this.group, null, 0L, "", this.group.id, this.group.name, 0, emptyIntArray, "\u001b<Join>")
				}
				GlobalEventChannel.subscribeAlways<BotOnlineEvent> {
					auxEvent(this.bot.asFriend, this.bot.asFriend, null, this.bot.id, this.bot.nick, this.bot.id, this.bot.nick, 0, emptyIntArray, "\u001b<Online>")
				}
				GlobalEventChannel.subscribeAlways<BotOfflineEvent> {
					auxEvent(this.bot.asFriend, this.bot.asFriend, null, this.bot.id, this.bot.nick, this.bot.id, this.bot.nick, 0, emptyIntArray, "\u001b<Offline>")
				}
				GlobalEventChannel.subscribeAlways<BotReloginEvent> {
					auxEvent(this.bot.asFriend, this.bot.asFriend, null, this.bot.id, this.bot.nick, this.bot.id, this.bot.nick, 0, emptyIntArray, "\u001b<Relogin>${this.cause?.message}\n${this.cause?.localizedMessage}")
				}
				GlobalEventChannel.subscribeAlways<MessageRecallEvent.FriendRecall> {
					val friend = this.operator
					auxEvent(friend, friend, null, this.operatorId, friend.nick, this.operatorId, friend.nick, 0, this.messageIds, "\u001b<Delete>")
				}
				GlobalEventChannel.subscribeAlways<MessageRecallEvent.GroupRecall> {
					auxEvent(this.operatorOrBot, this.group, null, this.operatorOrBot.id, this.operatorOrBot.nameCardOrNick, this.group.id, this.group.name, 0, this.messageIds, "\u001b<Delete>")
				}
				GlobalEventChannel.subscribeAlways<ImageUploadEvent.Failed> {
					try {
						this.target.sendMessage("\u267b\ufe0f ImageUploadEvent.Failed: ${this.message} (${this.errno})")
					} catch (e: Throwable) {
					}
				}
				GlobalEventChannel.subscribeAlways<MemberSpecialTitleChangeEvent> {
					auxEvent(this.member, this.group, null, this.member.id, this.member.nameCardOrNick, this.group.id, this.group.name, 0, emptyIntArray, "\u001b<Member badge>${this.origin}\n${this.new}")
				}
				GlobalEventChannel.subscribeAlways<FriendAvatarChangedEvent> {
					auxEvent(this.friend, this.friend, null, this.friend.id, this.friend.nick, this.friend.id, this.friend.nick, 0, emptyIntArray, "\u001b<Friend avatar>${this.friend.avatarUrl}")
				}
				GlobalEventChannel.subscribeAlways<GroupTalkativeChangeEvent> {
					val rawData = Mirai.getRawGroupHonorListData(this.bot, this.group.id, GroupHonorType.TALKATIVE)
					val dayCount = rawData?.currentTalkative?.dayCount ?: 0
					auxEvent(this.now, this.group, null, this.now.id, this.now.nameCardOrNick, this.group.id, this.group.name, 0, emptyIntArray, "\u001b<Longwang ${dayCount}>")
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
									file.nameWithoutExtension.substring(17, file.nameWithoutExtension.length).toLongOrNull()?.let {
										println("The async coroutine is dealing with periodic event ${it}.")
										val sender = if (it < 0) miraiBot.getGroup(-it) else miraiBot.getFriend(it)
										if (sender == null) {
											println("The async coroutine failed to find ${it}.")
											return@let
										}
										val context = when (sender) {
											is User -> sender.nick
											is Group -> sender.name
											else -> ""
										}
										auxEvent(sender, sender, null, it.absoluteValue, context, it.absoluteValue, context, 0, emptyIntArray, "\u001b<Periodic event>")
										delay(114)
									}
								}
							}
							if (protocol != BotConfiguration.MiraiProtocol.ANDROID_PHONE) {
								val trigger1 = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) == 12 && Calendar.getInstance().get(Calendar.MINUTE) == 0
								val trigger2 = File("tenshit-trigger").exists()
								if (trigger1 || trigger2) {
									println("The async coroutine is checking longwang...")
									for (group in miraiBot.groups) {
										println("...for group ${group.id}.")
										val rawData = Mirai.getRawGroupHonorListData(miraiBot, group.id, GroupHonorType.TALKATIVE)
										rawData?.currentTalkative?.let {
											val dayCount = it.dayCount
											auxEvent(group.botAsMember, group, null, it.uin ?: 0,it.nick ?: "", group.id, group.name, 0, emptyIntArray, "\u001b<Longwang ${dayCount}>")
										}
										delay(5140)
									}
								}
							}
						} catch (e: Throwable) {
							println("An exception was thrown in the coroutine: ${e.message}")
						}
					}
				}
				miraiBot.join() // 等待 bot 离线，避免主线程退出。
			}
		}
		@MiraiExperimentalApi
		suspend fun auxEvent(sender: Contact, context: Contact, msgSrc: MessageSource?, senderId: Long, senderName: String, contextId: Long, contextName: String, time: Long, messageId: IntArray, message: String) {
			try {
				val algebraicSenderId = if (sender is Group) -senderId else senderId
				val algebraicContextId = if (context is Group) -contextId else contextId
				val x = "${algebraicSenderId}\n${senderName}\n${algebraicContextId}\n${contextName}\n${time}\n${messageId.joinToString(",")}\n"
				if (!exp.matcher(message).find()) return
				replyOne(sender, context, msgSrc, x, askaux(x + message))
			} catch (e: Throwable) {
				try {
					val err = e.message
					println("An exception was thrown when processing the message \"${message}\": ${err}")
					e.printStackTrace()
					if (err != null && !err.contains("for 5000 ms")) {
						context.sendMessage("\u267b\ufe0f ${err}")
					}
				} catch (e: Throwable) {
					println("An exception was thrown when trying to send the exception as a message: ${e.message}")
				}
			}
		}
		@MiraiExperimentalApi
		suspend fun replyOne(sender: Contact, context: Contact, msgSrc: MessageSource?, x: String, text: String) {
			// 此中Int为类型：1表图片，2表语音
			val resourceTypeFilenameList = mutableListOf<Pair<Int, String>>()
			val fs = FileSystems.getDefault()
			for ((ext, type) in listOf(
				Pair("png", 1),
				Pair("jpg", 1),
				Pair("jpeg", 1),
				Pair("gif", 1),
				Pair("amr", 2),
				Pair("slk", 2),
				Pair("silk", 2),
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
			for (str in text.split("\u001b<Send>")) {
				if (str.startsWith("\u001b<Callback after ")) {
					str.substring(17, str.length - 1).toLongOrNull()?.let {
						println("Now scheduling a ${it}ms timer.")
						delay(it)
						println("The timer gets fired.")
						replyOne(sender, context, msgSrc, x, askaux(x + "\u001b<Callback>"))
					}
				} else if (str == "\u001b<Nudge sender>") {
					if (sender is User) {
						try {
							sender.nudge()
							println("Nudged ${sender.id} successfully.")
						} catch (e: Throwable) {
							replyOne(sender, context, msgSrc, x, askaux(x + "\u001b<Nudge failure>"))
						}
					}
				} else if (str.isNotEmpty()) {
					@Suppress("NAME_SHADOWING")
					var str = str.replace("\u001b<Revision>", revision.toString(), false)
					var msg: Message = EmptyMessageChain
					while (true) {
						val escapeIndex = str.indexOf("\u001b<")
						if (escapeIndex < 0) {
							if (str != "") msg += PlainText(str)
							break
						} else if (escapeIndex > 0) {
							msg += PlainText(str.substring(0, escapeIndex))
							str = str.substring(escapeIndex)
							continue
						} else {
							if (str.startsWith("\u001b<Mention everyone>") && sender is Member) {
								msg += AtAll
							} else if (str.startsWith("\u001b<Mention ") && sender is Member && context is Group) {
								val targetId = str.substring(10, str.indexOf(">")).toLongOrNull()
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
								msg = LightApp(str.substring(28))
								str = ""
							} else if (str.startsWith("\u001b<Rich message::Service JSON>")) {
								msg = SimpleServiceMessage(1, str.substring(29))
								str = ""
							} else if (str.startsWith("\u001b<Rich message::Service XML>")) {
								msg = SimpleServiceMessage(60, str.substring(28))
								str = ""
							} else if (str.startsWith("\u001b<Begin scope>")) {
								str = str.substring(14)
								MyDisplayStrategy.title = str.substring(0, str.indexOf("\n"))
								str = str.substring(str.indexOf("\n"))
								msg = buildForwardMessage(context) {
									this.displayStrategy = MyDisplayStrategy
									100200300 named "鸽子 C" at 1582315452 says "咕咕咕"
								}
								str = ""
							} else if (str.startsWith("\u001b<Emoticon ")) {
								val face = str.substring(11, str.indexOf(">"))
								var faceId = face.toIntOrNull() ?: MyFaceName.names.indexOfFirst { it == face }
								if (faceId < 0) faceId = MyFaceName.names.indexOfFirst { it == "[${face}]" }
								if (faceId < 0) faceId = Face.KA_FEI
								msg += Face(faceId)
							} else if (str.startsWith("\u001b<Sticker::Dice ")) {
								str.substring(16, str.indexOf(">")).toIntOrNull()?.let {
									msg += Dice((it - 1) % 6 + 1)
								}
							}
							str = when (str.indexOf(">")) {
								-1 -> ""
								str.length - 1 -> ""
								else -> str.substring(str.indexOf(">") + 1)
							}
						}
					}
					context.sendMessage(msg)
				}
			}
			resourceTypeFilenameList.forEach {
				// it.first为类型，it.second为文件名
				when (it.first) {
					1 -> context.sendImage(File(it.second))
					2 -> {
						if (context is Group) {
							val resource = File(it.second).toExternalResource("silk")
							context.uploadVoice(resource).sendTo(context)
							resource.close()
						}
					}
					else -> println("Unknown resource type ${it.first}. This should not happen and must be a bug in Tenshit.")
				}
				@Suppress("BlockingMethodInNonBlockingContext")
				Files.delete(fs.getPath(it.second))
			}
		}
	}
}

// https://github.com/mamoe/mirai/blob/dev/mirai-core-api/src/commonMain/kotlin/message/data/Face.kt
@Suppress("SpellCheckingInspection")
object MyFaceName {
	val names: Array<String> = Array(290) { "[表情]" }

	init {
		names[Face.JING_YA] = "[惊讶]"
		names[Face.PIE_ZUI] = "[撇嘴]"
		names[Face.SE] = "[色]"
		names[Face.FA_DAI] = "[发呆]"
		names[Face.DE_YI] = "[得意]"
		names[Face.LIU_LEI] = "[流泪]"
		names[Face.HAI_XIU] = "[害羞]"
		names[Face.BI_ZUI] = "[闭嘴]"
		names[Face.SHUI] = "[睡]"
		names[Face.DA_KU] = "[大哭]"
		names[Face.GAN_GA] = "[尴尬]"
		names[Face.FA_NU] = "[发怒]"
		names[Face.TIAO_PI] = "[调皮]"
		names[Face.ZI_YA] = "[呲牙]"
		names[Face.WEI_XIAO] = "[微笑]"
		names[Face.NAN_GUO] = "[难过]"
		names[Face.KU] = "[酷]"
		names[Face.ZHUA_KUANG] = "[抓狂]"
		names[Face.TU] = "[吐]"
		names[Face.TOU_XIAO] = "[偷笑]"
		names[Face.KE_AI] = "[可爱]"
		names[Face.BAI_YAN] = "[白眼]"
		names[Face.AO_MAN] = "[傲慢]"
		names[Face.JI_E] = "[饥饿]"
		names[Face.KUN] = "[困]"
		names[Face.JING_KONG] = "[惊恐]"
		names[Face.LIU_HAN] = "[流汗]"
		names[Face.HAN_XIAO] = "[憨笑]"
		names[Face.YOU_XIAN] = "[悠闲]"
		names[Face.FEN_DOU] = "[奋斗]"
		names[Face.ZHOU_MA] = "[咒骂]"
		names[Face.YI_WEN] = "[疑问]"
		names[Face.XU] = "[嘘]"
		names[Face.YUN] = "[晕]"
		names[Face.ZHE_MO] = "[折磨]"
		names[Face.SHUAI] = "[衰]"
		names[Face.KU_LOU] = "[骷髅]"
		names[Face.QIAO_DA] = "[敲打]"
		names[Face.ZAI_JIAN] = "[再见]"
		names[Face.FA_DOU] = "[发抖]"
		names[Face.AI_QING] = "[爱情]"
		names[Face.TIAO_TIAO] = "[跳跳]"
		names[Face.ZHU_TOU] = "[猪头]"
		names[Face.YONG_BAO] = "[拥抱]"
		names[Face.DAN_GAO] = "[蛋糕]"
		names[Face.SHAN_DIAN] = "[闪电]"
		names[Face.ZHA_DAN] = "[炸弹]"
		names[Face.DAO] = "[刀]"
		names[Face.ZU_QIU] = "[足球]"
		names[Face.BIAN_BIAN] = "[便便]"
		names[Face.KA_FEI] = "[咖啡]"
		names[Face.FAN] = "[饭]"
		names[Face.MEI_GUI] = "[玫瑰]"
		names[Face.DIAO_XIE] = "[凋谢]"
		names[Face.AI_XIN] = "[爱心]"
		names[Face.XIN_SUI] = "[心碎]"
		names[Face.LI_WU] = "[礼物]"
		names[Face.TAI_YANG] = "[太阳]"
		names[Face.YUE_LIANG] = "[月亮]"
		names[Face.ZAN] = "[赞]"
		names[Face.CAI] = "[踩]"
		names[Face.WO_SHOU] = "[握手]"
		names[Face.SHENG_LI] = "[胜利]"
		names[Face.FEI_WEN] = "[飞吻]"
		names[Face.OU_HUO] = "[怄火]"
		names[Face.XI_GUA] = "[西瓜]"
		names[Face.LENG_HAN] = "[冷汗]"
		names[Face.CA_HAN] = "[擦汗]"
		names[Face.KOU_BI] = "[抠鼻]"
		names[Face.GU_ZHANG] = "[鼓掌]"
		names[Face.QIU_DA_LE] = "[糗大了]"
		names[Face.HUAI_XIAO] = "[坏笑]"
		names[Face.ZUO_HENG_HENG] = "[左哼哼]"
		names[Face.YOU_HENG_HENG] = "[右哼哼]"
		names[Face.HA_QIAN] = "[哈欠]"
		names[Face.BI_SHI] = "[鄙视]"
		names[Face.WEI_QU] = "[委屈]"
		names[Face.KUAI_KU_LE] = "[快哭了]"
		names[Face.YIN_XIAN] = "[阴险]"
		names[Face.QIN_QIN] = "[亲亲]"
		names[Face.XIA] = "[吓]"
		names[Face.KE_LIAN] = "[可怜]"
		names[Face.CAI_DAO] = "[菜刀]"
		names[Face.PI_JIU] = "[啤酒]"
		names[Face.LAN_QIU] = "[篮球]"
		names[Face.PING_PANG] = "[乒乓]"
		names[Face.SHI_AI] = "[示爱]"
		names[Face.PIAO_CHONG] = "[瓢虫]"
		names[Face.BAO_QUAN] = "[抱拳]"
		names[Face.GOU_YIN] = "[勾引]"
		names[Face.QUAN_TOU] = "[拳头]"
		names[Face.CHA_JIN] = "[差劲]"
		names[Face.AI_NI] = "[爱你]"
		names[Face.NO] = "[NO]"
		names[Face.OK] = "[OK]"
		names[Face.ZHUAN_QUAN] = "[转圈]"
		names[Face.KE_TOU] = "[磕头]"
		names[Face.HUI_TOU] = "[回头]"
		names[Face.TIAO_SHENG] = "[跳绳]"
		names[Face.HUI_SHOU] = "[挥手]"
		names[Face.JI_DONG] = "[激动]"
		names[Face.JIE_WU] = "[街舞]"
		names[Face.XIAN_WEN] = "[献吻]"
		names[Face.ZUO_TAI_JI] = "[左太极]"
		names[Face.YOU_TAI_JI] = "[右太极]"
		names[Face.SHUANG_XI] = "[双喜]"
		names[Face.BIAN_PAO] = "[鞭炮]"
		names[Face.DENG_LONG] = "[灯笼]"
		names[Face.K_GE] = "[K歌]"
		names[Face.HE_CAI] = "[喝彩]"
		names[Face.QI_DAO] = "[祈祷]"
		names[Face.BAO_JIN] = "[爆筋]"
		names[Face.BANG_BANG_TANG] = "[棒棒糖]"
		names[Face.HE_NAI] = "[喝奶]"
		names[Face.FEI_JI] = "[飞机]"
		names[Face.CHAO_PIAO] = "[钞票]"
		names[Face.YAO] = "[药]"
		names[Face.SHOU_QIANG] = "[手枪]"
		names[Face.CHA] = "[茶]"
		names[Face.ZHA_YAN_JING] = "[眨眼睛]"
		names[Face.LEI_BEN] = "[泪奔]"
		names[Face.WU_NAI] = "[无奈]"
		names[Face.MAI_MENG] = "[卖萌]"
		names[Face.XIAO_JIU_JIE] = "[小纠结]"
		names[Face.PEN_XIE] = "[喷血]"
		names[Face.XIE_YAN_XIAO] = "[斜眼笑]"
		names[Face.doge] = "[doge]"
		names[Face.JING_XI] = "[惊喜]"
		names[Face.SAO_RAO] = "[骚扰]"
		names[Face.XIAO_KU] = "[笑哭]"
		names[Face.WO_ZUI_MEI] = "[我最美]"
		names[Face.HE_XIE] = "[河蟹]"
		names[Face.YANG_TUO] = "[羊驼]"
		names[Face.YOU_LING] = "[幽灵]"
		names[Face.DAN] = "[蛋]"
		names[Face.JU_HUA] = "[菊花]"
		names[Face.HONG_BAO] = "[红包]"
		names[Face.DA_XIAO] = "[大笑]"
		names[Face.BU_KAI_XIN] = "[不开心]"
		names[Face.LENG_MO] = "[冷漠]"
		names[Face.E] = "[呃]"
		names[Face.HAO_BANG] = "[好棒]"
		names[Face.BAI_TUO] = "[拜托]"
		names[Face.DIAN_ZAN] = "[点赞]"
		names[Face.WU_LIAO] = "[无聊]"
		names[Face.TUO_LIAN] = "[托脸]"
		names[Face.CHI] = "[吃]"
		names[Face.SONG_HUA] = "[送花]"
		names[Face.HAI_PA] = "[害怕]"
		names[Face.HUA_CHI] = "[花痴]"
		names[Face.XIAO_YANG_ER] = "[小样儿]"
		names[Face.BIAO_LEI] = "[飙泪]"
		names[Face.WO_BU_KAN] = "[我不看]"
		names[Face.TUO_SAI] = "[托腮]"
		names[Face.BO_BO] = "[啵啵]"
		names[Face.HU_LIAN] = "[糊脸]"
		names[Face.PAI_TOU] = "[拍头]"
		names[Face.CHE_YI_CHE] = "[扯一扯]"
		names[Face.TIAN_YI_TIAN] = "[舔一舔]"
		names[Face.CENG_YI_CENG] = "[蹭一蹭]"
		names[Face.ZHUAI_ZHA_TIAN] = "[拽炸天]"
		names[Face.DING_GUA_GUA] = "[顶呱呱]"
		names[Face.BAO_BAO] = "[抱抱]"
		names[Face.BAO_JI] = "[暴击]"
		names[Face.KAI_QIANG] = "[开枪]"
		names[Face.LIAO_YI_LIAO] = "[撩一撩]"
		names[Face.PAI_ZHUO] = "[拍桌]"
		names[Face.PAI_SHOU] = "[拍手]"
		names[Face.GONG_XI] = "[恭喜]"
		names[Face.GAN_BEI] = "[干杯]"
		names[Face.CHAO_FENG] = "[嘲讽]"
		names[Face.HENG] = "[哼]"
		names[Face.FO_XI] = "[佛系]"
		names[Face.QIA_YI_QIA] = "[掐一掐]"
		names[Face.JING_DAI] = "[惊呆]"
		names[Face.CHAN_DOU] = "[颤抖]"
		names[Face.KEN_TOU] = "[啃头]"
		names[Face.TOU_KAN] = "[偷看]"
		names[Face.SHAN_LIAN] = "[扇脸]"
		names[Face.YUAN_LIANG] = "[原谅]"
		names[Face.PEN_LIAN] = "[喷脸]"
		names[Face.SHENG_RI_KUAI_LE] = "[生日快乐]"
		names[Face.TOU_ZHUANG_JI] = "[头撞击]"
		names[Face.SHUAI_TOU] = "[甩头]"
		names[Face.RENG_GOU] = "[扔狗]"
		names[Face.JIA_YOU_BI_SHENG] = "[加油必胜]"
		names[Face.JIA_YOU_BAO_BAO] = "[加油抱抱]"
		names[Face.KOU_ZHAO_HU_TI] = "[口罩护体]"
		names[Face.BAN_ZHUAN_ZHONG] = "[搬砖中]"
		names[Face.MANG_DAO_FEI_QI] = "[忙到飞起]"
		names[Face.NAO_KUO_TENG] = "[脑阔疼]"
		names[Face.CANG_SANG] = "[沧桑]"
		names[Face.WU_LIAN] = "[捂脸]"
		names[Face.LA_YAN_JING] = "[辣眼睛]"
		names[Face.O_YO] = "[哦哟]"
		names[Face.TOU_TU] = "[头秃]"
		names[Face.WEN_HAO_LIAN] = "[问号脸]"
		names[Face.AN_ZHONG_GUAN_CHA] = "[暗中观察]"
		names[Face.emm] = "[emm]"
		names[Face.CHI_GUA] = "[吃瓜]"
		names[Face.HE_HE_DA] = "[呵呵哒]"
		names[Face.WO_SUAN_LE] = "[我酸了]"
		names[Face.TAI_NAN_LE] = "[太南了]"
		names[Face.LA_JIAO_JIANG] = "[辣椒酱]"
		names[Face.WANG_WANG] = "[汪汪]"
		names[Face.HAN] = "[汗]"
		names[Face.DA_LIAN] = "[打脸]"
		names[Face.JI_ZHANG] = "[击掌]"
		names[Face.WU_YAN_XIAO] = "[无眼笑]"
		names[Face.JING_LI] = "[敬礼]"
		names[Face.KUANG_XIAO] = "[狂笑]"
		names[Face.MIAN_WU_BIAO_QING] = "[面无表情]"
		names[Face.MO_YU] = "[摸鱼]"
		names[Face.MO_GUI_XIAO] = "[魔鬼笑]"
		names[Face.O] = "[哦]"
		names[Face.QING] = "[请]"
		names[Face.ZHENG_YAN] = "[睁眼]"
	}
}

object MyDisplayStrategy: ForwardMessage.DisplayStrategy {
	var title = ""
	@MiraiExperimentalApi
	override fun generateTitle(forward: RawForwardMessage): String {
		return title
	}
}
