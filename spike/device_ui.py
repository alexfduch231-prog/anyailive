"""ADB UI helper: print visible controls or tap a resource id. No credentials."""
import os, subprocess, sys, xml.etree.ElementTree as ET, re
adb = os.path.join(os.environ['ANDROID_HOME'], 'platform-tools', 'adb.exe')
device = os.environ.get('ANYAILIVE_DEVICE', '192.168.3.111:38555')
def call(*args):
    return subprocess.check_output([adb, '-s', device, *args])
if __name__ == '__main__':
    call('shell', 'uiautomator', 'dump', '/sdcard/anyailive-ui.xml')
    root = ET.fromstring(call('shell', 'cat', '/sdcard/anyailive-ui.xml'))
    for node in root.iter('node'):
        rid, text, bounds = (node.get(k, '') for k in ['resource-id', 'text', 'bounds'])
        if len(sys.argv) > 1 and rid.endswith(':id/' + sys.argv[1]):
            x1,y1,x2,y2 = map(int, re.findall(r'\d+', bounds))
            call('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            if len(sys.argv) > 2:
                call('shell', 'input', 'text', sys.argv[2])
            break
        if len(sys.argv) == 1 and (text or rid):
            print(rid, repr(text), bounds)
