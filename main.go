// TenshitMiraiGo

// Copyright © 2022 Frog Chen
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

// Rev. 1
// - 可以登录了。

// Rev. 2
// - tenshitaux-input.txt中支持纯文本。
// - tenshitaux-output.txt中支持纯文本、记录分隔符、Callback after、Emoticon，其他的代码写了但没测。

// 已知问题：没有异常，有的函数不返回没有错误信息，闷声不吭，极难调试。
// 这就是一门专为网络编程设计的语言。
// 原因有代数上下文ID蹩脚，也有MiraiGo不合Go原理。
// 本计划搁置，或不再重启。

package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	_ "image/png"
	"os"
	"os/exec"
	"os/signal"
	"regexp"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/Mrs4s/MiraiGo/binary"
	"github.com/Mrs4s/MiraiGo/client"
	"github.com/Mrs4s/MiraiGo/message"

	"github.com/skip2/go-qrcode"
	tuotoo "github.com/tuotoo/qrcode"
)

const revision = 2

var exp *regexp.Regexp

var console = bufio.NewReader(os.Stdin)

func readLine() string {
	str, _ := console.ReadString('\n')
	str = strings.TrimSpace(str)
	return str
}

var seqSqr sync.Mutex

func askaux(what string) string {
	seqSqr.Lock()
	defer seqSqr.Unlock()
	os.WriteFile("tenshitaux-input.txt", []byte(what), 0644)
	// 因为不需要重定向标准输入输出等，原本想用os.StartProcess的，但搞不定超时终结术。
	// Kill()能终结一个进程，终结不掉千万子进程。Go的文档这么说，才发现Java也一样。
	// https://www.jianshu.com/p/e147d856074c
	var cmd exec.Cmd
	ctx, cancel := context.WithTimeout(context.Background(), (2000+revision)*time.Millisecond)
	defer cancel()
	if runtime.GOOS == "windows" {
		cmd = *exec.CommandContext(ctx, "cmd.exe", "/c", "tenshitaux")
	} else {
		cmd = *exec.CommandContext(ctx, "timeout", "--signal=KILL", "2", "./tenshitaux")
	}
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			if runtime.GOOS == "windows" && cmd.Process != nil {
				exec.Command("taskkill.exe", "/f", "/t", "/pid", fmt.Sprint(cmd.Process.Pid)).Run()
			}
			fmt.Printf("A tenshitaux process has been running for too long.\n")
		case <-done:
		}
	}()
	if err := cmd.Run(); err == nil {
		if bytes, err := os.ReadFile("tenshitaux-output.txt"); err == nil {
			return string(bytes)
		} else {
			return fmt.Sprintf("\u267b\ufe0f unreadable tenshitaux output: %v", err)
		}
	} else {
		fmt.Printf("tenshitaux failed: %v.\n", err)
		return fmt.Sprintf("\u267b\ufe0f spawn: %v", err)
	}
}

// bot实例
var Instance *client.QQClient

