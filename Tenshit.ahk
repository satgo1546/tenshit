; Tenshit! Dice (迫真) 木鼠子 Ver.

; 使用方法：
; 使用 Window Spy 测量下列坐标。
; ——单击后能让焦点转移到收到的聊天文字信息的窗口（RXD）的鼠标坐标
; ——单击后能让焦点转移到发送聊天文字信息的窗口（TXD）的鼠标坐标
; ——单击后能选中（CS）最新聊天的鼠标坐标
; 也就是说，聊天窗口中主要的上下两部分分别对应于 RXD 和 TXD。将坐标值写入代码中对应的赋值语句处。在 Window Spy 报告的坐标中，请注意观察带有“Client”字样的。
; 启动本脚本，在聊天窗口中按 Ctrl + F9 运行。
; 任何时候都可按 Pause/Break 键结束运行。
; 若脚本运行中途活动窗口发生变化，将暂停执行。活动窗口恢复数秒后，会自动继续。
; 在运行过程中请不要改变聊天窗口的大小，这会使测量得到的坐标失效。

; 建议设置：
; 按 Enter 键发送消息。
; 允许来消息时自动弹出窗口。
; 关闭所有广告弹窗。
; 不允许接收窗口抖动。
; 自动登录，并在 Tenshit_RunQQ.ahk 中设置用于启动 QQ 的命令行，以便在崩溃时自动恢复。在本脚本中设置从 QQ 主面板转到聊天窗口的方法。

#NoEnv
#Warn
SendMode Input
SetWorkingDir %A_ScriptDir%
FileEncoding UTF-8-RAW

GroupAdd IM, ahk_class TXGuiFoundation
GroupAdd IM, ahk_exe QQ.exe
GroupAdd IM, ahk_exe TIM.exe

CoordMode Mouse, Client
CoordMode ToolTip, Client
SetDefaultMouseSpeed 0
#Include Tenshit_Settings.ahk

Random my_rand_seed
processed_message_count := 0
daemon_pid := 0
daemon_hwnd := 0
FileGetTime daemon_log_mtime, TenshitLog.csv, M
If (ErrorLevel) {
	daemon_log_mtime := 0
}
SetTimer daemon_log, 300000
OnExit("daemon_on_exit")
daemon_start_time := A_Now
daemon_log("Starting.")

Pause::
	ToolTip, Tenshit 将在 3 秒后退出。, TXD_X, TXD_Y, 20
	SetTimer, QUIT, -3000
	Return

QUIT:
	ExitApp
	Return

; 注意，此随机数生成器比较烂。
my_rand() {
	global my_rand_seed
	my_rand_seed := Mod(my_rand_seed * 48271, 0x7fffffff) & 0xffffffff
	Return my_rand_seed
}

last_message(raw_clip) {
	global last_message_text, last_message_user
	last_message_text := ""
	last_message_user := ""
	pos4 := StrLen(raw_clip)
	Loop 100 {
		pos1 := InStr(raw_clip, "`r`n", true, -1, A_Index)
		If (pos1 == 0) {
			Break
		}
		pos2 := InStr(raw_clip, "`r`n", true, -1, A_Index + 1)
		If (pos2 == 0) {
			pos2 := -1
		}
		FormatTime str1, , ShortDate
		pos3 := InStr(raw_clip, str1, true, pos2 + 2, 1)
		If (pos3 > pos2 && pos3 < pos1) {
			text := SubStr(raw_clip, pos1 + 2, pos4 - (pos1 + 2))
			user := SubStr(raw_clip, pos2 + 2, (pos3 - 1) - (pos2 + 2))
			If (user ~= "S)小沙子|小舞|あきは") {
				pos4 := pos2 + 2
				Continue
			}
			last_message_text := text
			last_message_user := user
			Return SubStr(raw_clip, pos2 + 2, pos4 - (pos2 + 2))
		}
	}
	Return ""
}

respond() {
	local
	response := respond_without_size_limit()
	If (StrLen(response) > 2000) {
		response := "产生了一个很大的输出。例：`r`n"
			. SubStr(response, 1, 50)
			. " ≪" . (StrLen(response) - 100) . "≫ "
			. SubStr(response, StrLen(response) - 49)
	}
	Return response
}

