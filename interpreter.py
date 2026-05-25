import serial as bt
import re
import pyautogui as gui

COM_PORT = 'COM5'
BAUD_RATE = 900

# Disable pyautogui failsafe so you can reach the edges of the screen
gui.FAILSAFE = False

# We've split MV into MX (Move X) and MY (Move Y)
COMMANDS = ['[MX]', '[MY]', '[LB]', '[RB]', '[MB]', '[SU]', '[SD]', '[DS]', '[DR]']

def receive_data():
    try:
        # Added a timeout so the script doesn't lock up if the connection drops
        with bt.Serial(COM_PORT, BAUD_RATE, timeout=0.1) as ser:
            print(f"Successfully connected to {COM_PORT}! Listening for Phomouse...")
            while True:
                if ser.in_waiting > 0:
                    data = ser.readline().decode('utf-8').strip()
                    if data:
                        process_data(data)
    except Exception as e:
        print(f"Serial connection failed for {COM_PORT}: {e}")
        print("Troubleshooting steps:")
        print("  1. Open Device Manager and confirm the COM port exists.")
        print("  2. Make sure the phone or Bluetooth serial device is connected and powered on.")
        print("  3. If the port number changed, update COM_PORT in the script.")
        print("  4. Restart the app and try again.")

def process_data(data: str):
    # Expecting format like: "PMCMD:[MX]-10,PMCMD:[MY]--5,PMCMD:[LB]-0"
    try:
        values = data.split(',')
        for value in values:
            if value.startswith('PMCMD:'):
                match = re.match(r'PMCMD:(.*?)-(.*)', value)
                if match:
                    cmd = match.group(1)
                    val_str = match.group(2)
                    
                    if cmd not in COMMANDS:
                        print(f"Warning: Unknown command '{cmd}'")
                        continue
                    
                    # Convert value to integer safely
                    try:
                        v=val_str[1:-1]
                        v = int(v)
                        print(f"Processing command '{cmd}' with value {v}")
                    except ValueError:
                        v = 0
                        print(f"Warning: Invalid  value '{val_str}' for command '{cmd}', defaulting to 0")
                    
                    # Execute hardware actions instantly
                    if cmd == '[MX]':
                        print(f"Moving mouse X by {v} pixels")
                        gui.moveRel(v, 0)
                    elif cmd == '[MY]':
                        print(f"Moving mouse Y by {v} pixels")
                        gui.moveRel(0, v)
                    elif cmd == '[LB]':
                        gui.click(button='left') # Standard click
                    elif cmd == '[RB]':
                        gui.click(button='right')
                    elif cmd == '[MB]':
                        gui.click(button='middle')
                    elif cmd == '[SU]':
                        gui.scroll(v)
                    elif cmd == '[SD]':
                        gui.scroll(-v)
                    elif cmd == '[DS]':
                        gui.mouseDown(button='left') # Drag Start
                    elif cmd == '[DR]':
                        gui.mouseUp(button='left')   # Drag Release

            else:
                # Silently ignore malformed packets to prevent crashing
                pass

    except Exception as e:
        # Catch errors but DO NOT crash the script. Real-time apps must keep running.
        print(f"Error processing packet '{data}': {e}")

if __name__ == '__main__':
    receive_data()