func qrCodeLogin() error {
	rsp, err := Instance.FetchQRCode()
	if err != nil {
		return err
	}
	fi, err := tuotoo.Decode(bytes.NewReader(rsp.ImageData))
	if err != nil {
		return err
	}
	os.WriteFile("qrcode.png", rsp.ImageData, 0644)
	defer os.Remove("qrcode.png")
	if qr, err := qrcode.New(fi.Content, qrcode.Medium); err == nil {
		data := qr.Bitmap()
		for i := 3; i < len(data)-3; i += 2 {
			for j := 3; j < len(data)-3; j++ {
				if data[i+1][j] {
					if data[i][j] {
						fmt.Print("\u2588")
					} else {
						fmt.Print("\u2584")
					}
				} else {
					if data[i][j] {
						fmt.Print("\u2580")
					} else {
						fmt.Print(" ")
					}
				}
			}
			fmt.Println()
		}
	} else {
		fmt.Printf("Failed to generate QR code: %v.\n", err)
	}
	fmt.Printf("Scan the QR code (qrcode.png) on the target.\nThe URL encoded follows in case of difficulty.\n%v\n", fi.Content)
	s, err := Instance.QueryQRCodeStatus(rsp.Sig)
	if err != nil {
		return err
	}
	for prevState := s.State; ; prevState = s.State {
		time.Sleep(time.Second)
		s, _ = Instance.QueryQRCodeStatus(rsp.Sig)
		if s == nil {
			continue
		}
		if prevState == s.State {
			continue
		}
		switch s.State {
		case client.QRCodeCanceled:
			fmt.Printf("Cancelled.\n")
		case client.QRCodeTimeout:
			fmt.Printf("Link expired.\n")
		case client.QRCodeWaitingForConfirm:
			fmt.Printf("Acknowledged; waiting for interaction.\n")
		case client.QRCodeConfirmed:
			res, err := Instance.QRCodeLogin(s.LoginInfo)
			if err != nil {
				return err
			}
			return loginResponseProcessor(res)
		case client.QRCodeImageFetch, client.QRCodeWaitingForScan:
			// ignore
		}
	}
}

// 登录结果处理
func loginResponseProcessor(res *client.LoginResponse) (err error) {
	for {
		if err != nil {
			return
		}
		if res.Success {
			return nil
		}
		switch res.Error {
		case client.SliderNeededError:
			Instance.Disconnect()
			Instance.Release()
			Instance = client.NewClientEmpty()
			fmt.Printf("Forbidden. A QR scan is required.\n")
			return qrCodeLogin()
		case client.NeedCaptcha:
			_ = os.WriteFile("captcha.jpg", res.CaptchaImage, 0644)
			defer os.Remove("captcha.jpg")
			fmt.Printf("captcha.jpg saved.\nCaptcha? ")
			res, err = Instance.SubmitCaptcha(readLine(), res.CaptchaSign)
		case client.SMSNeededError:
			if Instance.RequestSMS() {
				fmt.Printf("Authorization code sent to phone number %v.\nCode? ", res.SMSPhone)
				res, err = Instance.SubmitSMS(readLine())
			} else {
				fmt.Printf("Failed to send SMS.\n")
			}
		case client.SMSOrVerifyNeededError:
			fmt.Printf("1) send authorization code to phone number %v\n2) fallback to a QR scan\n[1-2]? ", res.SMSPhone)
			if readLine() == "1" {
				res.Error = client.SMSNeededError
			} else {
				// 是否有误？go-cqhttp中相关代码已有一年未动，有误也不用管了。
				res.Error = client.UnsafeDeviceError
			}
		case client.UnsafeDeviceError:
			fmt.Printf("The device is rumored unsafe. Go here.\n%v\n", res.VerifyUrl)
			os.Exit(0)
		case client.OtherLoginError, client.UnknownLoginError, client.TooManySMSRequestError:
			fmt.Printf("Bad login: %v.\n", res.ErrorMessage)
			if strings.Contains(res.ErrorMessage, "版本") {
				fmt.Printf("Note that this message also refers to bad credentials.\n")
			}
			os.Exit(0)
		}
	}
}

