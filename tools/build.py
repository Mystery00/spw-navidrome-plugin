"""仅使用标准库编译、测试和打包；宿主字节码只作为编译输入。"""
import argparse, hashlib, json, os, pathlib, re, subprocess, sys, zipfile
from urllib.parse import urlsplit

ROOT = pathlib.Path(__file__).resolve().parents[1]

def version_from_tag(tag):
    """仅接受正式发布 tag，避免版本与文件路径混淆。"""
    if not re.fullmatch(r'v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)', tag):
        raise ValueError('Release tag must match vMAJOR.MINOR.PATCH without leading zeros')
    return tag[1:]

def run(args):
    subprocess.run([str(x) for x in args], cwd=ROOT, check=True)

def validate_metadata(meta):
    """在编译前拒绝不完整清单、非法权限及换行注入。"""
    for key in ('pluginId', 'version', 'mainClass'):
        if not isinstance(meta.get(key), str) or not meta[key].strip():
            raise ValueError(f'Required metadata missing: {key}')
    for value in meta.values():
        if isinstance(value, str) and any(c in value for c in '\r\n\0'):
            raise ValueError('Metadata contains a forbidden control character')
    for key in ('pluginId', 'mainClass'):
        if not re.fullmatch(r'[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+', meta[key], re.ASCII):
            raise ValueError(f'Invalid qualified name: {key}')
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?', meta['version']):
        raise ValueError('Version must use semantic version format')
    permissions = meta.get('permissions', [])
    allowed = {'key-bindings', 'library-read', 'library-write', 'class-transform'}
    if (not isinstance(permissions, list) or any(not isinstance(p, str) or p not in allowed for p in permissions)
            or len(permissions) != len(set(permissions))):
        raise ValueError('Unsupported or duplicate plugin permissions')
    if meta.get('openSourceUrl'):
        url = urlsplit(meta['openSourceUrl'])
        if url.scheme != 'https' or not url.hostname or url.username or url.password:
            raise ValueError('Source URL must be public HTTPS without credentials')

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--jdk', required=True)
    p.add_argument('--host')
    p.add_argument('--test-only', action='store_true')
    p.add_argument('--standalone', action='store_true', help='Build with the public API, without an installed host')
    p.add_argument('--tag', help='Use a release tag as the package version')
    args = p.parse_args()
    if args.standalone and (args.host or args.test_only):
        p.error('--standalone cannot be combined with --host or --test-only')
    run([sys.executable, ROOT/'tools/test_build.py'])
    meta = json.loads((ROOT/'project.json').read_text(encoding='utf-8'))
    if args.tag:
        meta['version'] = version_from_tag(args.tag)
    validate_metadata(meta)
    # 宿主仍会解析原配置层，即使其上覆盖了自定义 Compose 页面。
    config = json.loads((ROOT/'src/main/resources/preference_config.json').read_text(encoding='utf-8'))
    for group in config['configs']:
        for preference in group['preferences']:
            kind = preference.get('type')
            if kind not in {'button', 'switch', 'list', 'seekbar', 'edittext'}:
                raise SystemExit(f'Unsupported preference type: {kind}')
            if kind != 'button' and not preference.get('key'):
                raise SystemExit(f'Preference key required: {kind}')
    build = ROOT / 'build'
    build.mkdir(exist_ok=True)
    if args.standalone:
        env = os.environ.copy()
        env['JAVA_HOME'] = args.jdk
        subprocess.run([str(ROOT/'gradlew.bat'), '--no-daemon', '-Pstandalone=true',
                        'clean', 'classes', 'coreTest', 'viewRequestTest', 'credentialStoreTest',
                        'nativeStateTest', 'nativeThemeTest', 'permissionLifecycleTest'],
                       cwd=ROOT, env=env, check=True)
        package(meta)
        print('HOST ABI TESTS SKIPPED: standalone build does not include SPW.')
        return
    # 编译器输出只能清理工作区内固定路径。
    import shutil
    classes = build / 'test-classes'
    if classes.exists(): shutil.rmtree(classes)
    classes.mkdir()
    cp = []
    if not args.test_only:
        if not args.host: raise SystemExit('Set SPW_HOME or --host to the installed SPW directory.')
        host = pathlib.Path(args.host) / 'app' / 'ffmpeg-x64.dll'
        cache = ROOT / 'tools' / '.cache'
        cache.mkdir(parents=True, exist_ok=True)
        api = cache / 'host-compile-only.jar'
        with zipfile.ZipFile(host) as z, zipfile.ZipFile(api, 'w') as out:
            for n in z.namelist():
                if n.endswith('.class') and n.startswith(('com/xuncorp/spw/workshop/api/', 'org/pf4j/', 'kotlin/jvm/internal/', 'kotlin/annotation/', 'kotlin/Metadata', 'org/jetbrains/annotations/', 'com/xuncorp/spw/workshop/api/hook/')):
                    out.writestr(n, z.read(n))
        cp.append(api)
        (build / 'host-sha256.txt').write_text(hashlib.sha256(host.read_bytes()).hexdigest(), encoding='utf-8')
    sources = list((ROOT / 'src/main/java').rglob('*.java'))
    if args.test_only: sources = [s for s in sources if s.name != 'NavidromePlugin.java']
    sources += list((ROOT / 'src/test/java').rglob('*.java'))
    argfile = build / 'javac.args'
    opts = ['--release', '21', '-encoding', 'UTF-8', '-d', str(classes)]
    if cp: opts += ['-classpath', os.pathsep.join(map(str, cp))]
    argfile.write_text('\n'.join('"' + str(x).replace('\\', '/') + '"' for x in opts + sources), encoding='utf-8')
    run([pathlib.Path(args.jdk)/'bin/javac.exe', '@' + str(argfile)])
    run([pathlib.Path(args.jdk)/'bin/java.exe', '-ea', '-cp', classes, 'vip.mystery0.spw.navidrome.CoreTest'])
    run([pathlib.Path(args.jdk)/'bin/java.exe', '-ea', '-cp', classes, 'vip.mystery0.spw.navidrome.ui.ViewRequestTest'])
    if args.host:
        host = pathlib.Path(args.host) / 'app' / 'ffmpeg-x64.dll'
        test_cp = os.pathsep.join(map(str, [classes, host]))
        run([pathlib.Path(args.jdk)/'bin/java.exe', '--enable-native-access=ALL-UNNAMED', '-ea', '-cp', test_cp, 'vip.mystery0.spw.navidrome.credentials.CredentialStoreTest'])
        if not args.test_only:
            run([pathlib.Path(args.jdk)/'bin/java.exe', '-ea', '-cp', classes, 'vip.mystery0.spw.navidrome.host.Host1192Test', host])
            run([pathlib.Path(args.jdk)/'bin/java.exe', '-ea', '-cp', classes, 'vip.mystery0.spw.navidrome.host.HostThemeAbiTest', host])
    else:
        print('DPAPI TEST SKIPPED: pass --host to provide the host JNA runtime.')
    if args.test_only: return
    # Gradle 统一编译 Java 与 Compose，宿主与依赖只用于编译。
    env = os.environ.copy(); env['JAVA_HOME'] = args.jdk; env['GRADLE_USER_HOME'] = str(ROOT/'.tools/gradle-home')
    subprocess.run([str(ROOT/'gradlew.bat'), '--no-daemon', f'-PspwHostJar={host}', 'classes', 'nativeStateTest', 'nativeThemeTest', 'permissionLifecycleTest'], cwd=ROOT, env=env, check=True)
    package(meta)


