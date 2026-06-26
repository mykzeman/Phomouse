# Adapted from https://stackoverflow.com/questions/4263608/ctypes-mouse-events                      
import ctypes as c
import ctypes.wintypes as win
import struct
import array as ray
MOUSEEVENTF_MOVE=0x0001
MOUSEEVENTF_LEFTDOWN=0x0002
MOUSEEVENTF_LEFTUP=0x0004
MOUSEEVENTF_RIGHTDOWN=0x0008
MOUSEEVENTF_RIGHTUP=0x0010
MOUSEEVENTF_MIDDLEDOWN=0x0020
MOUSEEVENTF_MIDDLEUP=0x0040
MOUSEEVENTF_WHEEL=0x0800
MOUSEEVENTF_ABSOLUTE=0x8000
INPUT_MOUSE=0x0000
ULONG_PTR=c.c_size_t
class MOUSEINPUT(c.Structure):
    _fields_=[("dx", win.LONG),
              ("dy", win.LONG),
              ("mouseData", win.DWORD),
              ("dwFlags", win.DWORD),
              ("time", win.DWORD),
              ("dwExtraInfo", ULONG_PTR)]
class DUMMYUNIONNAME(c.Union):
    _fields_=[("mi", MOUSEINPUT)]
class INPUT(c.Structure):
    _anonymous_ = ["u"]
    _fields_=[("type", win.DWORD),
              ("u", DUMMYUNIONNAME)]
    
def nozero(result,func,arguments):
    if not result:
        raise c.WinError(c.GetLastError())
    return result
user32=c.WinDLL("user32",use_last_error=True)
send_clicks=user32.SendInput
send_clicks.errcheck=nozero
send_clicks.argtypes=(win.UINT, c.POINTER(INPUT), c.c_int)
send_clicks.restype=win.UINT
def send_mouse_input(dx,dy,mouseData,dwFlags,time=0,dwExtraInfo=0):
    mi=MOUSEINPUT(dx,dy,mouseData,dwFlags,time,dwExtraInfo)
    clicks=INPUT(INPUT_MOUSE,DUMMYUNIONNAME(mi))
    return send_clicks(1, c.byref(clicks), c.sizeof(clicks))