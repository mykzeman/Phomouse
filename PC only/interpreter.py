import serial as bt
import serial.tools.list_ports
import re
import pyautogui as gui
import time

BAUD_RATE = 9000

# Disable pyautogui failsafe so you can reach the edges of the screen
gui.FAILSAFE = False
# Reduce default delay for smoother movement
gui.PAUSE = 0

# Command set
COMMANDS = ['[MX]', '[MY]', '[LB]', '[RB]', '[MB]', '[SU]', '[SD]', '[DS]', '[DR]']

def find_available_ports():
    """Returns a list of all currently available COM ports."""
    ports = serial.tools.list_ports.comports()
    available = []
    for p in ports:
        try:
            # Try to open the port to see if it's actually available
            ser = bt.Serial(p.device, BAUD_RATE, timeout=0.1)
            ser.close()
            available.append(p.device)
        except (bt.SerialException, OSError):
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
            with bt.Serial(com, BAUD_RATE, timeout=5) as ser:
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
                        gui.moveRel(v, 0, _pause=False)
                    elif cmd == '[MY]':
                        gui.moveRel(0, v, _pause=False)
                    elif cmd == '[LB]':
                        gui.click(button='left')
                    elif cmd == '[RB]':
                        gui.click(button='right')
                    elif cmd == '[MB]':
                        gui.click(button='middle')
                    elif cmd == '[SU]':
                        gui.scroll(v)
                    elif cmd == '[SD]':
                        gui.scroll(-v)
                    elif cmd == '[DS]':
                        gui.mouseDown(button='left')
                    elif cmd == '[DR]':
                        gui.mouseUp(button='left')

    except Exception:
        pass

if __name__ == '__main__':
    receive_data()
