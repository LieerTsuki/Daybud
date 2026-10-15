"""Synthetic upgrade evidence, only on the explicitly named project test AVD.

Run prepare with the published 0.1.1 installed, export its v1 backup, then
install -r the candidate and run verify. Never run connectedAndroidTest on
this sample: that task uninstalls the app. Evidence stays under .tools/.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
PACKAGE = 'com.example.lifemanager'
EVIDENCE = ROOT / '.tools/f-upgrade-20261016'
TABLES = ['todos', 'tags', 'todo_tag_cross_ref', 'schedules', 'schedule_exceptions',
          'habits', 'habit_records', 'subscriptions', 'subscription_payments', 'subscription_reminders']
UNCHANGED = ['tags', 'habits', 'habit_records', 'subscriptions', 'subscription_payments', 'subscription_reminders']


def adb(*args, data=None, check=True):
    return subprocess.run([str(ADB), '-s', OPTIONS.serial, *args], input=data,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=check).stdout


def guard():
    assert OPTIONS.serial.startswith('emulator-'), 'Physical devices are forbidden'
    assert adb('emu', 'avd', 'name').decode().splitlines()[0].strip() == 'Rizhiya_F_Upgrade_20261016'
    assert adb('shell', 'getprop', 'sys.boot_completed').strip() == b'1'


def snapshot(label):
    adb('shell', 'am', 'force-stop', PACKAGE)
    target = EVIDENCE / label
    # A repeated label must never silently reuse a previous DB or WAL.
    target.mkdir(parents=True, exist_ok=False)
    raw = target / 'raw'
    raw.mkdir()
    remote_files = set(adb('exec-out', 'run-as', PACKAGE, 'ls', '-1', 'databases').decode('utf-8').splitlines())
    native_generation = int(adb('exec-out', 'run-as', PACKAGE, 'sqlite3', '-readonly',
        'databases/life-manager.db', 'select(generation)from(app_maintenance);').strip())
    manifest = {}
    # SQLite recreates SHM locally. An existing WAL must be read successfully.
    for suffix in ['', '-wal']:
        remote = 'databases/life-manager.db' + suffix
        if suffix and 'life-manager.db' + suffix not in remote_files:
            continue
        content = adb('exec-out', 'run-as', PACKAGE, 'cat', remote)
        if not suffix and not content.startswith(b'SQLite format 3\x00'):
            raise RuntimeError('Remote database was not read successfully')
        if content:
            (raw / ('life-manager.db' + suffix)).write_bytes(content)
            (target / ('life-manager.db' + suffix)).write_bytes(content)
            manifest[suffix or 'main'] = dict(bytes=len(content), sha256=hashlib.sha256(content).hexdigest())
    (raw / 'manifest.json').write_text(json.dumps(dict(nativeGeneration=native_generation, files=manifest), indent=2), encoding='utf-8')
    conn = sqlite3.connect(target / 'life-manager.db')
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA wal_checkpoint(TRUNCATE)')
    assert conn.execute('SELECT generation FROM app_maintenance').fetchone()[0] == native_generation, 'Host snapshot differs from on-device SQLite'
    return conn, target


def rows(conn, table):
    return [dict(r) for r in conn.execute(f'SELECT * FROM "{table}" ORDER BY 1,2')]


def prepare():
    package = adb('shell', 'dumpsys', 'package', PACKAGE).decode()
    assert 'versionName=0.1.1' in package and 'versionCode=2 ' in package
    conn, target = snapshot('old')
    assert conn.execute('PRAGMA user_version').fetchone()[0] == 5
    assert all(not rows(conn, t) for t in TABLES), 'Only an empty synthetic test install may be seeded'
    statements = [
        "INSERT INTO todos VALUES (41,'  合成子项🌱  ','原文','HIGH',1792108800000,0,7,7,8,42,-3)",
        "INSERT INTO todos VALUES (42,'合成父项',NULL,'NONE',NULL,1,NULL,7,8,NULL,0)",
        "INSERT INTO tags VALUES (4,'合成标签',-123,7)",
        "INSERT INTO todo_tag_cross_ref VALUES (41,4)",
        "INSERT INTO schedules VALUES (41,'合成全天',1000,2000,1,20741,20742,' 地点 ','','原文',-123,30,'CUSTOM',2,'7,01,1',20745,'+08:00',7,8)",
        "INSERT INTO schedules VALUES (42,'合成区间',1792112400000,1792116000000,0,NULL,NULL,NULL,'参与者',NULL,-456,15,'NONE',1,NULL,NULL,'Asia/Shanghai',9,10)",
        "INSERT INTO schedule_exceptions VALUES (41,20741,1)",
        "INSERT INTO habits VALUES (5,'合成习惯','book',-123,'CUSTOM',1,'7,1,1',20000,NULL,7,8)",
        "INSERT INTO habit_records VALUES (9,5,19999,7)",
        "INSERT INTO subscriptions VALUES (8,'合成订阅',9007199254740993,'CNY','MONTHLY',20741,19900,NULL,'',0,20001,7,8)",
        "INSERT INTO subscription_payments VALUES (3,8,9007199254740993,'EUR',19999,'合成历史')",
        "INSERT INTO subscription_reminders VALUES (8,3)",
        "UPDATE app_settings SET theme='DARK',dateFormat='DMY',defaultCurrency='EUR',todoReminders=0,scheduleReminders=1,subscriptionReminders=0,defaultReminderMask=7 WHERE id=1",
        "UPDATE app_maintenance SET generation=73 WHERE id=1",
    ]
    with conn:
        for sql in statements:
            conn.execute(sql)
    assert not conn.execute('PRAGMA foreign_key_check').fetchall()
    expected = {t: rows(conn, t) for t in TABLES + ['app_settings', 'app_maintenance']}
    (target.parent / 'expected.json').write_text(json.dumps(expected, ensure_ascii=False, indent=2), encoding='utf-8')
    conn.execute('PRAGMA wal_checkpoint(TRUNCATE)')
    conn.close()
    adb('shell', 'run-as', PACKAGE, 'rm', '-f', 'databases/life-manager.db-wal', 'databases/life-manager.db-shm')
    adb('exec-in', 'run-as', PACKAGE, 'sh', '-c', 'cat > databases/life-manager.db', data=(target / 'life-manager.db').read_bytes())
    print(json.dumps({'prepared': {t: len(expected[t]) for t in TABLES}, 'generation': 73}))


def verify():
    old = json.loads((EVIDENCE / 'expected.json').read_text(encoding='utf-8'))
    conn, target = snapshot(OPTIONS.label)
    assert conn.execute('PRAGMA user_version').fetchone()[0] == 6
    assert not conn.execute('PRAGMA foreign_key_check').fetchall()
    for table in UNCHANGED:
        assert rows(conn, table) == old[table], table
    plans = rows(conn, 'plans')
    refs = rows(conn, 'plan_legacy_refs')
    mapping = {(r['source'], r['legacyId']): r['planId'] for r in refs}
    assert len(plans) == len(refs) == len(old['todos']) + len(old['schedules'])
    for source, table in [('TODO', 'todos'), ('SCHEDULE', 'schedules')]:
        for item in old[table]:
            expected = dict(id=mapping[source, item['id']], title=item['title'],
                description=item['description'] if source == 'TODO' else item['note'],
                priority=item['priority'] if source == 'TODO' else 'NONE',
                color=-14390954 if source == 'TODO' else item['color'],
                location=None if source == 'TODO' else item['location'],
                participants=None if source == 'TODO' else item['participants'],
                timeMode=('NONE' if item['dueAt'] is None else 'DEADLINE') if source == 'TODO' else ('ALL_DAY' if item['isAllDay'] else 'TIMED'),
                dueAt=item['dueAt'] if source == 'TODO' else None, dueDate=None,
                startAt=None if source == 'TODO' else item['startAt'], endAt=None if source == 'TODO' else item['endAt'],
                allDayStartDate=None if source == 'TODO' else item['allDayStartDate'],
                allDayEndDate=None if source == 'TODO' else item['allDayEndDate'],
                timeZone=OPTIONS.zone if source == 'TODO' else item['timeZone'],
                isCompleted=item['isCompleted'] if source == 'TODO' else 0,
                completedAt=item['completedAt'] if source == 'TODO' else None,
                repeatRule='NONE' if source == 'TODO' else item['repeatRule'],
                repeatInterval=1 if source == 'TODO' else item['repeatInterval'],
                repeatDaysOfWeek=None if source == 'TODO' else item['repeatDaysOfWeek'],
                repeatEndDate=None if source == 'TODO' else item['repeatEndDate'],
                reminderEnabled=0 if source == 'TODO' else int(not item['isAllDay']),
                reminderMinutes=15 if source == 'TODO' else item['reminderMinutes'],
                parentId=mapping.get(('TODO', item['parentId'])) if source == 'TODO' else None,
                sortOrder=item['sortOrder'] if source == 'TODO' else 0,
                createdAt=item['createdAt'], updatedAt=item['updatedAt'])
            actual = next(p for p in plans if p['id'] == expected['id'])
            assert actual == expected, (source, item['id'], actual, expected)
    assert rows(conn, 'plan_tag_cross_ref') == [{'planId': mapping['TODO', r['todoId']], 'tagId': r['tagId']} for r in old['todo_tag_cross_ref']]
    assert rows(conn, 'plan_exceptions') == [{'planId': mapping['SCHEDULE', r['scheduleId']], 'occurrenceDate': r['occurrenceDate'], 'isCancelled': r['isCancelled']} for r in old['schedule_exceptions']]
    settings = old['app_settings'][0]
    expected_settings = {k: v for k, v in settings.items() if k not in ['todoReminders', 'scheduleReminders']}
    expected_settings['planReminders'] = int(bool(settings['todoReminders'] or settings['scheduleReminders']))
    assert rows(conn, 'app_settings') == [expected_settings]
    assert rows(conn, 'app_maintenance') == [{'id': 1, 'generation': OPTIONS.generation}]
    assert not conn.execute("SELECT name FROM sqlite_master WHERE name IN ('todos','schedules','todo_tag_cross_ref','schedule_exceptions')").fetchall()
    result = {'schema': 6, 'generation': OPTIONS.generation, 'plans': len(plans), 'planColumnsChecked': len(plans[0]), 'unchangedTables': UNCHANGED, 'foreignKeyViolations': 0}
    (target / 'verified.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    conn.close()
    print(json.dumps(result))


def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/f-window.xml')
    content = adb('exec-out', 'cat', '/sdcard/f-window.xml')
    (ROOT / '.tools/f-window.xml').write_bytes(content)
    tree = ET.fromstring(content)
    if OPTIONS.text is None:
        for n in tree.iter('node'):
            if n.get('text') or n.get('content-desc'):
                print(n.get('text'), n.get('content-desc'), n.get('bounds'))
        return
    found = [n for n in tree.iter('node') if n.get('text') == OPTIONS.text or n.get('content-desc') == OPTIONS.text]
    assert len(found) == 1, [(n.get('text'), n.get('bounds')) for n in found]
    import re
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', found[0].get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


if __name__ == '__main__':
    if not __debug__:
        raise RuntimeError('Optimized Python would disable verification; run without -O/PYTHONOPTIMIZE')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'verify', 'ui'])
    parser.add_argument('--serial', required=True)
    parser.add_argument('--zone', default='Asia/Shanghai')
    parser.add_argument('--generation', type=int, default=74)
    parser.add_argument('--label', default='upgraded')
    parser.add_argument('--text')
    OPTIONS = parser.parse_args()
    guard()
    globals()[OPTIONS.action]()