func main() {
	fmt.Printf("This is TenshitMiraiGo, revision number %d.\n", revision)
	var options []string
	if bytes, err := os.ReadFile("tenshit-settings.txt"); err == nil {
		options = strings.Split(string(bytes), "\n")
	} else {
		fmt.Println("I find no tenshit-settings.txt.")
		fmt.Println("The first line in the file is the QQ ID number or zero.")
		fmt.Println("The second line is the password, unencrypted.")
		fmt.Println("The third line is a protocol like ANDROID_PHONE.")
		fmt.Println("The fourth line is unused.")
		fmt.Println("The fifth line is a RE2 regular expression. Only messages that match it will be passed to tenshitaux.")
		fmt.Println("The sixth line is a RE2 regular expression. A friend request will be accepted only if the message matches it.")
		os.Exit(1)
	}
	if len(options) < 6 {
		fmt.Printf("Missing configuration items.\n")
		os.Exit(1)
	}
	var qqId int64 = 1145141919810
	if _, err := fmt.Sscan(options[0], &qqId); err != nil {
		fmt.Printf("What a bad-smelling QQ ID!\n")
		os.Exit(1)
	}
	var protocol client.ClientProtocol
	switch options[2] {
	case "ANDROID_PHONE":
		protocol = client.AndroidPhone
	case "ANDROID_WATCH":
		protocol = client.AndroidWatch
	case "MACOS":
		protocol = client.MacOS
	case "IPAD":
		protocol = client.IPad
	case "QIDIAN":
		protocol = client.QiDian
	default:
		fmt.Printf("Not recognized protocol %q.\n", options[2])
		os.Exit(1)
	}
	exp = regexp.MustCompile(options[4])
	friendRequestAnswer := regexp.MustCompile(options[5])
	if _, err := os.Stat("tenshitaux"); err != nil {
		fmt.Printf("tenshitaux not found.\n")
		os.Exit(1)
	}
	if _, err := os.Stat("device.json"); err == nil {
		fmt.Printf("device.json detected.\n")
	} else {
		// 初次使用则生成随机设备信息。
		fmt.Printf("Generating device.json.\n")
		client.GenRandomDevice()
		if err := os.WriteFile("device.json", client.SystemDeviceInfo.ToJson(), 0644); err != nil {
			fmt.Printf("Unable to write device.json.\n")
		}
	}
	// 从device.json读入设备信息。
	Instance = client.NewClient(qqId, options[1])
	if bytes, err := os.ReadFile("device.json"); err == nil {
		if err := client.SystemDeviceInfo.ReadJson(bytes); err == nil {
			fmt.Printf("Using the current device.json.\n")
		} else {
			fmt.Printf("Can't parse device.json.\n")
		}
	} else {
		fmt.Printf("Can't read device.json.\n")
	}
	// TODO：在这里初始化Protocol正确吗？
	client.SystemDeviceInfo.Protocol = protocol

	// 处理各种事件。
	Instance.PrivateMessageEvent.Subscribe(func(_ *client.QQClient, event *message.PrivateMessage) {
		fmt.Printf("Friend message from %v: %q.\n", event.Sender.Uin, event.ToString())
		if event.Sender.Uin == Instance.Uin {
			return
		}
		// DisplayName() == CardName || Nickname
		auxEvent(event.Sender.Uin, event.Sender.DisplayName(), event.Sender.Uin, event.Sender.DisplayName(), event.ToString(), event.Time, []int32{event.Id, event.InternalId})
	})
	Instance.GroupMessageEvent.Subscribe(func(_ *client.QQClient, event *message.GroupMessage) {
		fmt.Printf("Group message from %v in %v: %q.\n", event.Sender.Uin, event.GroupCode, event.ToString())
		if event.Sender.Uin == Instance.Uin {
			return
		}
		auxEvent(event.Sender.Uin, event.Sender.DisplayName(), -event.GroupCode, event.GroupName, event.ToString(), event.Time, []int32{event.Id, event.InternalId})
	})
	Instance.FriendNotifyEvent.Subscribe(func(_ *client.QQClient, event client.INotifyEvent) {
		fmt.Printf("Friend poke from %v: %q.\n", event.From(), event.Content())
		if event.From() == Instance.Uin {
			return
		}
		switch event := event.(type) {
		case *client.FriendPokeNotifyEvent:
			auxEvent(event.Sender, "", event.Sender, "", fmt.Sprintf("\x1b<Poke %v>%v", event.Receiver, event.Content()), 0, nil)
		}
	})
	Instance.GroupNotifyEvent.Subscribe(func(_ *client.QQClient, event client.INotifyEvent) {
		fmt.Printf("Group poke in %v: %q\n", event.From(), event.Content())
	})
	Instance.FriendMessageRecalledEvent.Subscribe(func(_ *client.QQClient, event *client.FriendMessageRecalledEvent) {
		fmt.Printf("好友撤回%v %v\n", event.FriendUin, event.MessageId)
	})
	Instance.GroupMessageRecalledEvent.Subscribe(func(_ *client.QQClient, event *client.GroupMessageRecalledEvent) {
		fmt.Printf("群撤回 %v %v %v %v %v\n", event.AuthorUin, event.GroupCode, event.MessageId, event.Time, event.OperatorUin)
	})
	Instance.DisconnectedEvent.Subscribe(func(_ *client.QQClient, event *client.ClientDisconnectedEvent) {
		fmt.Printf("Disconnected due to %q.\n", event.Message)
	})
	Instance.NewFriendRequestEvent.Subscribe(func(client *client.QQClient, event *client.NewFriendRequest) {
		fmt.Printf("NewFriendRequestEvent from %s: %q, ", event.RequesterNick, event.Message)
		time.Sleep(514 * time.Millisecond)
		if friendRequestAnswer.MatchString(event.Message) {
			event.Accept()
			fmt.Printf("accepted.\n")
		} else {
			fmt.Printf("suspended.\n")
		}
	})

	// 登录流程过于复杂！通过API传递这些错误照理是正确的，但由于项目的特殊性……
	// https://github.com/Mrs4s/go-cqhttp/blob/master/cmd/gocq/login.go
	// Kotlin那边把这些逻辑全部封在login方法内，导致对Swing的奇怪依赖。
	if token, err := os.ReadFile("session.token"); err == nil {
		// 存在会话缓存，快速恢复会话。
		if uin := binary.NewReader(token).ReadInt64(); Instance.Uin != 0 && uin != Instance.Uin {
			fmt.Printf(
				"Loading session.token, albeit with mismatching QQ IDs (%v in tenshit-settings.txt but %v in session.token).\n",
				Instance.Uin, uin,
			)
		} else {
			fmt.Printf("Reusing session.token.\n")
		}
		if err = Instance.TokenLogin(token); err == nil {
			fmt.Printf("Session recovery done.\n")
		} else {
			fmt.Printf("Session recovery failed: %v.\nTry manual login by removing session.token.\n", err)
			Instance.Disconnect()
			Instance.Release()
		}
	} else {
		if Instance.Uin != 0 {
			// 标准登录。
			if res, err := Instance.Login(); err == nil {
				loginResponseProcessor(res)
			} else {
				fmt.Printf("Login failed: %v.\n", err)
			}
		} else {
			// 提供的QQ号为零，扫码登录。
			qrCodeLogin()
		}
	}

	// 刷新联系人列表。
	fmt.Printf("Reloading friends... ")
	if err := Instance.ReloadFriendList(); err == nil {
		fmt.Printf("%d items.\n", len(Instance.FriendList))
	} else {
		fmt.Printf("error %v.\n", err)
	}
	fmt.Printf("Reloading groups... ")
	if err := Instance.ReloadGroupList(); err == nil {
		fmt.Printf("%d items.\n", len(Instance.GroupList))
	} else {
		fmt.Printf("error %v.\n", err)
	}
	// 主循环。
	fmt.Printf("Thank goodness, I am alive.\n")
	ch := make(chan os.Signal)
	signal.Notify(ch, os.Interrupt)
	<-ch
	// 结束时保存会话缓存。
	os.WriteFile("session.token", Instance.GenToken(), 0644)
	println("Token saved.")
}

