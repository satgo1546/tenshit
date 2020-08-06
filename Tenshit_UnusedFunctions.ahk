/*
	Function: RegionWaitChange
		Waits for a region of a window to change.
	License:
		- Version 1.01 <http://www.autohotkey.net/~polyethene/#regionwaitchange>
		- Dedicated to the public domain (CC0 1.0) <http://creativecommons.org/publicdomain/zero/1.0/>
	Parameters:
		x, y - coordinates relative to window
		w, h - size of the region to monitor (default: 1 and 1 respectively)
		t - window title (default: Last Found Window <http://www.autohotkey.com/docs/LastFoundWindow.htm>)
		f - check frequency in milliseconds (default: 500)
		s - maximum image buffer size (default: 64 MiB)
		inv - *reserved*
*/
RegionWaitChange(x, y, w = 1, h = 1, t = "", f = 500, s = 67108864, inv = false) {
	hwnd := WinExist(t)
	lhc := ""
	Loop {
		hc := DCCBitmapHash(hwnd, x, y, w, h, s)
		If ((inv ? hc == lhc : hc != lhc) and lhc != "") {
			Return true
		}
		lhc := hc
		Sleep f
	}
}

/*
	Function: RegionWaitStatic
		Identical to the previous function except waits until the specified region stops changing.
*/
RegionWaitStatic(x, y, w = 1, h = 1, t = "", f = 1000, s = 67108864) {
	RegionWaitChange(x, y, w, h, t, f, s, true)
}

/*
	Function: DCCBitmapHash
		Returns an MD5 hash of a window region.
	Parameters:
		hwnd - window handle
		x, y - coordinates relative to window
		w, h - size of region
		s - maximum image buffer size (default: 64 MiB)
*/
DCCBitmapHash(hwnd, x, y, w = 1, h = 1, s = 67108864) {
	hdc := DllCall("GetDC", "UInt", hwnd)
	hcdc := DllCall("CreateCompatibleDC", "UInt", hdc)

	hbmp := DllCall("CreateCompatibleBitmap", "UInt", hdc, "UInt", w, "UInt", h)
	DllCall("SelectObject", "UInt", hcdc, "UInt", hbmp)

	DllCall("BitBlt", "UInt", hcdc, "Int", 0, "Int", 0, "Int", w, "Int", h
		, "UInt", hdc, "Int", x, "Int", y, "UInt", 0x00CC0020) ; SRCCOPY

	sz := VarSetCapacity(bits, s, 0)
	sz := DllCall("GetBitmapBits", "UInt", hbmp, "UInt", sz, "UInt", &bits)

	DllCall("DeleteObject", "UInt", hbmp)
	DllCall("DeleteDC", "UInt", hcdc)
	DllCall("ReleaseDC", "UInt", hwnd, "UInt", hdc)

	Return MD5(bits, sz)
}

MD5(ByRef V, L = 0) { ; www.autohotkey.com/forum/viewtopic.php?p=275910
	VarSetCapacity(MD5_CTX, 104, 0)
	DllCall("advapi32\MD5Init", "Str", MD5_CTX)
	DllCall("advapi32\MD5Update", "Str", MD5_CTX, "Str", V, "UInt", L ? L : StrLen(V))
	DllCall("advapi32\MD5Final", "Str", MD5_CTX)
	MD5 := ""
	Loop % StrLen(Hex := "123456789ABCDEF0") {
		N := NumGet(MD5_CTX, 87 + A_Index, "Char")
		MD5 .= SubStr(Hex, N >> 4, 1) . SubStr(Hex, N & 15, 1)
	}
	Return MD5
}
