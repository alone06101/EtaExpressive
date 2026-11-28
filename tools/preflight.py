#!/usr/bin/env python3
# 编译前静态预检：拦住「编译期才暴露、且已经踩过」的几类错误。
#
# 这些错误都真实发生过，每拦一次省一轮 8 分钟的 CI：
#  1. DAO 注解与声明被拆开：在某个函数声明「之前」插入新方法时，容易插到上一个
#     方法的 @Query 与它自己的声明之间，于是那个 @Query 挂到了新方法上，原方法
#     变成没有注解的抽象函数。要到 KSP 阶段才报，且报的是间接信息。
#  2. 缺 import：新增代码引用跨包符号时凭记忆写，编译期才报 Unresolved reference。
#  3. @Query 的 :param 与函数参数不一致。
#  4. 三语文案 key 不一致。
#
# 只做静态文本检查，不需要 Android SDK。

import glob
import io
import re
import sys

DAO_ANNOTATIONS = ('@Query', '@Insert', '@Update', '@Delete', '@Upsert', '@RawQuery')

# 常被跨包引用、且踩过坑的符号；同包引用不需要 import，会自动跳过。
KNOWN_SYMBOLS = {
    'AndroidAgentLogger': 'io.github.mangi.eta.core',
    'safeLogType': 'io.github.mangi.eta.core',
    'CapabilityAssistRouter': 'io.github.mangi.eta.data.repository',
    'EtaConversationAnalysisDocument': 'io.github.mangi.eta.data.repository',
    'EtaConversationAnalysisOptions': 'io.github.mangi.eta.data.repository',
    'ConversationDuplicateRepository': 'io.github.mangi.eta.data.repository',
    'DatabaseTestRetryRule': 'io.github.mangi.eta.data.db',
}

problems = []
warnings = []


def read(path):
    return io.open(path, encoding='utf-8').read()


def package_of(text):
    match = re.search(r'^package\s+([\w.]+)', text, re.MULTILINE)
    return match.group(1) if match else ''


def check_dao_annotation_pairs(path):
    lines = read(path).split(chr(10))
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped.startswith(DAO_ANNOTATIONS):
            continue
        cursor = index
        # @Upsert / @Insert / @Delete 不带参数，整行即注解结束；
        # 只有带括号的注解才需要往下找配对的收尾行。
        if '(' in lines[cursor]:
            while cursor < len(lines) and not lines[cursor].strip().endswith(')'):
                cursor += 1
        if cursor >= len(lines):
            continue
        probe = cursor + 1
        while probe < len(lines):
            text = lines[probe].strip()
            if text == '' or text.startswith('//') or text.startswith('/*') or text.startswith('*') or text.endswith('*/'):
                probe += 1
                continue
            break
        if probe >= len(lines):
            continue
        declaration = lines[probe].strip()
        if not declaration.startswith('fun ') and not declaration.startswith('suspend fun '):
            problems.append(path + ':' + str(index + 1) + ' 注解后未紧跟函数声明（疑似被拆开）：' + declaration[:60])


def check_query_parameters(path):
    text = read(path)
    for match in re.finditer(r'@Query\(', text):
        start = match.end()
        depth = 1
        cursor = start
        while cursor < len(text) and depth > 0:
            if text[cursor] == '(':
                depth += 1
            elif text[cursor] == ')':
                depth -= 1
            cursor += 1
        annotation_body = text[start:cursor - 1]
        # 只认真正的绑定位置：前面是等号、逗号、左括号或空白。
        # 否则 SQL 字符串常量里的冒号（https://host、{"k":true}）会被误当成变量。
        bindings = set(re.findall(r'(?<=[=\s,(]):([A-Za-z_][A-Za-z0-9_]*)', annotation_body))
        if not bindings:
            continue
        tail = text[cursor:]
        signature_end = tail.find(')')
        if signature_end == -1:
            continue
        params = set(re.findall(r'([A-Za-z_][A-Za-z0-9_]*)\s*:', tail[:signature_end]))
        missing = bindings - params
        if missing:
            problems.append(path + ' @Query 缺少参数：' + ', '.join(sorted(missing)))


def check_imports(path):
    text = read(path)
    package = package_of(text)
    body = chr(10).join(line for line in text.split(chr(10)) if not line.strip().startswith('import '))
    for symbol, owner in KNOWN_SYMBOLS.items():
        if owner == package:
            continue
        if not re.search(r'\b' + re.escape(symbol) + r'\b', body):
            continue
        # 全限定名引用（io.github.mangi.eta.core.AndroidAgentLogger.error(...)）
        # 本身不需要 import。
        if owner + '.' + symbol in body:
            continue
        expected = 'import ' + owner + '.' + symbol
        if expected not in text:
            problems.append(path + ' 使用了 ' + symbol + ' 但缺少 import：' + expected)


def check_strings_locales():
    paths = {
        'en': 'app/src/main/res/values/strings.xml',
        'zh-Hans': 'app/src/main/res/values-b+zh+Hans/strings.xml',
        'zh-Hant': 'app/src/main/res/values-b+zh+Hant/strings.xml',
    }
    keys = {}
    for name, path in paths.items():
        try:
            text = read(path)
        except OSError:
            warnings.append('缺少文案文件：' + path)
            continue
        keys[name] = set(re.findall(r'<string name="([^"]+)"', text))
    if len(keys) < 2:
        return
    baseline = keys['en']
    for name, values in keys.items():
        if name == 'en':
            continue
        only_baseline = baseline - values
        only_locale = values - baseline
        if only_baseline:
            problems.append(name + ' 缺少 ' + str(len(only_baseline)) + ' 个 key，例如：' + ', '.join(sorted(only_baseline)[:5]))
        if only_locale:
            problems.append(name + ' 多出 ' + str(len(only_locale)) + ' 个 key，例如：' + ', '.join(sorted(only_locale)[:5]))


def main():
    for path in sorted(glob.glob('app/src/main/kotlin/**/*Dao.kt', recursive=True)):
        check_dao_annotation_pairs(path)
        check_query_parameters(path)
    for path in sorted(glob.glob('app/src/main/kotlin/**/*.kt', recursive=True)):
        check_imports(path)
    check_strings_locales()

    for item in warnings:
        print('warning: ' + item)
    if problems:
        print('')
        print('静态预检失败（' + str(len(problems)) + ' 项）：')
        for item in problems:
            print('  - ' + item)
        return 1
    print('静态预检通过。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