respond_without_size_limit() {
	global last_message_text, last_message_user, last_message_context
	global processed_message_count, my_rand_seed
	global daemon_start_time, daemon_log_mtime
	static help_cooldown := 0, help_warning := 1
	processed_message_count += 1
	text := last_message_text
	user := last_message_user
	context := last_message_context

	text := RegExReplace(text, "\s*@木鼠子(\s+|$)")
	text := Trim(text, " `n`t`r")
	If (SubStr(user, 1, 1) == "【") {
		pos := InStr(user, "】")
		If (pos > 1 && pos < StrLen(user)) {
			user := SubStr(user, pos + 1)
		}
	}

	If (text ~= "i)^[.。]help$") {
		;If (A_TickCount > help_cooldown) {
			help_cooldown := A_TickCount + 600000
			help_warning := 1
			Return "Tenshit! Dice 木鼠子 Ver. 软体不稳定`r`n"
				. "因服务器网络原因，延迟可高达 3 分钟。`r`n"
				. "只支持下列命令：`r`n"
				. "‣ .help (帮助)`r`n"
				. "‣ .r (掷骰·计算)`r`n"
				. "‣ .deck (抽取牌堆)`r`n"
				. "‣ .jrrp (今日人品)`r`n"
				. "‣ .i (翻转棋) (β)"
		;} Else {
		;	If (help_warning) {
		;		help_warning := 0
		;		Return "爬楼，请。"
		;	}
		;}
	} Else If (text ~= "i)^[.。]jrrp$") {
		seed := 0
		Loop % StrLen(user) {
			seed ^= Asc(SubStr(user, A_Index, 1)) * A_Index
		}
		seed := A_YYYY . seed . A_YDay
		my_rand_seed := seed
		Return "[" . user . "] 的今日人品为 " . Mod((my_rand() >> 2) // 17, 101) . "。"
	} Else If (text ~= "i)^[.。]r") {
		Return "[" . user . "] 掷出了 " . eval(Trim(SubStr(text, 3)), true) . "。"
	} Else If (text ~= "i)^[.。]deck") {
		filename := Trim(SubStr(text, 6))
		If (filename == "") {
			str := "抽取牌堆。"
			Loop Files, *.txt
			{
				str .= "`r`n‣ .deck " . SubStr(A_LoopFileName, 1, StrLen(A_LoopFileName) - 4)
			}
			Return str
		} Else {
			filename .= ".txt"
		}
		If (!FileExist(filename)) {
			Return "不存在名为 " . filename . " 的牌堆文件。"
		}
		Try {
			FileRead str, %filename%
		} Catch e {
			Return RTrim("在读取 " . filename . " 时发生了下列错误。`r`n" . e.Message, " `n`t`r")
		}
		str := StrSplit(RTrim(str, " `n`t`r"), "`r`n")
		Random a, 1, str.Length()
		str := StrReplace(str[a], "\r\n", "`r`n")
		Return "[" . user . "]`r`n" . str
	} Else If (text ~= "i)^[.。](?:i\s*\d{0,4}$|txssb)") {
		Clipboard := user . "`r`n" . context . "`r`n" . text
		RunWait TenshitAux.exe, , Min UseErrorLevel
		If (ErrorLevel) {
			Return "执行辅助程序时返回了 " . ErrorLevel . "。错误代码是 " . A_LastError . "。"
		}
		Return "#<Clipboard data>"
	} Else If (text ~= "^\.prtscr$") {
		Send {PrintScreen}
		Return "#<Clipboard data>"
	} Else If (text ~= "^\.debug\s+s$") {
		; A_TickCount is 32-bit.
		; The return type is really UInt64, but it is not supported.
		str := "Tenshit! Dice 木鼠子 Ver."
		FormatTime str1, , yyyy 年 M 月 d 日 HH:mm
		str .= "`r`n现在 = " . str1
		str .= "`r`n服务器运行时间 = "  . format_timespan(DllCall("kernel32\GetTickCount64", "Int64") // 1000)
		str2 := A_Now
		EnvSub str2, %daemon_start_time%, Seconds
		str .= "`r`n框架运行时间 = " . format_timespan(str2)
		str .= "`r`n总计处理消息数 = " . processed_message_count
		str3 := A_Now
		EnvSub str3, %daemon_log_mtime%, Seconds
		str .= "`r`n日志最后写入时间 = " . format_timespan(str3) . "前"
		memory_status := ""
		VarSetCapacity(memory_status, 64, 0)
		NumPut(64, memory_status, "UInt")
		If (DllCall("Kernel32.dll\GlobalMemoryStatusEx", "Ptr", &memory_status)) {
			memory_status := NumGet(memory_status, 4, "UInt") . "%"
		} Else {
			memory_status := "错误 " . A_LastError
		}
		str .= "`r`n内存使用量 = " . memory_status
		DriveSpaceFree str4, %A_WorkingDir%
		str .= "`r`n卷剩余空间 = " . str4 . "MB"
		Return str
	}
	Return ""
}

parse_number(str, value_if_empty = 0) {
	If str is space
		Return value_if_empty
	If str is float
		Return str
	pos := RegExMatch(str, "[亿億]")
	If (pos) {
		Return parse_number(SubStr(str, 1, pos - 1), 1) * 100000000 + parse_number(SubStr(str, pos + 1))
	}
	pos := InStr(str, "万")
	If (pos) {
		Return parse_number(SubStr(str, 1, pos - 1), 1) * 10000 + parse_number(SubStr(str, pos + 1))
	}
	pos := InStr(str, "千")
	If (pos) {
		Return parse_number(SubStr(str, 1, pos - 1), 1) * 1000 + parse_number(SubStr(str, pos + 1))
	}
	pos := InStr(str, "百")
	If (pos) {
		Return parse_number(SubStr(str, 1, pos - 1), 1) * 100 + parse_number(SubStr(str, pos + 1))
	}
	pos := InStr(str, "十")
	If (pos) {
		Return parse_number(SubStr(str, 1, pos - 1), 1) * 10 + parse_number(SubStr(str, pos + 1))
	}
	str := RegExReplace(str, "[零〇]", "0")
	str := StrReplace(str, "一", "1")
	str := StrReplace(str, "二", "2")
	str := StrReplace(str, "三", "3")
	str := StrReplace(str, "四", "4")
	str := StrReplace(str, "五", "5")
	str := StrReplace(str, "六", "6")
	str := StrReplace(str, "七", "7")
	str := StrReplace(str, "八", "8")
	str := StrReplace(str, "九", "9")
	str := StrReplace(str, "点", ".")
	Return str
}

eval(text, verbose) {
	If (StrLen(text) > 999) {
		Return "算死了"
	}
	If (text == "") {
		text := "1d100"
	}
	text := StrReplace(text, "D", "d")
	text := RegExReplace(text, "加上?", "+")
	text := RegExReplace(text, "[减-]去?", "−")
	text := RegExReplace(text, "[乘\*]以?", "×")
	text := RegExReplace(text, "除以|/", "÷")
	text := StrReplace(text, "的平方", "^2")
	text := StrReplace(text, "的立方", "^3")
	Loop {
		pos1 := InStr(text, "的")
		If (pos1) {
			pos2 := InStr(text, "次方", true, pos1)
			If (pos2) {
				text := StrReplace(text, "的", "^(", , 1)
				text := StrReplace(text, "次方", ")", , 1)
			} Else {
				Break
			}
		} Else {
			Break
		}
	}
	log := text
	regex_integer := "\d+\.?"
	regex_number := "\d+(?:\.\d*)?"
	Loop {
		If (RegExMatch(text, "O)[亿億万千百十点零一二三四五六七八九]+", match)) {
			r := parse_number(match.Value(0))
		;(RegExMatch(text, "O)\(([^()]++|(?R))*\)", match)) {
		} Else If (RegExMatch(text, "O)(" . regex_number . ")\(([^()]+?)\)", match)) {
			r := "(" . match.Value(1) . " × " . eval(match.Value(2), false) . ")"
		} Else If (RegExMatch(text, "O)\(([^()]+?)\)", match)) {
			r := eval(match.Value(1), false)
		} Else If (RegExMatch(text, "O)(" . regex_number . ")\s*\^\s*(" . regex_number . ")", match)) {
			r := match.Value(1) ** match.Value(2)
		} Else If (RegExMatch(text, "O)(" . regex_integer . ")?\s*d\s*(" . regex_number . ")?", match)) {
			a := Trim(match.Value(1))
			b := Trim(match.Value(2))
			If (!a || !b) {
				If (!a) {
					a := 1
				}
				If (!b) {
					b := 100
				}
				r := a . "d" . b
			} Else If (a > 999) {
				r := "算死了"
			} Else If (a > 2 && a < 10) {
				Random c, 1, b
				r := "(" . c
				Loop % match.Value(1) - 1 {
					Random c, 1, b
					r .= " + " . c
				}
				r .= ")"
			} Else {
				r := 0
				Loop % a {
					Random c, 1, b
					r += c
				}
			}
		} Else If (RegExMatch(text, "O)(" . regex_number . ")\s*([×÷])\s*(" . regex_number . ")", match)) {
			If (match.Value(2) == "×") {
				r := match.Value(1) * match.Value(3)
			} Else {
				r := match.Value(1) / match.Value(3)
			}
		} Else If (RegExMatch(text, "O)(" . regex_number . ")\s*([+−])\s*(" . regex_number . ")", match)) {
			If (match.Value(2) == "+") {
				r := match.Value(1) + match.Value(3)
			} Else {
				r := match.Value(1) - match.Value(3)
			}
		} Else {
			Break
		}
		text := SubStr(text, 1, match.Pos(0) - 1) . r . SubStr(text, match.Pos(0) + match.Len(0))
		text := RegExReplace(text, "\.0+(?!\d)", "")
		log := log . "`r`n= " . text
	}
	If (InStr(log, "`r`n", true, 1, 2) == 0) {
		log := StrReplace(log, "`r`n", " ")
	}
	Return verbose ? log : text
}

format_timespan(seconds) {
	r := ""
	If (seconds > 86400) {
		r .= seconds // 86400 . " 天"
		seconds := Mod(seconds, 86400)
	}
	If (seconds > 3600) {
		r .= " " . seconds // 3600 . " 小时"
		seconds := Mod(seconds, 3600)
	}
	If (seconds > 60) {
		r .= " " . seconds // 60 . " 分"
		seconds := Mod(seconds, 60)
	}
	r .= " " . seconds . " 秒"
	Return Trim(r)
}

!F5::
	;InputBox last_message_user, 测试对消息的响应, 用户名, , 300, 120, , , , , 用户
	;If (ErrorLevel != 0) {
	;	Return
	;}
	last_message_user := "用户"
	last_message_context := "上下文"
	InputBox last_message_text, 测试对消息的响应, %last_message_user% %A_YYYY%-%A_MM%-%A_DD% %A_Hour%:%A_Min%:%A_Sec%, , 600, 120
	If (ErrorLevel == 0) {
		response := respond()
		If (response == "") {
			MsgBox 48, 测试对消息的响应, 没有回复。
		} Else {
			MsgBox 64, 测试对消息的响应, %response%
		}
	}
	Return

!F7::
	Reload
	Return

^F9::
	If (!WinActive("ahk_group IM")) {
		Return
	}
	daemon_hwnd := WinExist("A")
	WinGet daemon_pid, PID, A
	daemon_log("Starting to process messages.")
outer:
	Loop {
		last_last_message := ""
		Loop {
			WinGetTitle last_window_title, A
			Click %CS_X%, %CS_Y%
			Sleep 100
			If (daemon())
				Continue outer
			WinGetTitle curr_window_title, A
			If (last_window_title != curr_window_title) {
				Sleep 500
			}
			Clipboard := ""
			If (daemon())
				Continue outer
			Click %RXD_X%, %RXD_Y%
			Sleep 200
			If (daemon())
				Continue outer
			Send ^a^c
			ClipWait 0.8
			If (daemon())
				Continue outer
			curr_last_message := last_message(Clipboard)
			last_message_context := RegExReplace(curr_window_title, "等\d+个会话$", "")
		} Until last_last_message != curr_last_message
		response := respond()
		If (response != "") {
			If (daemon())
				Continue outer
			Click %TXD_X%, %TXD_Y%
			Send ^a{Delete}
			temp_clipboard := ClipboardAll
			While response != "" {
				If (daemon())
					Continue outer
				response := partial_send(response)
			}
			Send {Enter}
		}
		Sleep 1000
	}
	Return

partial_send(response) {
	global temp_clipboard
	If (RegExMatch(response, "O)#<([ A-Za-z0-9]+)>", match)) {
		If (match.Pos(0) > 1) {
			Clipboard := SubStr(response, 1, match.Pos(0) - 1)
			Send ^v
		}
		Switch match.Value(1) {
		Case "Clipboard data":
			Clipboard := temp_clipboard
			Send ^v
		Case "Send":
			Send {Enter}
			Sleep 100
		}
		response := SubStr(response, match.Pos(0) + match.Len(0))
	} Else {
		Clipboard := response
		Send ^v
		response := ""
	}
	Return response
}

daemon() {
	global daemon_pid, daemon_exe, daemon_hwnd
	Process Exist, %daemon_pid%
	If (!ErrorLevel) {
		Process Close, QQ.exe
		Process Close, TIM.exe
		Process Close, TXPlatform.exe
		Process Close, TxBugReport.exe
		Process Close, Timwp.exe
		Sleep 1000
		daemon_log("The process (PID " . daemon_pid . ") no longer exists. (Restarting.)")
		Loop {
			Try {
				Run Tenshit_RunQQ.bat
				Break
			} Catch e {
				daemon_log("Restart was faced with difficulty: " . e.message . " (Trying again.)")
			}
		}
		Sleep 29000
		WinGetActiveTitle window_title
		If (window_title == "QQ") {
			Click 235, 174
			Sleep 5000
			Click 110, 205
			Sleep 5000
			Click 110, 283, 2
			Sleep 5000
		} Else If (window_title == "异常关闭恢复") {
			WinClose 异常关闭恢复
		}
		If (WinExist("个会话")) {
			WinActivate 个会话
		}
		WinMaximize A
		WinGet daemon_pid, PID, A
		daemon_hwnd := WinExist("A")
		Return true
	}
	If (WinExist("A") != daemon_hwnd) {
		daemon_log("The focused window has changed. (Changing it back.)")
	}
	WinActivate ahk_id %daemon_hwnd%
	WinMaximize A
	Return false
}

daemon_log(event = "") {
	static MEMORYSTATUSEX, init := VarSetCapacity(MEMORYSTATUSEX, 64, 0) && NumPut(64, MEMORYSTATUSEX, "UInt")
	global processed_message_count
	static last_processed_message_count := 0
	global daemon_pid, daemon_exe, daemon_hwnd
	global daemon_log_mtime
	f := FileOpen("TenshitLog.csv", "a `n", "UTF-8") ; with BOM
	If (!IsObject(f)) {
		Return
	}
	daemon_log_mtime := A_Now
	f.Write(A_Now . "," . event)
	f.Write("," . processed_message_count - last_processed_message_count)
	last_processed_message_count := processed_message_count
	f.Write("," . daemon_pid)
	f.Write("," . daemon_hwnd)
	If (DllCall("Kernel32.dll\GlobalMemoryStatusEx", "Ptr", &MEMORYSTATUSEX)) {
		dwMemoryLoad := NumGet(MEMORYSTATUSEX, 4, "UInt")
		ullTotalPhys := NumGet(MEMORYSTATUSEX, 8, "UInt64")
		ullAvailPhys := NumGet(MEMORYSTATUSEX, 16, "UInt64")
		ullTotalPageFile := NumGet(MEMORYSTATUSEX, 24, "UInt64")
		ullAvailPageFile := NumGet(MEMORYSTATUSEX, 32, "UInt64")
		ullTotalVirtual := NumGet(MEMORYSTATUSEX, 40, "UInt64")
		ullAvailVirtual := NumGet(MEMORYSTATUSEX, 48, "UInt64")
		f.Write("," . dwMemoryLoad . "," . ullTotalPhys . "," . ullAvailPhys . "," . ullTotalPageFile . "," . ullAvailPageFile . "," . ullTotalVirtual . "," . ullAvailVirtual)
	} Else {
		f.Write(",ERROR " . A_LastError . ",,,,,,")
	}
	f.Write("`n")
	f.Close()
}

daemon_on_exit(reason, code) {
	daemon_log("Exiting for " . reason . ".")
}
