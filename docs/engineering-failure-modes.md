# 构建失败模式与处理步骤

记录本项目 CI 上真实发生过的失败类型、根因与对应处理步骤，避免重复踩。
凡是「本可静态发现」的，都已经收敛到 `tools/preflight.py`，并在
`build-debug.yml` 与 `auto-release.yml` 的编译之前执行。

## 1. DAO 注解与函数声明被拆开

**现象**：KSP 阶段报 `Each bind variable in the query must have a matching function parameter` 或 `An abstract DAO function must be annotated with one and only one of ...`。

**根因**：在某个函数声明「之前」插入新方法时，容易插到上一个方法的 `@Query`
与它自己的声明之间。于是那个 `@Query` 挂到了新方法上，原方法变成没有注解的
抽象函数。

**处理步骤**：

1. 插入 DAO 方法时，锚点选「上一个完整函数块之后」，不要选「函数声明行之前」。
2. 提交前跑 `python3 tools/preflight.py`，它会检查每个注解后面是否紧跟声明。

## 2. 缺 import

**现象**：`Unresolved reference 'xxx'`。

**根因**：新增代码引用跨包符号时凭记忆写，没核对是否已 import。

**处理步骤**：

1. 新增跨包引用后立刻确认 `import` 行存在。
2. `preflight.py` 覆盖了一批高频符号（`AndroidAgentLogger`、`safeLogType`、
   分析导入导出相关类、`DatabaseTestRetryRule`）；用全限定名引用时不算缺 import。
3. 扩充脚本里的 `KNOWN_SYMBOLS` 就能持续覆盖新的高频符号。

## 3. @Query 绑定变量与参数不一致

**现象**：KSP 报缺参数。

**根因**：改 SQL 或改了参数名，只改了一边。

**处理步骤**：同上，`preflight.py` 会比对 `:name` 与函数参数列表。
它只在冒号前是等号、逗号、左括号或空白时才认作绑定变量，避免把
`https://host`、`{"k":true}` 里的冒号当成参数。

## 4. 契约测试与实现不一致

**现象**：Python 契约测试失败，例如要求 `supportsGptSpeedBinding(runProvider, runModel)`。

**根因**：改动了被断言的具体表达式，但没看测试。

**处理步骤**：

1. 改动可能是「契约」的表达式前，先 `grep app/src/test/python` 看有没有断言它。
2. 如果实现语义确实要变，同步改契约测试并在提交信息里说明为什么变。

## 5. Robolectric 与共享 DB 的环境性失败

**现象**：`Failed to fetch maven artifact ...android-all-instrumented`（429 限流），
或迁移测试报 `expected:<41> but was:<5>`、`Can't downgrade database from 41 to 38`。

**根因**：同一 JVM 内所有测试类共享应用私有目录，别的测试类留下的 SQLite 连接
不归单例管，清库后仍可能把旧版本写回；429 是 Maven Central 限流。

**处理步骤**：

1. 先判断是不是环境问题：失败用例每次不同、同类其它用例都通过 → 环境。
2. 已知脆弱类挂 `DatabaseTestRetryRule`（确定性重试，持续失败仍然会红）。
3. `setUp` 里的清库改为重试删除，删不掉时明确报错，不要让它伪装成业务断言失败。
4. 纯 429 的失败直接重跑，不要改业务代码。

## 6. Gradle 插件解析失败

**现象**：`Plugin [id: 'org.gradle.toolchains.foojay-resolver-convention', version: '1.0.0'] was not found`。

**根因**：Gradle Plugin Portal 瞬时不可用，`settings.gradle.kts` 本身没有变化。

**处理步骤**：确认该文件近期无改动后直接重跑；不要为此改构建脚本。

## 7. 发布链路

- 只有 `versionName` 变化且对应 tag 不存在时才会真正发版；文档提交会被跳过。
- 已经失败过、但 `versionName` 未变的提交，需要手动触发 `auto-release.yml`
  才会重新构建发布。
- 创建 Release 前会检查同名 Release 是否存在，避免与 tag 触发的路径撞车。

## 8. 提交前自检清单

1. `python3 tools/preflight.py`
2. 改 DAO：确认注解与声明相邻
3. 改被测试断言的表达式：`grep app/src/test`
4. 新增文案：三语 `strings.xml` 的 key 数量一致
5. 一次提交只做一类改动，便于失败时二分定位
