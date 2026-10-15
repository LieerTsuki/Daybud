"""Release-only emulator smoke measurements; not Macrobenchmark or an FPS claim."""
import argparse
import json
from pathlib import Path
import re
import statistics
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
P = 'com.example.lifemanager'


def main():
    if not __debug__:
        raise RuntimeError('Optimized Python would disable verification; run without -O/PYTHONOPTIMIZE')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Only dedicated test emulators'
    adb_path = ROOT / '.tools/android-sdk/platform-tools/adb.exe'

    def adb(*argv):
        return subprocess.check_output([str(adb_path), '-s', args.serial, *argv]).decode('utf-8')

    assert adb('emu', 'avd', 'name').splitlines()[0].strip() == 'Rizhiya_F_Upgrade_20261016'
    package = adb('shell', 'dumpsys', 'package', P)
    assert 'versionName=1.0.0' in package and 'versionCode=3 ' in package
    flags = re.findall(r'^\s*(?:pkgFlags|flags)=\[([^\]]*)\]', package, re.M)
    assert flags and all('DEBUGGABLE' not in flag for flag in flags), 'Install the signed non-debuggable release first'
    out = ROOT / '.tools/f-performance-final'
    out.mkdir(parents=True, exist_ok=False)
    (out / 'package.txt').write_text(package, encoding='utf-8')
    (out / 'display.txt').write_text(adb('shell', 'dumpsys', 'display'), encoding='utf-8')
    (out / 'surfaceflinger.txt').write_text(adb('shell', 'dumpsys', 'SurfaceFlinger'), encoding='utf-8')
    cold = []
    for i in range(5):
        adb('shell', 'am', 'force-stop', P)
        result = adb('shell', 'am', 'start', '-W', '-n', P + '/.MainActivity')
        (out / f'launch-{i+1}.txt').write_text(result, encoding='utf-8')
        assert 'Status: ok' in result and 'LaunchState: COLD' in result, result
        cold.append(int(re.search(r'TotalTime:\s*(\d+)', result)[1]))
        time.sleep(2)
    adb('shell', 'uiautomator', 'dump', '/sdcard/f-performance-window.xml')
    xml = adb('exec-out', 'cat', '/sdcard/f-performance-window.xml')
    (out / 'navigation.xml').write_text(xml, encoding='utf-8')
    tree = ET.fromstring(xml)
    positions = {}
    for label in ['计划', '打卡', '订阅', '设置']:
        nodes = [n for n in tree.iter('node') if n.get('content-desc') == label + '入口']
        assert len(nodes) == 1, (label, xml)
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', nodes[0].get('bounds')))
        positions[label] = [str((x1+x2)//2), str((y1+y2)//2)]
    adb('shell', 'dumpsys', 'gfxinfo', P, 'reset')
    navigation = []
    for cycle in range(5):
        for label in ['打卡', '订阅', '设置', '计划']:
            began = time.monotonic()
            adb('shell', 'input', 'tap', *positions[label])
            time.sleep(0.9)
            adb('shell', 'uiautomator', 'dump', '/sdcard/f-performance-window.xml')
            current = adb('exec-out', 'cat', '/sdcard/f-performance-window.xml')
            (out / f'cycle-{cycle+1}-{label}.xml').write_text(current, encoding='utf-8')
            # UIAutomator exposes Compose's description on a child of the selected tab.
            # Require that the selected ancestor actually contains this exact destination.
            selected = [n for n in ET.fromstring(current).iter('node')
                        if n.get('selected') == 'true' and
                        any(d.get('content-desc') == label + '入口' for d in n.iter('node'))]
            assert len(selected) == 1, f'Navigation to {label} was not confirmed'
            navigation.append(dict(destination=label, automationElapsedMs=round((time.monotonic()-began)*1000)))
    gfx = adb('shell', 'dumpsys', 'gfxinfo', P, 'framestats')
    (out / 'gfxinfo.txt').write_text(gfx, encoding='utf-8')
    assert int(re.search(r'Total frames rendered:\s*(\d+)', gfx)[1]) > 0, 'No frame samples'
    adb('shell', 'rm', '-f', '/sdcard/f-performance-window.xml')
    result = dict(serial=args.serial, version='1.0.0', code=3, debuggable=False,
                  coldTotalTimeMs=cold, medianMs=statistics.median(cold), maxMs=max(cold),
                  navigationCycles=5, navigationMinimumDelaySeconds=0.9,
                  confirmedNavigations=navigation,
                  gfxSummary=[line.strip() for line in gfx.splitlines() if re.search(r'Total frames|Janky frames|percentile|Missed Vsync', line)],
                  limitation='Emulator, synthetic small database, system launch and navigation smoke only; no physical-device or stable-60fps acceptance.')
    (out / 'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