func auxEvent(
	sender int64, senderName string,
	context int64, contextName string,
	messageString string,
	time int32,
	messageIds []int32,
) {
	debug.SetPanicOnFault(true)
	defer func() {
		if r := recover(); r != nil {
			defer func() {
				if r := recover(); r != nil {
					fmt.Printf("The panic handler has panicked: %v\n", r)
				}
			}()
			fmt.Printf("Something has panicked while processing the message %q: %v\n", messageString, r)
			elems := []message.IMessageElement{message.NewText(fmt.Sprintf("\u267b\ufe0f %v", r))}
			if context < 0 {
				Instance.SendGroupMessage(-context, &message.SendingMessage{Elements: elems})
			} else {
				Instance.SendPrivateMessage(context, &message.SendingMessage{Elements: elems})
			}
		}
	}()
	// ?:也没有，||=也没有，库还不搞接口，就是这个下场。
	if context < 0 {
		group := Instance.FindGroup(-context)
		if group == nil {
			return
		}
		if contextName == "" {
			contextName = group.Name
		}
		member := group.FindMember(sender)
		if member == nil {
			return
		}
		if senderName == "" {
			senderName = member.DisplayName()
		}
	} else {
		friend := Instance.FindFriend(context)
		if friend == nil {
			return
		}
		if contextName == "" {
			contextName = friend.Nickname
		}
		from := Instance.FindFriend(sender)
		if from == nil {
			return
		}
		if senderName == "" {
			senderName = from.Nickname
		}
	}
	// 把整数数组合并成字符串，用逗号分隔。
	// Go有strings.Join，但没有[]int到[]string类型转换……这是对的，也是不通情达理的。
	// JavaScript：你说什么？（只需要messageIds+[]。逗号是因为它是JavaScript。）
	// 所以借JSON一臂之力。
	messageIdsString, _ := json.Marshal(messageIds)
	header := fmt.Sprintf("%v\n%v\n%v\n%v\n%v\n%v\n",
		sender, senderName,
		context, contextName,
		time,
		strings.Trim(string(messageIdsString), "[]"),
	)
	if !exp.MatchString(messageString) {
		return
	}
	replyOne(sender, context, time, messageIds, header, askaux(header+messageString))
}

