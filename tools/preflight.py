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


def check_comment_delimiters_in_strings(path):
    """
    源码字符串里出现注释起止符会破坏 app/src/test/python 那套契约测试。

    它们先用正则把注释剥掉再抽取结构（例如 entry<AppRoute.X>）；一旦某行字符串里
    写了通配符 mime（星号斜杠星号），剥注释阶段会把后面整段代码当成块注释吞掉，
    断言就以「未抽到任何 entry」这种莫名其妙的形式失败。
    """
    # 只有被契约测试做「注释剥离」的那几个文件才受这条约束；其它文件里的
    # "*/*"、"image/*" 之类字符串不会进入那套正则，报出来只会是噪音。
    if not path.endswith(('AgentAppRoot.kt', 'AppRoute.kt', 'AgentNavigator.kt')):
        return
    open_marker = '/' + '*'
    close_marker = '*' + '/'
    literal_pattern = re.compile(r'"((?:[^"\\]|\\.)*)"')
    lines = read(path).split(chr(10))
    for index, line in enumerate(lines):
        stripped = line.strip()
        # 注释行本身必然含起止符，跳过；只看真正的字符串字面量内部。
        if stripped.startswith('//') or stripped.startswith('*') or stripped.startswith(open_marker):
            continue
        for literal in literal_pattern.findall(line):
            if open_marker in literal or close_marker in literal:
                problems.append(
                    path + ':' + str(index + 1) + ' 字符串里出现注释起止符，会破坏契约测试的注释剥离：'
                    + stripped[:60])
                break


def check_doc_comment_integrity(path):
    """
    注释内部又出现注释开头，通常意味着「代码被插进了注释与声明之间」。

    这个形态在本仓库已经造成过三次事故：新方法被插到上一个成员的文档注释与它
    自己的声明之间，于是注释被拆成两段、中间那段正文变成游离代码，编译期以
    「private 成员 Unresolved」这种完全无关的形式报错。
    """
    marker = '/' + '*'
    closing = '*' + '/'
    for index, line in enumerate(read(path).split(chr(10)), 1):
        stripped = line.strip()
        if not stripped.startswith(marker):
            continue
        rest = stripped[len(marker):]
        if marker in rest:
            problems.append(
                path + ':' + str(index + 1) + ' 注释内部又出现注释开头（疑似代码被插进注释与声明之间）')
    for index, line in enumerate(read(path).split(chr(10)), 1):
        stripped = line.strip()
        if not stripped.startswith('*'):
            continue
        opens = stripped.count(marker)
        closes = stripped.count(closing)
        # 注释正文里成对引用的写法（例如上游文档里的 /* 40.dp */）是正当用法，
        # 只有不成对的开头才说明这段注释被拆坏了。
        if opens > closes:
            problems.append(
                path + ':' + str(index + 1) + ' 注释正文里出现未闭合的注释开头（疑似注释被拆开）')


def check_loading_indicator_wrappers(path):
    """
    业务文件禁止直接实例化进度/加载指示器，必须走 EtaLoadingSlot / EtaProgressBar。

    与 app/src/test/kotlin/.../design/DesignSystemRulesTest 同源的规则；放在这里是为了
    在编译前就失败，而不是等一轮 6 分钟的单元测试。
    """
    wrapper = 'ui/adaptive/EtaLoading.kt'
    if path.replace(chr(92), '/').endswith(wrapper):
        return
    if '/agent/browser/ported/' in path:
        return
    # 与 DesignSystemRulesTest 里的 legacy 白名单保持一致：这些是明确不迁的存量文件。
    legacy = (
        'ui/components/ContainedMorphLoadingIndicator.kt',
        'ui/components/AgentChatBody.kt',
        'ui/components/AgentChatComposerM3.kt',
        'ui/components/AgentChatInputBar.kt',
        'ui/components/AgentStatusCard.kt',
        'ui/components/ChatImagePreview.kt',
        'ui/components/ChatMessageItem.kt',
        'ui/components/ConversationSidePaneScaffold.kt',
        'ui/components/SpeechPlaybackButton.kt',
        'ui/screens/terminal/ConsoleScreen.kt',
        'ui/screens/terminal/SharedFoldersScreen.kt',
        'ui/screens/virtualdisplay/VirtualDisplaySections.kt',
        'ui/screens/virtualdisplay/VirtualDisplayStage.kt',
        'ui/screens/browser/AgentBrowserScreen.kt',
        'ui/screens/search/MediaSearchScreen.kt',
        'ui/ContextCompressionDialogs.kt',
        'ui/ContextCompressionSettingsScreen.kt',
        'ui/DoubaoVoiceSettings.kt',
        'ui/OfflineSpeechPackColumn.kt',
        'agent/overlay/AgentOverlayCapsule.kt',
        'agent/voice/EtaVoicePanel.kt',
    )
    normalized = path.replace(chr(92), '/')
    if any(normalized.endswith(item) for item in legacy):
        return
    pattern = re.compile(
        r'\b(ContainedMorphLoadingIndicator|ContainedLoadingIndicator|'
        r'CircularProgressIndicator|LinearProgressIndicator|'
        r'CircularWavyProgressIndicator|LinearWavyProgressIndicator|LoadingIndicator)\s*\(')
    for index, line in enumerate(read(path).split(chr(10)), 1):
        if pattern.search(line):
            problems.append(
                path + ':' + str(index + 1) + ' 禁止直接实例化进度指示器，请改用 EtaLoadingSlot / EtaProgressBar')


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


def check_icon_imports(path):
    """
    用到的 Icons.Rounded.X 必须已 import。

    图标是按名称单独引入的（androidx.compose.material.icons.rounded.X），
    换图标时很容易只改用法、忘了改 import，编译期才报 Unresolved reference。
    """
    text = read(path)
    for match in re.finditer(r'Icons\.(Rounded|Filled|Outlined|AutoMirrored\.\w+)\.(\w+)', text):
        family = match.group(1)
        name = match.group(2)
        if family.startswith('AutoMirrored'):
            # AutoMirrored 的包路径是全小写的 icons.automirrored.<style>.<Name>
            style = family.split('.')[1].lower()
            prefix = 'androidx.compose.material.icons.automirrored.' + style + '.'
            expected = 'import ' + prefix + name
        else:
            prefix = 'androidx.compose.material.icons.' + family.lower() + '.'
            expected = 'import ' + prefix + name
        # 通配符 import 也算已引入
        if 'import ' + prefix + '*' in text:
            continue
        if expected in text:
            continue
        problems.append(path + ' 使用了 Icons.' + family + '.' + name + ' 但缺少 import：' + expected)


def check_known_symbol_usage(path):
    """known symbol 与 withContext 这类高频协程符号的使用检查。"""
    text = read(path)
    if 'withContext(' not in text:
        return
    if 'import kotlinx.coroutines.withContext' in text:
        return
    if 'import kotlinx.coroutines.*' in text:
        return
    if 'kotlinx.coroutines.withContext' in text:
        return
    problems.append(path + ' 使用了 withContext 但缺少 import：import kotlinx.coroutines.withContext')


def main():
    for path in sorted(glob.glob('app/src/main/kotlin/**/*Dao.kt', recursive=True)):
        check_dao_annotation_pairs(path)
        check_query_parameters(path)
    for path in sorted(glob.glob('app/src/main/kotlin/**/*.kt', recursive=True)):
        check_imports(path)
        check_comment_delimiters_in_strings(path)
        check_doc_comment_integrity(path)
        check_icon_imports(path)
        check_known_symbol_usage(path)
        check_loading_indicator_wrappers(path)
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
