package io.github.tenshitmirai

import kotlinx.coroutines.*
import net.mamoe.mirai.*
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.data.GroupHonorType
import net.mamoe.mirai.event.events.*
import net.mamoe.mirai.event.subscribeAlways
import net.mamoe.mirai.message.MessageEvent
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.content
import net.mamoe.mirai.message.sendAsImageTo
import net.mamoe.mirai.utils.BotConfiguration
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import kotlin.concurrent.schedule
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlin.system.exitProcess

class MyLoader {
	companion object {
		const val revision = 9
		val seqSqr = ReentrantLock()
		var exp = Pattern.compile("Tenshit")
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
		@LowLevelAPI
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
				val miraiBot = Bot(qqId, password) {
					this.protocol = protocol
				}
				miraiBot.alsoLogin()
				println("Thank goodness, I am alive.")
				miraiBot.subscribeAlways<MessageEvent> {
					val subject = it.subject
					val context = when (subject) {
						is User -> subject.nick
						is Friend -> subject.nick
						is Group -> subject.name
						is Member -> subject.nameCardOrNick
						else -> ""
					}
					auxEvent(it.sender, subject, it.sender.id, it.senderName, subject.id, context, it.time.toLong(), it.message.content)
				}
				miraiBot.subscribeAlways<BotNudgedEvent> {
					var subject: Contact = it.from
					if (subject is Member) subject = subject.group
					val context = when (subject) {
						is User -> subject.nick
						is Group -> subject.name
						else -> ""
					}
					auxEvent(it.from, subject, it.from.id, it.from.nameCardOrNick, subject.id, context, 0, "\u001b<Nudge>")
				}
				miraiBot.subscribeAlways<BotMuteEvent> {
					auxEvent(it.operator, it.group, it.operator.id, it.operator.nameCardOrNick, it.group.id, it.group.name, 0, "\u001b<Mute ${it.durationSeconds}>")
				}
				miraiBot.subscribeAlways<BotUnmuteEvent> {
					auxEvent(it.operator, it.group, it.operator.id, it.operator.nameCardOrNick, it.group.id, it.group.name, 0, "\u001b<Unmute>")
				}
				miraiBot.subscribeAlways<MemberJoinEvent> {
					auxEvent(it.member, it.group, it.member.id, it.member.nameCardOrNick, it.group.id, it.group.name, 0, "\u001b<Member join>")
				}
				miraiBot.subscribeAlways<GroupNameChangeEvent> {
					auxEvent(it.operatorOrBot, it.group, it.operator?.id ?: 0L, it.operator?.nameCardOrNick ?: "", it.group.id, it.origin, 0, "\u001b<Group name change>${it.new}")
				}
				miraiBot.subscribeAlways<BotJoinGroupEvent> {
					auxEvent(it.group.botAsMember, it.group, 0L, "", it.group.id, it.group.name, 0, "\u001b<Join>")
				}
				miraiBot.subscribeAlways<MessageRecallEvent> {
					val subject = when (it) {
						is MessageRecallEvent.FriendRecall -> it.bot.getFriend(it.operator)
						is MessageRecallEvent.GroupRecall -> it.group
					}
					val context = when (it) {
						is MessageRecallEvent.FriendRecall -> it.bot.getFriend(it.operator).nick
						is MessageRecallEvent.GroupRecall -> it.group.name
					}
					val senderName = when (it) {
						is MessageRecallEvent.FriendRecall -> context
						is MessageRecallEvent.GroupRecall -> it.author.nameCardOrNick
					}
					auxEvent(it.bot.selfQQ, subject, it.authorId, senderName, subject.id, context, it.messageTime.toLong(), "\u001b<Delete>")
				}
				miraiBot.subscribeAlways<ImageUploadEvent.Failed> {
					try {
						it.target.sendMessage("ImageUploadEvent.Failed: ${it.message} (${it.errno})")
					} catch (e: Throwable) {
					}
				}
				async {
					println("An async coroutine has been started as expected. What would happen?")
					while (true) {
						delay(49000)
						println("The async coroutine says it's " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss z.", Locale.US).format(Date()))
						File(".").listFiles()?.forEach {
							if (it.name.startsWith("tenshit-periodic-")) {
								it.nameWithoutExtension.substring(17, it.nameWithoutExtension.length).toLongOrNull()?.let {
									println("The async coroutine is dealing with periodic event ${it}.")
									val sender = if (it < 0) miraiBot.getGroupOrNull(-it) else miraiBot.getFriendOrNull(it)
									if (sender == null) {
										println("The async coroutine failed to find ${it}.")
										return@let
									}
									val context = when (sender) {
										is User -> sender.nick
										is Group -> sender.name
										else -> ""
									}
									auxEvent(sender, sender, it.absoluteValue, context, it.absoluteValue, context, 0, "\u001b<Periodic event>")
									delay(114)
								}
							}
						}
						val trigger1 = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) == 12 && Calendar.getInstance().get(Calendar.MINUTE) == 0
						val trigger2 = File("tenshit-trigger").exists()
						if (trigger1 || trigger2) {
							println("The async coroutine is checking Ryuuoo...")
							for (group in miraiBot.groups) {
								println("...for group ${group.id}.")
								val data = miraiBot._lowLevelGetGroupHonorListData(group.id, GroupHonorType.TALKATIVE)
								data?.currentTalkative?.let {
									println("......It's ${it.uin}.")
									auxEvent(group, group, it.uin ?: 0, it.nick ?: "", group.id, group.name, 0, "\u001b<Longwang ${it.dayCount}>")
								}
								delay(5140)
							}
						}
					}
				}
				miraiBot.join() // 等待 bot 离线, 避免主线程退出
			}
		}
		suspend fun auxEvent(sender: Contact, context: Contact, senderId: Long, senderName: String, contextId: Long, contextName: String, time: Long, message: String) {
			try {
				seqSqr.lock()
				val algebraicSenderId = if (sender is Group) -senderId else senderId
				val algebraicContextId = if (context is Group) -contextId else contextId
				val x = "${algebraicSenderId}\n${senderName}\n${algebraicContextId}\n${contextName}\n${time}\n"
				if (!exp.matcher(message).find()) return
				File("tenshitaux-input.txt").writeText(x + message, Charsets.UTF_8)
				replyOne(sender, context, x)
			} catch (e: Throwable) {
				try {
					val err = e.message
					println("An exception was thrown when processing message \"${message}\".")
					if (err != null && !err.contains("for 5000 ms")) {
						context.sendMessage("\u267b\ufe0f ${err}")
					}
				} catch (e: Throwable) {
				}
			} finally {
				seqSqr.unlock()
			}
		}
		fun replyOne(sender: Contact, context: Contact, x: String) {
			runaux()
			val ret = File("tenshitaux-output.txt").readText(Charsets.UTF_8).split("\u001b<Send>")
			for (str in ret) {
				if (str.startsWith("\u001b<Callback after ")) {
					str.substring(17, str.length - 1).toLongOrNull()?.let {
						println("Now scheduling a ${it}ms timer.")
						Timer().schedule(it) {
							println("The timer gets fired.")
							File("tenshitaux-input.txt").writeText(x + "\u001b<Callback>", Charsets.UTF_8)
							replyOne(sender, context, x)
						}
					}
				} else if (str == "\u001b<Nudge sender>") {
					if (sender is User) {
						try {
							sender.nudge()
							println("Nudged ${sender.id} successfully.")
						} catch (e: Throwable) {
							File("tenshitaux-input.txt").writeText(x + "\u001b<Nudge failure>", Charsets.UTF_8)
							replyOne(sender, context, x)
						}
					}
				} else if (str.isNotEmpty()) {
					@Suppress("NAME_SHADOWING")
					val str = str.replace("\u001b<Revision>", revision.toString(), false)
					if (str.startsWith("\u001b<At sender>") && sender is Member) {
						runBlocking { context.sendMessage(At(sender) + str.substring(12, str.length)) }
					} else {
						runBlocking { context.sendMessage(str) }
					}
				}
			}
			for (ext in listOf("png", "jpg", "jpeg", "gif")) {
				val file = File("tenshitaux-output.${ext}")
				if (file.exists()) {
					val new_filename = "${Random.nextLong()}.${ext}"
					file.renameTo(File(new_filename))
					runBlocking { file.sendAsImageTo(context) }
					file.delete()
				}
			}
		}
	}
}
