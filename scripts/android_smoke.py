"""Smoke test real no emulador: arranque, definições e rascunho após rotação."""
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

qa = pathlib.Path('qa')
qa.mkdir(exist_ok=True)
def adb(*args):
    return subprocess.check_output(['adb', *args], text=True)
def tree():
    for _ in range(3):
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/window.xml')
            xml = adb('shell', 'cat', '/sdcard/window.xml')
            return ET.fromstring(xml), xml
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(1)
    raise AssertionError('Não foi possível ler a interface')
def tap_text(value):
    root, xml = tree()
    node = next(n for n in root.iter('node') if n.get('text') == value)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
def screenshot(name):
    (qa/name).write_bytes(subprocess.check_output(['adb', 'exec-out', 'screencap', '-p']))

adb('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
adb('shell', 'am', 'start', '-W', '-n', 'pt.reborn.assistant/.MainActivity')
time.sleep(2)
_, xml = tree()
assert 'Configurar Reborn' in xml and 'Enviar' in xml, xml
screenshot('01-welcome.png')
tap_text('Configurar Reborn')
_, xml = tree()
assert 'Endereço do servidor' in xml and 'Código de acesso Reborn' in xml, xml
screenshot('02-settings.png')
adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
root, xml = tree()
node = next(n for n in root.iter('node') if n.get('class') == 'android.widget.EditText')
x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
adb('shell', 'input', 'text', 'rascunho-reborn')
adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
adb('shell', 'settings', 'put', 'system', 'user_rotation', '1')
time.sleep(2)
_, xml = tree()
assert 'rascunho-reborn' in xml, 'O rascunho perdeu-se na rotação'
assert adb('shell', 'pidof', 'pt.reborn.assistant').strip(), 'App não está em execução'
screenshot('03-rotation.png')
(qa/'smoke-result.txt').write_text('PASS: instalação, arranque, definições e rascunho após rotação (API 35).\n')
print('PASS: Android install, launch, settings and rotation.')
