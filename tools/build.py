"""仅使用标准库编译、测试和打包；宿主字节码只作为编译输入。"""
import argparse, hashlib, json, os, pathlib, subprocess, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

def run(args):
    subprocess.run([str(x) for x in args], cwd=ROOT, check=True)

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--jdk', required=True)
    p.add_argument('--host')
    p.add_argument('--test-only', action='store_true')
    args = p.parse_args()
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
    run([pathlib.Path(args.jdk)/'bin/java.exe', '-ea', '-cp', classes, 'io.github.mystery00.spw.navidrome.CoreTest'])
    run([pathlib.Path(args.jdk)/'bin/java.exe', '-ea', '-cp', classes, 'io.github.mystery00.spw.navidrome.ui.ViewRequestTest'])
    if args.host:
        host = pathlib.Path(args.host) / 'app' / 'ffmpeg-x64.dll'
        test_cp = os.pathsep.join(map(str, [classes, host]))
        run([pathlib.Path(args.jdk)/'bin/java.exe', '--enable-native-access=ALL-UNNAMED', '-ea', '-cp', test_cp, 'io.github.mystery00.spw.navidrome.credentials.CredentialStoreTest'])
        if not args.test_only:
            run([pathlib.Path(args.jdk)/'bin/java.exe', '-ea', '-cp', classes, 'io.github.mystery00.spw.navidrome.host.Host1192Test', host])
    else:
        print('DPAPI TEST SKIPPED: pass --host to provide the host JNA runtime.')
    if args.test_only: return
    # Gradle 统一编译 Java 与 Compose，宿主与依赖只用于编译。
    env = os.environ.copy(); env['JAVA_HOME'] = args.jdk; env['GRADLE_USER_HOME'] = str(ROOT/'.tools/gradle-home')
    subprocess.run([str(ROOT/'gradlew.bat'), '--no-daemon', 'classes', 'nativeStateTest'], cwd=ROOT, env=env, check=True)
    production_dirs = [build/'classes/java/main', build/'classes/kotlin/main']
    meta = json.loads((ROOT/'project.json').read_text(encoding='utf-8'))
    manifest = {'Manifest-Version':'1.0', 'Plugin-Id':meta['pluginId'], 'Plugin-Version':meta['version'], 'Plugin-Class':meta['mainClass'], 'Plugin-Name':meta['name'], 'Plugin-Provider':meta['provider'], 'Plugin-Description':meta['description'], 'Plugin-Has-Config':'true', 'Plugin-Permissions':','.join(meta.get('permissions',[]))}
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
    # SPW 导入器只接受 ZIP/JAR 扩展名，提供内容相同的 ZIP。
    shutil.copyfile(target, target.with_suffix('.zip'))
    print('BUILD OK:', target)
    print('IMPORT ZIP:', target.with_suffix('.zip'))
    print('SHA256:', hashlib.sha256(target.read_bytes()).hexdigest())

if __name__ == '__main__': main()