func replyOne(sender, context int64, messageTime int32, messageIds []int32, x, str string) {
	for str != "" {
		// 用于fmt.Sscanf的临时变量。
		var a int64
		if _, err := fmt.Sscanf(str, "\x1b<Callback after %d>", &a); err == nil {
			fmt.Printf("Now scheduling a %dms timer.\n", a)
			time.Sleep(time.Duration(a) * time.Millisecond)
			fmt.Printf("The timer gets fired.\n")
			replyOne(sender, context, messageTime, messageIds, x, askaux(x+"\x1b<Callback>"))
		} else if strings.HasPrefix(str, "\x1b<Poke ") {
			a = sender
			fmt.Sscanf(str, "\x1b<Poke %d>", &a)
			fmt.Printf("Poking %v in context %v.\n", a, context)
			if context < 0 {
				Instance.SendGroupPoke(-context, a)
			} else {
				Instance.SendFriendPoke(context)
				if a != context {
					fmt.Printf("Can only poke %v in a friend context; this is a limitation in MiraiGo.\n", context)
				}
			}
		} else if _, err := fmt.Sscanf(str, "\x1b<Context change %d>", &a); err == nil {
			context = a
			if context < 0 && Instance.FindGroup(-a) == nil || context >= 0 && Instance.FindFriend(a) == nil {
				context = Instance.Uin
				fmt.Printf("A context change to %v was redirected to the bot itself because the context is not found.\n", a)
			}
		} else if strings.HasPrefix(str, "\x1b<Resource::Audio ") {
			fmt.Printf("Uploading voice data.\n")
			//TODO
		} else if str != "" {
			msg, newStr := parseStringAsMessageChain(sender, context, messageTime, messageIds, str)
			if context < 0 {
				if gm := Instance.SendGroupMessage(-context, &message.SendingMessage{Elements: msg}); gm != nil {
					fmt.Printf("SendGroupMessage to %v: %q.\n", gm.GroupCode, gm.ToString())
				} else {
					fmt.Printf("SendGroupMessage failed mysteriously.\n")
				}
			} else {
				if pm := Instance.SendPrivateMessage(context, &message.SendingMessage{Elements: msg}); pm != nil {
					fmt.Printf("SendPrivateMessage to %v: %q.\n", pm.Target, pm.ToString())
				} else {
					fmt.Printf("SendPrivateMessage failed mysteriously.\n")
				}
			}
			str = newStr
			continue
		}
		_, str, _ = strings.Cut(str, "\x1e")
	}
}