def package(meta):
    """本机与 CI 共用同一安装包格式和依赖隔离检查。"""
    import shutil
    build = ROOT / 'build'
    production_dirs = [build/'classes/java/main', build/'classes/kotlin/main']
    manifest = {'Manifest-Version':'1.0', 'Plugin-Id':meta['pluginId'], 'Plugin-Version':meta['version'], 'Plugin-Class':meta['mainClass'], 'Plugin-Name':meta['name'], 'Plugin-Provider':meta['provider'], 'Plugin-Description':meta['description'], 'Plugin-Has-Config':'true', 'Plugin-Permissions':','.join(meta.get('permissions',[]))}
    if meta.get('openSourceUrl'):
        manifest['Plugin-Open-Source-Url'] = meta['openSourceUrl']
    # UTF-8 按字符折行，避免切断多字节字符。
    lines = []
    for k, v in manifest.items():
        line = ''
        for ch in k + ': ' + v:
            if len((line + ch).encode()) > 70: lines.append(line); line = ' '
            line += ch
        lines.append(line)
    mf = ('\r\n'.join(lines)+'\r\n\r\n').encode()
    dist = build/'dist'; dist.mkdir(exist_ok=True)
    target = dist/f"plugin-{meta['pluginId']}-{meta['version']}.spmod"
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('META-INF/MANIFEST.MF', mf)
        z.writestr('classes/META-INF/MANIFEST.MF', mf)
        for output in production_dirs:
            for f in output.rglob('*'):
                if f.is_file(): z.write(f, 'classes/' + f.relative_to(output).as_posix())
        resources = ROOT/'src/main/resources'
        for f in resources.rglob('*'):
            if f.is_file(): z.write(f, 'classes/'+f.relative_to(resources).as_posix())
        z.write(ROOT/'LICENSE', 'LICENSE')
        for f in (ROOT/'licenses').glob('*.txt'): z.write(f, 'licenses/'+f.name)
        if (ROOT/'THIRD_PARTY.md').exists(): z.write(ROOT/'THIRD_PARTY.md', 'THIRD_PARTY.md')
    with zipfile.ZipFile(target) as z:
        assert 'classes/'+meta['mainClass'].replace('.', '/')+'.class' in z.namelist()
        assert not any(n.startswith(('classes/com/xuncorp/', 'classes/kotlin/', 'classes/androidx/', 'classes/org/pf4j/')) for n in z.namelist())
        assert not any('Test' in n or 'MockServer' in n for n in z.namelist())
    # 官方发布使用 SPMOD；另附本机已验证可导入的同内容 ZIP。
    shutil.copyfile(target, target.with_suffix('.zip'))
    print('BUILD OK:', target)
    print('IMPORT ZIP:', target.with_suffix('.zip'))
    print('SHA256:', hashlib.sha256(target.read_bytes()).hexdigest())

if __name__ == '__main__': main()
