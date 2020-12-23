package io.github.tenshitmirai

import kotlinx.coroutines.*
import net.mamoe.mirai.*
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.data.GroupHonorType
import net.mamoe.mirai.event.events.*
import net.mamoe.mirai.event.subscribeAlways
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.BotConfiguration
import net.mamoe.mirai.utils.MiraiExperimentalApi
import net.mamoe.mirai.utils.sendImage
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
	companion object {
		const val revision = 13
		val seqSqr = ReentrantLock()
		var exp: Pattern = Pattern.compile("Tenshit")
		val emptyIntArray = IntArray(0)
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
					auxEvent(it.sender, subject, it.sender.id, it.senderName, subject.id, context, it.time.toLong(), it.source.ids, it.message.content)
				}
				miraiBot.subscribeAlways<BotNudgedEvent> {
					val from = it.from
					if (from !is User) return@subscribeAlways // from is Bot
					var subject: Contact = from
					if (subject is Member) subject = subject.group
					val context = when (subject) {
						is User -> subject.nick
						is Group -> subject.name
						else -> ""
					}
					auxEvent(from, subject, from.id, from.nameCardOrNick, subject.id, context, 0, emptyIntArray, "\u001b<Nudge>\n${it.action}\n${it.suffix}")
				}
				miraiBot.subscribeAlways<BotMuteEvent> {
					auxEvent(it.operator, it.group, it.operator.id, it.operator.nameCardOrNick, it.group.id, it.group.name, 0, emptyIntArray, "\u001b<Mute ${it.durationSeconds}>")
				}
				miraiBot.subscribeAlways<BotUnmuteEvent> {
					auxEvent(it.operator, it.group, it.operator.id, it.operator.nameCardOrNick, it.group.id, it.group.name, 0, emptyIntArray, "\u001b<Unmute>")
				}
				miraiBot.subscribeAlways<MemberJoinEvent> {
					auxEvent(it.member, it.group, it.member.id, it.member.nameCardOrNick, it.group.id, it.group.name, 0, emptyIntArray, "\u001b<Member join>")
				}
				miraiBot.subscribeAlways<MemberLeaveEvent> {
					auxEvent(it.member, it.group, it.member.id, it.member.nameCardOrNick, it.group.id, it.group.name, 0, emptyIntArray, "\u001b<Member quit>")
				}
				miraiBot.subscribeAlways<GroupNameChangeEvent> {
					auxEvent(it.operatorOrBot, it.group, it.operator?.id ?: 0L, it.operator?.nameCardOrNick ?: "", it.group.id, it.origin, 0, emptyIntArray, "\u001b<Group name change>${it.new}")
				}
				miraiBot.subscribeAlways<BotJoinGroupEvent> {
					auxEvent(it.group.botAsMember, it.group, 0L, "", it.group.id, it.group.name, 0, emptyIntArray, "\u001b<Join>")
				}
				miraiBot.subscribeAlways<BotOnlineEvent> {
					auxEvent(it.bot.asFriend, it.bot.asFriend, it.bot.id, it.bot.nick, it.bot.id, it.bot.nick, 0, emptyIntArray, "\u001b<Online>")
				}
				miraiBot.subscribeAlways<BotOfflineEvent> {
					auxEvent(it.bot.asFriend, it.bot.asFriend, it.bot.id, it.bot.nick, it.bot.id, it.bot.nick, 0, emptyIntArray, "\u001b<Offline>")
				}
				miraiBot.subscribeAlways<MessageRecallEvent> {
					val subject = when (it) {
						is MessageRecallEvent.FriendRecall -> it.bot.getFriendOrFail(it.operator)
						is MessageRecallEvent.GroupRecall -> it.group
					}
					val context = when (it) {
						is MessageRecallEvent.FriendRecall -> it.bot.getFriendOrFail(it.operator).nick
						is MessageRecallEvent.GroupRecall -> it.group.name
					}
					val senderName = when (it) {
						is MessageRecallEvent.FriendRecall -> context
						is MessageRecallEvent.GroupRecall -> it.author.nameCardOrNick
					}
					auxEvent(it.bot.asFriend, subject, it.authorId, senderName, subject.id, context, it.messageTime.toLong(), it.messageIds, "\u001b<Delete>")
				}
				miraiBot.subscribeAlways<ImageUploadEvent.Failed> {
					try {
						it.target.sendMessage("\u267b\ufe0f ImageUploadEvent.Failed: ${it.message} (${it.errno})")
					} catch (e: Throwable) {
					}
				}
				miraiBot.subscribeAlways<BotReloginEvent> {
					auxEvent(it.bot.asFriend, it.bot.asFriend, it.bot.id, it.bot.nick, it.bot.id, it.bot.nick, 0, emptyIntArray, "\u001b<Relogin>${it.cause?.message}\n${it.cause?.localizedMessage}")
				}
				miraiBot.subscribeAlways<MemberSpecialTitleChangeEvent> {
					auxEvent(it.member, it.group, it.member.id, it.member.nameCardOrNick, it.group.id, it.group.name, 0, emptyIntArray, "\u001b<Member badge>${it.origin}\n${it.new}")
				}
				miraiBot.subscribeAlways<FriendAvatarChangedEvent> {
					auxEvent(it.friend, it.friend, it.friend.id, it.friend.nick, it.friend.id, it.friend.nick, 0, emptyIntArray, "\u001b<Friend avatar>${it.friend.avatarUrl}")
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
										auxEvent(sender, sender, it.absoluteValue, context, it.absoluteValue, context, 0, emptyIntArray, "\u001b<Periodic event>")
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
									val data = Mirai._lowLevelGetGroupHonorListData(miraiBot, group.id, GroupHonorType.TALKATIVE)
									data?.currentTalkative?.let {
										println("......It's ${it.uin}.")
										auxEvent(group.botAsMember, group, it.uin ?: 0, it.nick
											?: "", group.id, group.name, 0, emptyIntArray, "\u001b<Longwang ${it.dayCount}>")
									}
									delay(5140)
								}
							}
						} catch (e: Throwable) {
							println("An exception was thrown in the coroutine: ${e.message}")
						}
					}
				}
				miraiBot.join() // 等待 bot 离线, 避免主线程退出
			}
		}
		@MiraiExperimentalApi
		suspend fun auxEvent(sender: Contact, context: Contact, senderId: Long, senderName: String, contextId: Long, contextName: String, time: Long, messageId: IntArray, message: String) {
			try {
				val algebraicSenderId = if (sender is Group) -senderId else senderId
				val algebraicContextId = if (context is Group) -contextId else contextId
				val x = "${algebraicSenderId}\n${senderName}\n${algebraicContextId}\n${contextName}\n${time}\n${messageId.joinToString(",")}\n"
				if (!exp.matcher(message).find()) return
				replyOne(sender, context, x, askaux(x + message))
			} catch (e: Throwable) {
				try {
					val err = e.message
					println("An exception was thrown when processing the message \"${message}\": ${err}")
					if (err != null && !err.contains("for 5000 ms")) {
						context.sendMessage("\u267b\ufe0f ${err}")
					}
				} catch (e: Throwable) {
					println("An exception was thrown when trying to send the exception as a message: ${e.message}")
				}
			}
		}
		@MiraiExperimentalApi
		suspend fun replyOne(sender: Contact, context: Contact, x: String, text: String) {
			for (str in text.split("\u001b<Send>")) {
				if (str.startsWith("\u001b<Callback after ")) {
					str.substring(17, str.length - 1).toLongOrNull()?.let {
						println("Now scheduling a ${it}ms timer.")
						delay(it)
						println("The timer gets fired.")
						replyOne(sender, context, x, askaux(x + "\u001b<Callback>"))
					}
				} else if (str == "\u001b<Nudge sender>") {
					if (sender is User) {
						try {
							sender.nudge()
							println("Nudged ${sender.id} successfully.")
						} catch (e: Throwable) {
							replyOne(sender, context, x, askaux(x + "\u001b<Nudge failure>"))
						}
					}
				} else if (str.isNotEmpty()) {
					@Suppress("NAME_SHADOWING")
					var str = str.replace("\u001b<Revision>", revision.toString(), false)
					var msg: MessageChain = EmptyMessageChain
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
							if (str.startsWith("\u001b<Mention sender>") && sender is Member) {
								msg += At(sender)
							} else if (str.startsWith("\u001b<Mention everyone>") && sender is Member) {
								msg += AtAll
							} else if (str.startsWith("\u001b<Rich message::Xiaochengxu>")) {
								msg = LightApp(str.substring(28)).asMessageChain()
								str = ""
							} else if (str.startsWith("\u001b<Rich message::Service JSON>")) {
								msg = SimpleServiceMessage(1, str.substring(29)).asMessageChain()
								str = ""
							} else if (str.startsWith("\u001b<Rich message::Service XML>")) {
								msg = SimpleServiceMessage(60, str.substring(28)).asMessageChain()
								str = ""
							} else if (str.startsWith("\u001b<Face ")) {
								val face = str.substring(7, str.indexOf(">"))
								var faceId = face.toIntOrNull() ?: MyFaceName.names.indexOfFirst { it == face }
								if (faceId < 0) faceId = MyFaceName.names.indexOfFirst { it == "[${face}]" }
								if (faceId < 0) faceId = Face.kafei
								msg += Face(faceId)
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
			for (ext in listOf("png", "jpg", "jpeg", "gif")) {
				val filename = "tenshitaux-output.${ext}"
				if (File(filename).exists()) {
					val newFilename = "${Random.nextLong()}.${ext}"
					val fs = FileSystems.getDefault()
					Files.move(fs.getPath(filename), fs.getPath(newFilename), StandardCopyOption.REPLACE_EXISTING)
					if (!File(newFilename).exists()) throw Exception("An image file disappeared immediately after being renamed. Why?")
					context.sendImage(File(newFilename))
					Files.delete(fs.getPath(newFilename))
				}
			}
		}
	}
}

// https://github.com/mamoe/mirai/blob/dev/mirai-core-api/src/commonMain/kotlin/message/data/Face.kt
@Suppress("SpellCheckingInspection")
object MyFaceName {
	val names = Array(256) { "[表情]" }

	init {
		names[Face.jingya] = "[惊讶]"
		names[Face.piezui] = "[撇嘴]"
		names[Face.se] = "[色]"
		names[Face.fadai] = "[发呆]"
		names[Face.deyi] = "[得意]"
		names[Face.liulei] = "[流泪]"
		names[Face.haixiu] = "[害羞]"
		names[Face.bizui] = "[闭嘴]"
		names[Face.shui] = "[睡]"
		names[Face.daku] = "[大哭]"
		names[Face.ganga] = "[尴尬]"
		names[Face.fanu] = "[发怒]"
		names[Face.tiaopi] = "[调皮]"
		names[Face.ciya] = "[呲牙]"
		names[Face.weixiao] = "[微笑]"
		names[Face.nanguo] = "[难过]"
		names[Face.ku] = "[酷]"
		names[Face.zhuakuang] = "[抓狂]"
		names[Face.tu] = "[吐]"
		names[Face.touxiao] = "[偷笑]"
		names[Face.keai] = "[可爱]"
		names[Face.baiyan] = "[白眼]"
		names[Face.aoman] = "[傲慢]"
		names[Face.ji_e] = "[饥饿]"
		names[Face.kun] = "[困]"
		names[Face.jingkong] = "[惊恐]"
		names[Face.liuhan] = "[流汗]"
		names[Face.hanxiao] = "[憨笑]"
		names[Face.dabing] = "[大病]"
		names[Face.fendou] = "[奋斗]"
		names[Face.zhouma] = "[咒骂]"
		names[Face.yiwen] = "[疑问]"
		names[Face.yun] = "[晕]"
		names[Face.zhemo] = "[折磨]"
		names[Face.shuai] = "[衰]"
		names[Face.kulou] = "[骷髅]"
		names[Face.qiaoda] = "[敲打]"
		names[Face.zaijian] = "[再见]"
		names[Face.fadou] = "[发抖]"
		names[Face.aiqing] = "[爱情]"
		names[Face.tiaotiao] = "[跳跳]"
		names[Face.zhutou] = "[猪头]"
		names[Face.yongbao] = "[拥抱]"
		names[Face.dan_gao] = "[蛋糕]"
		names[Face.shandian] = "[闪电]"
		names[Face.zhadan] = "[炸弹]"
		names[Face.dao] = "[刀]"
		names[Face.zuqiu] = "[足球]"
		names[Face.bianbian] = "[便便]"
		names[Face.kafei] = "[咖啡]"
		names[Face.fan] = "[饭]"
		names[Face.meigui] = "[玫瑰]"
		names[Face.diaoxie] = "[凋谢]"
		names[Face.aixin] = "[爱心]"
		names[Face.xinsui] = "[心碎]"
		names[Face.liwu] = "[礼物]"
		names[Face.taiyang] = "[太阳]"
		names[Face.yueliang] = "[月亮]"
		names[Face.qiang] = "[强]"
		names[Face.ruo] = "[弱]"
		names[Face.woshou] = "[握手]"
		names[Face.shengli] = "[胜利]"
		names[Face.feiwen] = "[飞吻]"
		names[Face.naohuo] = "[恼火]"
		names[Face.xigua] = "[西瓜]"
		names[Face.lenghan] = "[冷汗]"
		names[Face.cahan] = "[擦汗]"
		names[Face.koubi] = "[抠鼻]"
		names[Face.guzhang] = "[鼓掌]"
		names[Face.qiudale] = "[糗大了]"
		names[Face.huaixiao] = "[坏笑]"
		names[Face.zuohengheng] = "[左哼哼]"
		names[Face.youhengheng] = "[右哼哼]"
		names[Face.haqian] = "[哈欠]"
		names[Face.bishi] = "[鄙视]"
		names[Face.weiqu] = "[委屈]"
		names[Face.kuaikule] = "[快哭了]"
		names[Face.yinxian] = "[阴险]"
		names[Face.qinqin] = "[亲亲]"
		names[Face.xia] = "[吓]"
		names[Face.kelian] = "[可怜]"
		names[Face.caidao] = "[菜刀]"
		names[Face.pijiu] = "[啤酒]"
		names[Face.lanqiu] = "[篮球]"
		names[Face.pingpang] = "[乒乓]"
		names[Face.shiai] = "[示爱]"
		names[Face.piaochong] = "[瓢虫]"
		names[Face.baoquan] = "[抱拳]"
		names[Face.gouyin] = "[勾引]"
		names[Face.quantou] = "[拳头]"
		names[Face.chajin] = "[差劲]"
		names[Face.aini] = "[爱你]"
		names[Face.bu] = "[NO]"
		names[Face.hao] = "[OK]"
		names[Face.zhuanquan] = "[转圈]"
		names[Face.ketou] = "[磕头]"
		names[Face.huitou] = "[回头]"
		names[Face.tiaosheng] = "[跳绳]"
		names[Face.huishou] = "[挥手]"
		names[Face.jidong] = "[激动]"
		names[Face.jiewu] = "[街舞]"
		names[Face.xianwen] = "[献吻]"
		names[Face.zuotaiji] = "[左太极]"
		names[Face.youtaiji] = "[右太极]"
		names[Face.shuangxi] = "[双喜]"
		names[Face.bianpao] = "[鞭炮]"
		names[Face.denglong] = "[灯笼]"
		names[Face.facai] = "[发财]"
		names[Face.K_ge] = "[K歌]"
		names[Face.gouwu] = "[购物]"
		names[Face.youjian] = "[邮件]"
		names[Face.shuai_qi] = "[帅气]"
		names[Face.hecai] = "[喝彩]"
		names[Face.qidao] = "[祈祷]"
		names[Face.baojin] = "[爆筋]"
		names[Face.bangbangtang] = "[棒棒糖]"
		names[Face.he_nai] = "[喝奶]"
		names[Face.xiamian] = "[下面]"
		names[Face.xiangjiao] = "[香蕉]"
		names[Face.feiji] = "[飞机]"
		names[Face.kaiche] = "[开车]"
		names[Face.gaotiezuochetou] = "[高铁左车头]"
		names[Face.chexiang] = "[车厢]"
		names[Face.gaotieyouchetou] = "[高铁右车头]"
		names[Face.duoyun] = "[多云]"
		names[Face.xiayu] = "[下雨]"
		names[Face.chaopiao] = "[钞票]"
		names[Face.xiongmao] = "[熊猫]"
		names[Face.dengpao] = "[灯泡]"
		names[Face.fengche] = "[风车]"
		names[Face.naozhong] = "[闹钟]"
		names[Face.dasan] = "[打伞]"
		names[Face.caiqiu] = "[彩球]"
		names[Face.zuanjie] = "[钻戒]"
		names[Face.shafa] = "[沙发]"
		names[Face.zhijin] = "[纸巾]"
		names[Face.yao] = "[药]"
		names[Face.shouqiang] = "[手枪]"
		names[Face.qingwa] = "[青蛙]"
		names[Face.hexie] = "[河蟹]"
		names[Face.yangtuo] = "[羊驼]"
		names[Face.youling] = "[幽灵]"
		names[Face.dan] = "[蛋]"
		names[Face.juhua] = "[菊花]"
		names[Face.hongbao] = "[红包]"
		names[Face.daxiao] = "[大笑]"
		names[Face.bukaixin] = "[不开心]"
		names[Face.lengmo] = "[冷漠]"
		names[Face.e] = "[呃]"
		names[Face.haobang] = "[好棒]"
		names[Face.baituo] = "[拜托]"
		names[Face.dianzan] = "[点赞]"
		names[Face.wuliao] = "[无聊]"
		names[Face.tuolian] = "[托脸]"
		names[Face.chi] = "[吃]"
		names[Face.songhua] = "[送花]"
		names[Face.haipa] = "[害怕]"
		names[Face.huachi] = "[花痴]"
		names[Face.xiaoyanger] = "[小样儿]"
		names[Face.biaolei] = "[飙泪]"
		names[Face.wobukan] = "[我不看]"
		names[212] = "[托腮]"
		names[Face.bobo] = "[啵啵]"
		names[Face.hulian] = "[糊脸]"
		names[Face.paitou] = "[拍头]"
		names[Face.cheyiche] = "[扯一扯]"
		names[Face.tianyitian] = "[舔一舔]"
		names[Face.cengyiceng] = "[蹭一蹭]"
		names[Face.zhuaizhatian] = "[拽炸天]"
		names[Face.dingguagua] = "[顶呱呱]"
		names[Face.baobao] = "[抱抱]"
		names[Face.baoji] = "[暴击]"
		names[Face.kaiqiang] = "[开枪]"
		names[Face.liaoyiliao] = "[撩一撩]"
		names[Face.paizhuo] = "[拍桌]"
		names[Face.paishou] = "[拍手]"
		names[Face.gongxi] = "[恭喜]"
		names[Face.ganbei] = "[干杯]"
		names[Face.chaofeng] = "[嘲讽]"
		names[Face.heng] = "[哼]"
		names[Face.foxi] = "[佛系]"
		names[Face.qiaoyiqioa] = "[敲一敲]"
		names[Face.jingdai] = "[惊呆]"
		names[Face.chandou] = "[颤抖]"
		names[Face.kentou] = "[啃头]"
		names[Face.toukan] = "[偷看]"
		names[Face.shanlian] = "[扇脸]"
		names[Face.yuanliang] = "[原谅]"
		names[Face.penlian] = "[喷脸]"
		names[Face.shengrikuaile] = "[生日快乐]"
		names[Face.touzhuangji] = "[头撞击]"
		names[Face.shuaitou] = "[甩头]"
		names[Face.rengou] = "[扔狗]"
		names[245] = "[必胜加油]"
		names[246] = "[加油抱抱]"
		names[247] = "[口罩护体]"
	}
}