func parseStringAsMessageChain(sender, context int64, messageTime int32, messageIds []int32, s string) (msg []message.IMessageElement, str string) {
	str = strings.ReplaceAll(s, "\x1b<Revision>", fmt.Sprintf("%d.go", revision))
	var forwardMessageStack []*message.ForwardMessage
	var forwardMessageMetadataPending bool
	var forwardMessageSenderId = Instance.Uin
	var forwardMessageSenderName string
	var forwardMessageTime = messageTime
	if forwardMessageTime == 0 {
		forwardMessageTime = int32(time.Now().Unix())
	}
	for {
		escapeIndex := strings.IndexAny(str, "\x1b\x1e")
		if escapeIndex < 0 {
			if str != "" {
				msg = append(msg, message.NewText(str))
				str = ""
			}
			break
		} else if escapeIndex > 0 {
			if len(forwardMessageStack) != 0 && forwardMessageMetadataPending {
				forwardMessageMetadataPending = false
				substrings := strings.SplitN(str, "\n", 4)
				forwardMessageSenderId = 0
				fmt.Sscan(substrings[0], forwardMessageSenderId)
				if forwardMessageSenderId == 0 {
					forwardMessageSenderId = Instance.Uin
				}
				forwardMessageSenderName = substrings[1]
				var dt int32
				fmt.Sscan(substrings[2], dt)
				if dt < 114514 {
					forwardMessageTime += dt
				} else {
					forwardMessageTime = dt
				}
				str = substrings[3]
			} else {
				msg = append(msg, message.NewText(str[:escapeIndex]))
				str = str[escapeIndex:]
			}
			continue
		} else {
			// 用于fmt.Sscanf的临时变量。
			var a int64
			if str[0] == '\x1e' {
				str = str[1:]
				if len(forwardMessageStack) == 0 {
					break
				} else {
					if len(msg) != 0 {
						forwardMessageStack[len(forwardMessageStack)-1].AddNode(&message.ForwardNode{
							SenderId:   forwardMessageSenderId,
							SenderName: forwardMessageSenderName,
							Message:    msg,
							Time:       forwardMessageTime,
						})
					}
					msg = nil
					forwardMessageMetadataPending = true
				}
				continue
			} else if strings.HasPrefix(str, "\x1b<Mention everyone>") && context < 0 {
				msg = append(msg, message.AtAll())
			} else if _, err := fmt.Sscanf(str, "\x1b<Mention %d>", &a); err == nil && context < 0 {
				if a == 0 {
					a = Instance.Uin
				}
				if group := Instance.FindGroup(-context); group != nil {
					display := fmt.Sprintf("@%v", a)
					if member := group.FindMember(a); member != nil {
						display = "@" + member.DisplayName()
					}
					msg = append(msg, message.NewAt(a, display))
				}
			} else if strings.HasPrefix(str, "\x1b<Quote>") {
				msg = append(msg, &message.ReplyElement{
					ReplySeq: messageIds[0],
					Sender:   sender,
					Time:     messageTime,
					Elements: []message.IMessageElement{message.NewText("…")},
				})
			} else if strings.HasPrefix(str, "\x1b<Begin quote ") {
				var ids []int32
				for _, s := range strings.Split(str[14:strings.IndexRune(str, '>')], ",") {
					var id int32
					fmt.Sscan(s, &id)
					ids = append(ids, id)
				}
				var contents string
				contents, str, _ = strings.Cut(str[strings.IndexRune(str, '>')+1:], "\x1b<End>")
				msg = append(msg, &message.ReplyElement{
					ReplySeq: ids[0],
					Sender:   sender,
					Time:     messageTime,
					Elements: []message.IMessageElement{message.NewText(contents)},
				})
			} else if strings.HasPrefix(str, "\x1b<Rich message::Xiaochengxu>") {
				msg = append(msg, message.NewLightApp(str[strings.IndexRune(str, '>')+1:]))
				str = ""
			} else if strings.HasPrefix(str, "\x1b<Rich message::Service JSON>") {
				msg = append(msg, message.NewRichJson(str[strings.IndexRune(str, '>')+1:]))
				str = ""
			} else if strings.HasPrefix(str, "\x1b<Rich message::Service XML>") {
				msg = append(msg, message.NewRichXml(str[strings.IndexRune(str, '>')+1:], 60))
				str = ""
			} else if strings.HasPrefix(str, "\x1b<Begin scope ") {
				/*scopeContextId = str.substringBefore('>').substringAfter("scope ").toLongOrNull()
				scopeContext = getContactFromAlgebraicId(context.bot, scopeContextId) ?: context.bot.asFriend
				strategy = object : ForwardMessage.DisplayStrategy {
					val preamble = str.substringBefore('\x1e', "").substringAfter('>').split('\n')
					override fun generateTitle(forward: RawForwardMessage) = preamble.first()
					override fun generatePreview(forward: RawForwardMessage) =
						if preamble.size < 2) preamble else preamble.subList(1, preamble.size - 1)
					override fun generateSummary(forward: RawForwardMessage) = preamble.last()
				}
				str = '\x1e' + str.substringAfter('\x1e')
				forwardMessageStack.add(ForwardMessageBuilder(scopeContext).apply {
					displayStrategy = strategy
				})
				continue
				*/
			} else if strings.HasPrefix(str, "\x1b<End>") {
				/*forwardMessageStack.removeLastOrNull()?.let {
					if !msg.isContentEmpty() {
						it.add(forwardMessageSenderId, forwardMessageSenderName, msg, forwardMessageTime)
					}
					msg = it.build()
				} ?: println("Nothing to <End>.")*/
			} else if _, err := fmt.Sscanf(str, "\x1b<Emoticon %d>", &a); err == nil {
				msg = append(msg, message.NewFace(int32(a)))
			} else if _, err := fmt.Sscanf(str, "\x1b<Sticker::Dice %d>", &a); err == nil {
				msg = append(msg, message.NewDice((int32(a)-1)%6+1))
			} else if strings.HasPrefix(str, "\x1b<Sticker::") {
				//msg = append(msg, &message.MarketFaceElement{})
				/*
					val name = PlainText(str.substringAfter('>')).serializeToMiraiCode()
					val marketFace = "[mirai:marketface:$it,$name]".deserializeMiraiCode()
					if marketFace.last() !is MarketFace {
						val classes = marketFace.joinToString { it.javaClass.name }
						println("A Sticker cannot be constructed from mirai code (got $classes).")
					}
					msg = marketFace
					str = ""
				*/
			} else if strings.HasPrefix(str, "\x1b<Resource::Image ") {
				if file, err := os.Open(str[strings.IndexRune(str, ' ')+1 : strings.IndexRune(str, '>')]); err == nil {
					defer file.Close()
					// 我甚至不能用绝对值函数来初始化PrimaryID，因为Go没有整数绝对值函数！！
					var target message.Source
					if context < 0 {
						target = message.Source{
							SourceType: message.SourceGroup,
							PrimaryID:  -context,
						}
					} else {
						target = message.Source{
							SourceType: message.SourcePrivate,
							PrimaryID:  context,
						}
					}
					if i, err := Instance.UploadImage(target, file); err == nil {
						msg = append(msg, i)
					} else {
						str = fmt.Sprintf("\u267b\ufe0f UploadImage failed: %v\n%s", err, str[strings.IndexRune(str, '>')+1:])
						continue
					}
				}
			} else if str[0] == '\x1b' {
				seq, _, _ := strings.Cut(str[1:], ">")
				fmt.Printf("Unrecognized escape sequence %s>.\n", seq)
			} else {
				fmt.Printf("Control should not reach here. This is a bug in Tenshit.\n")
			}
			_, str, _ = strings.Cut(str, ">")
		}
	}
	if len(forwardMessageStack) != 0 {
		fmt.Printf("Missing <End> for %v <Begin scope>(s).\n", len(forwardMessageStack))
	}
	return
}
