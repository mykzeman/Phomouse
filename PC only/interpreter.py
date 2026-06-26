from serial import *
from serial.tools import list_ports
import re
import time
# MAKE SURE TO INSTALL phomouse.py from the Phomouse repo in the same directory as this script
import phomouse as pm


BAUD_RATE = 9600
# Command set
COMMANDS = ['[MX]', '[MY]', '[LB]', '[RB]', '[MB]', '[SU]', '[SD]', '[DS]', '[DR]']

def find_available_ports():
    """Returns a list of all currently available COM ports."""
    ports = list_ports.comports()
    available = []
    for p in ports:
        try:
            # Try to open the port to see if it's actually available
            ser = Serial(p.device, BAUD_RATE, timeout=0.1)
            ser.close()
            available.append(p.device)
        except (SerialException, OSError):
            continue
    return available

def receive_data():
    print('--- Phomouse Serial Interpreter ---')
    print(f'Baud Rate: {BAUD_RATE}')
    
    while True:
        available = find_available_ports()
        if not available:
            print('Searching for available COM ports...')
            time.sleep(2)
            continue

        print(f'Detected available ports: {available}')
        com = available[0]
        print(f'Attempting to connect to {com}...')

        try:
            with Serial(com, BAUD_RATE, timeout=5) as ser:
                print(f'Connected to {com}! Listening for Phomouse commands...')
                while True:
                    if ser.in_waiting > 0:
                        data = ser.readline().decode('utf-8', errors='ignore').strip()
                        if data:
                            process_data(data)
                    else:
                        time.sleep(0.001) # Low latency loop
        except Exception as e:
            print(f'Connection lost or error on {com}: {e}')
            time.sleep(1)

def process_data(data: str):
    # Expecting format like: "PMCMD:[MX]-{22},PMCMD:[MY]-{5},PMCMD:[LB]-{1000}"
    try:
        values = data.split(',')
        for value in values:
            if value.startswith('PMCMD:'):
                match = re.match(r'PMCMD:(.*?)-(.*)', value)
                if match:
                    cmd = match.group(1)
                    val_str = match.group(2).strip('{}')
                    
                    if cmd not in COMMANDS:
                        continue
                    
                    try:
                        v = int(val_str)
                    except ValueError:
                        v = 0

                    # Execute actions
                    if cmd == '[MX]':
                        pm.send_mouse_input(v, 0, 0, pm.MOUSEEVENTF_MOVE)
                    elif cmd == '[MY]':
                        pm.send_mouse_input(0, v, 0, pm.MOUSEEVENTF_MOVE)
                    elif cmd == '[LB]':
                        pm.send_mouse_input(0, 0, 0, pm.MOUSEEVENTF_LEFTDOWN)
                        pm.send_mouse_input(0, 0, 0, pm.MOUSEEVENTF_LEFTUP)
                    elif cmd == '[RB]':
                        pm.send_mouse_input(0, 0, 0, pm.MOUSEEVENTF_RIGHTDOWN)
                        pm.send_mouse_input(0, 0, 0, pm.MOUSEEVENTF_RIGHTUP)
                    elif cmd == '[MB]':
                        pm.send_mouse_input(0, 0, 0, pm.MOUSEEVENTF_MIDDLEDOWN)
                        pm.send_mouse_input(0, 0, 0, pm.MOUSEEVENTF_MIDDLEUP)
                    elif cmd == '[SU]':
                        pm.send_mouse_input(0, 0, v, pm.MOUSEEVENTF_WHEEL)
                    elif cmd == '[SD]':
                        pm.send_mouse_input(0, 0, -v, pm.MOUSEEVENTF_WHEEL)
                    elif cmd == '[DS]':
                        pm.send_mouse_input(0, 0, 0, pm.MOUSEEVENTF_LEFTDOWN)
                    elif cmd == '[DR]':
                        pm.send_mouse_input(0, 0, 0, pm.MOUSEEVENTF_LEFTUP)

    except Exception:
        pass

def main():
    receive_data()
if __name__ == '__main__':
    main() 