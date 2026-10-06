"""隔离恢复演练：新建本机 MySQL 实例，不连接开发库，保留证据并关闭本次进程。"""
import argparse
import base64
import hashlib
import http.cookiejar
import json
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / '基于Vue3的多组织活动管理系统_zcode/backend'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--mysql-bin', type=Path, required=True)
parser.add_argument('--java', type=Path, required=True)
args = parser.parse_args()
jar = BACKEND / 'target/activity-0.0.1-SNAPSHOT.jar'
assert jar.is_file(), '请先构建后端'
run_dir = ROOT / '验证截图' / ('恢复演练-' + time.strftime('%Y%m%d-%H%M%S'))
run_dir.mkdir(parents=True, exist_ok=False)
processes, logs = [], []
temporary = Path(tempfile.mkdtemp(prefix='activity-recovery-')).resolve()


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


db_port, api_port = free_port(), free_port()


def run(command, **kwargs):
    return subprocess.run([str(x) for x in command], check=True, capture_output=True,
                          encoding='utf-8', **kwargs).stdout.strip()


def start(command, name):
    log = (run_dir / (name + '.log')).open('w', encoding='utf-8')
    logs.append(log)
    process = subprocess.Popen([str(x) for x in command], cwd=run_dir,
                               stdout=log, stderr=subprocess.STDOUT,
                               creationflags=subprocess.CREATE_NO_WINDOW)
    processes.append(process)
    return process


def stop(process):
    if process.poll() is None:
        process.terminate()
        process.wait(timeout=30)


def wait_ready(process, check):
    for _ in range(60):
        if process.poll() is not None:
            raise RuntimeError('服务提前退出，请检查本次目录中的日志')
        try:
            if check():
                return
        except (OSError, subprocess.CalledProcessError):
            pass
        time.sleep(1)
    raise RuntimeError('服务启动超时')


mysql = [args.mysql_bin / 'mysql.exe', '--no-defaults', '--protocol=TCP',
         '-h127.0.0.1', f'-P{db_port}', '-uroot', '--default-character-set=utf8mb4', '-N', '-B']


def sql(query, database=None):
    return run(mysql + ([database] if database else []) + ['-e', query])


def app(database, uploads):
    process = start([args.java, '-jar', jar, f'--server.port={api_port}',
        f'--spring.datasource.url=jdbc:mysql://127.0.0.1:{db_port}/{database}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
        '--spring.datasource.username=root', '--spring.datasource.password=',
        '--spring.config.additional-location=optional:file:./empty-config/',
        '--spring.profiles.active=recovery-audit', '--app.ai.enabled=false',
        f'--app.upload.dir={uploads}'], database)
    wait_ready(process, lambda: urllib.request.urlopen(
        f'http://127.0.0.1:{api_port}/api/health', timeout=2).status == 200)
    return process


def client():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(opener, path, data=None, content_type='application/json', raw=False, method=None):
    headers = {}
    if data is not None:
        csrf = call(opener, '/api/auth/csrf')
        headers = {csrf['headerName']: csrf['token'], 'Content-Type': content_type,
                   'Idempotency-Key': 'recovery-' + str(time.time_ns())}
        if not isinstance(data, bytes):
            data = json.dumps(data).encode()
    request = urllib.request.Request(f'http://127.0.0.1:{api_port}{path}', data=data, headers=headers, method=method)
    try:
        response = opener.open(request, timeout=20)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'{path}: {error.code} {error.read().decode()}') from error
    with response:
        body = response.read()
        return body if raw else (json.loads(body) if body else None)


def login(opener):
    call(opener, '/api/auth/login', urllib.parse.urlencode(credentials).encode(),
         'application/x-www-form-urlencoded')


def inventory(database, uploads):
    result = {}
    for row in sql('SELECT stored_name,size_bytes FROM attachment WHERE deleted_at IS NULL', database).splitlines():
        name, size = row.split('\t')
        file = uploads / name
        assert file.is_file(), f'有效附件缺失：{name}'
        body = file.read_bytes()
        assert len(body) == int(size), f'附件长度不符：{name}'
        result[name] = hashlib.sha256(body).hexdigest()
    return result


