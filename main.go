package main

import (
	"bufio"
	"bytes"
	"fmt"
	_ "image/png"
	"io/ioutil"
	"os"
	"os/signal"
	"strings"
	"time"

	"github.com/Mrs4s/MiraiGo/binary"
	"github.com/Mrs4s/MiraiGo/client"
	"github.com/Mrs4s/MiraiGo/message"

	"github.com/skip2/go-qrcode"
	tuotoo "github.com/tuotoo/qrcode"
)

const revision = 1

// 读取文件
// 读取失败返回 nil
func ReadFile(path string) []byte {
	bytes, err := ioutil.ReadFile(path)
	if err != nil {
		fmt.Printf("unable to read '%v'\n", path)
		return nil
	}
	return bytes
}

// FileExist 判断文件是否存在
func FileExist(path string) (bool, error) {
	_, err := os.Stat(path)
	if err == nil {
		return true, nil
	}
	if os.IsNotExist(err) {
		return false, nil
	}
	return true, err
}

// bot实例
var Instance *client.QQClient

func QrcodeLogin() error {
	rsp, err := Instance.FetchQRCode()
	if err != nil {
		return err
	}
	fi, err := tuotoo.Decode(bytes.NewReader(rsp.ImageData))
	if err != nil {
		return err
	}
	os.WriteFile("qrcode.png", rsp.ImageData, 0o644)
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

var console = bufio.NewReader(os.Stdin)

func readLine() string {
	str, _ := console.ReadString('\n')
	str = strings.TrimSpace(str)
	return str
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
			return QrcodeLogin()
		case client.NeedCaptcha:
			_ = os.WriteFile("captcha.jpg", res.CaptchaImage, 0o644)
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
	fmt.Printf("This is TenshitMiraiGo, revision %d.\n", revision)
	// 生成随机设备信息
	if _, err := os.Stat("device.json"); err == nil {
		fmt.Printf("Reusing existing device.json.\n")
	} else {
		client.GenRandomDevice()
		if err := ioutil.WriteFile("device.json", client.SystemDeviceInfo.ToJson(), os.FileMode(0644)); err != nil {
			fmt.Printf("Unable to write device.json.\n")
		}
	}
	// 使用 device.json 初始化设备信息
	Instance = client.NewClient(int64(0), "114514")
	if err := client.SystemDeviceInfo.ReadJson(ReadFile("device.json")); err != nil {
		fmt.Printf("Can't parse device.json.\n")
	}

	// 处理各种事件
	Instance.PrivateMessageEvent.Subscribe(func(_ *client.QQClient, event *message.PrivateMessage) {
		fmt.Printf("私信%v %v %v %v %v %v %v %v\n", event.Sender.Uin, event.Sender.Nickname, event.Sender.CardName, event.Id, event.InternalId, event.Target, event.Time, event.ToString())
	})
	Instance.GroupMessageEvent.Subscribe(func(_ *client.QQClient, event *message.GroupMessage) {
		fmt.Printf("群。%v %v %v %v %v %v %v %v %v\n", event.GroupName, event.Sender.Nickname, event.Sender.CardName, event.Id, event.InternalId, event.GroupCode, event.Sender.Uin, event.Time, event.ToString())
	})
	Instance.FriendMessageRecalledEvent.Subscribe(func(_ *client.QQClient, event *client.FriendMessageRecalledEvent) {
		fmt.Printf("好友撤回%v %v\n", event.FriendUin, event.MessageId)
	})
	Instance.GroupMessageRecalledEvent.Subscribe(func(client *client.QQClient, event *client.GroupMessageRecalledEvent) {
		fmt.Printf("群撤回 %v %v %v %v %v\n", event.AuthorUin, event.GroupCode, event.MessageId, event.Time, event.OperatorUin)
	})
	Instance.DisconnectedEvent.Subscribe(func(_ *client.QQClient, event *client.ClientDisconnectedEvent) {
		fmt.Printf("断了……原因是：%v\n", event.Message)
	})

	// 登录
	client.SystemDeviceInfo.Protocol = client.AndroidWatch
	if token, err := os.ReadFile("session.token"); err == nil {
		// 存在token缓存的情况快速恢复会话
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
		// 不存在token缓存 走正常流程
		if Instance.Uin != 0 {
			// 有账号就先普通登录
			if res, err := Instance.Login(); err == nil {
				loginResponseProcessor(res)
			} else {
				fmt.Printf("Login failed: %v.\n", err)
			}
		} else {
			// 没有账号就扫码登录
			QrcodeLogin()
		}
	}

	// RefreshList 刷新联系人
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

	// 主循环
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, os.Interrupt)
	<-ch
	// 结束时保存会话缓存
	os.WriteFile("session.token", Instance.GenToken(), 0o644)
	println("Token saved.")
}