try:
    datadir = temporary / 'mysql-data'
    run([args.mysql_bin / 'mysqld.exe', '--no-defaults', '--initialize-insecure', f'--datadir={datadir}'])
    database_process = start([args.mysql_bin / 'mysqld.exe', '--no-defaults', f'--datadir={datadir}',
        f'--port={db_port}', '--bind-address=127.0.0.1', '--mysqlx=OFF',
        f'--log-error={temporary / "mysql.err"}', '--console'], 'mysql')
    wait_ready(database_process, lambda: sql('SELECT 1') == '1')
    sql('CREATE DATABASE activity_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
    uploads = run_dir / 'uploads'
    source = app('activity_test', uploads)
    owner = client()
    credentials = {'username': 'restore_owner', 'password': 'RestoreDemo123!'}
    call(owner, '/api/auth/register', credentials)
    login(owner)
    org = call(owner, '/api/organizations', {'name': '恢复演练组织'})
    members = call(owner, f"/api/organizations/{org['id']}/members")['items']
    activity = call(owner, f"/api/organizations/{org['id']}/activities", {
        'name': '中文活动恢复核对', 'description': '核对中文、金额与附件的完整恢复', 'ownerMemberId': members[0]['memberId'],
        'registrationScope': 'INTERNAL', 'budget': '123.45', 'location': '恢复演练场地',
        'startTime': '2027-10-01T14:00:00+08:00', 'endTime': '2027-10-01T16:00:00+08:00'})
    activity_path = f"/api/organizations/{org['id']}/activities/{activity['id']}"
    call(owner, activity_path + '/proposal', {'content': '恢复演练方案', 'version': activity['version']}, method='PUT')
    call(owner, activity_path + '/submit', {})
    call(owner, activity_path + '/approve', {'version': call(owner, activity_path)['version']})
    png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')
    multipart = (b'--audit-boundary\r\nContent-Disposition: form-data; name="file"; filename="restore.png"\r\n'
                 b'Content-Type: image/png\r\n\r\n' + png + b'\r\n--audit-boundary--\r\n')
    attachment = call(owner, activity_path + '/attachments', multipart, 'multipart/form-data; boundary=audit-boundary')
    before = call(owner, activity_path)
    stop(source)  # 停止所有写入后再同时备份数据库和附件。
    original_hashes = inventory('activity_test', uploads)
    assert len(original_hashes) == 1
    tables = sql('SHOW TABLES', 'activity_test').splitlines()
    counts = {table: sql(f'SELECT COUNT(*) FROM `{table}`', 'activity_test') for table in tables}
    backup = run_dir / 'backup'
    backup.mkdir()
    dump = temporary / 'activity_test.sql'
    run([args.mysql_bin / 'mysqldump.exe', '--no-defaults', '--protocol=TCP', '-h127.0.0.1',
         f'-P{db_port}', '-uroot', '--single-transaction', '--routines', '--triggers',
         '--set-gtid-purged=OFF', '--default-character-set=utf8mb4', f'--result-file={dump}', 'activity_test'])
    shutil.copytree(uploads, backup / 'uploads')
    shutil.copy2(dump, backup / 'activity_test.sql')
    sql('CREATE DATABASE activity_restore CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
    with dump.open('rb') as stream:
        run(mysql + ['activity_restore'], stdin=stream)
    restored_uploads = run_dir / 'restored-uploads'
    shutil.copytree(backup / 'uploads', restored_uploads)
    assert counts == {table: sql(f'SELECT COUNT(*) FROM `{table}`', 'activity_restore') for table in tables}
    assert original_hashes == inventory('activity_restore', restored_uploads)
    # 缺文件反例：核对必须失败，不得因为目录复制成功就判定恢复成功。
    file = restored_uploads / next(iter(original_hashes))
    hidden = file.with_suffix('.missing')
    file.rename(hidden)
    try:
        try:
            inventory('activity_restore', restored_uploads)
        except AssertionError:
            missing_detected = True
        else:
            raise AssertionError('附件缺失检查未生效')
    finally:
        hidden.rename(file)
    restored = app('activity_restore', restored_uploads)
    owner = client()
    login(owner)
    assert call(owner, activity_path) == before
    assert call(owner, activity_path + f"/attachments/{attachment['attachmentId']}/download", raw=True) == png
    result = {'status': 'PASS', 'tables': len(tables), 'tableCounts': counts,
              'effectiveAttachments': len(original_hashes), 'sha256': original_hashes,
              'missingFileDetected': missing_detected, 'restoredLoginAndDownload': True,
              'databasePort': db_port, 'apiPort': api_port,
              'scope': '隔离本机实例；不含生产 HTTPS、真实模型密钥与大数据量演练'}
    (run_dir / '结果.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'status': 'PASS', 'evidence': str(run_dir / '结果.json')}, ensure_ascii=False))
finally:
    for process in reversed(processes):
        if process is locals().get('database_process'):
            try:
                run([args.mysql_bin / 'mysqladmin.exe', '--no-defaults', '--protocol=TCP',
                     '-h127.0.0.1', f'-P{db_port}', '-uroot', 'shutdown'])
                process.wait(timeout=30)
            except subprocess.CalledProcessError:
                pass
        stop(process)
    for log in logs:
        log.close()
    assert temporary.parent == Path(tempfile.gettempdir()).resolve() and temporary.name.startswith('activity-recovery-')
    for attempt in range(10):
        try:
            shutil.rmtree(temporary)
            break
        except PermissionError:
            if attempt == 9:
                raise
            time.sleep(1)
