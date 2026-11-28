package io.github.mangi.eta.data.db

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * 数据库迁移类测试的确定性重试规则。
 *
 * 为什么需要：这些用例要验证「生产入口」EtaDatabase.get 打开固定库名 eta.db 的行为，
 * 而 Robolectric 让同一个 JVM 里的所有测试类共享应用私有目录。别的测试类留下的 SQLite
 * 连接不归单例管，closeForTests() 关不到；清库之后那条连接仍可能把旧版本 checkpoint
 * 写回同一路径，于是本类随机读到「不是自己造的那个库」，断言失败。
 *
 * 与其在用例里继续堆清理代码（已经加固过两轮，仍偶发），不如显式重试：
 * 只有持续失败才会真正变红，偶发的文件竞争不再产生假阴性。
 *
 * 重试前留一点退避时间，让上一条连接的 checkpoint 有机会落盘；
 * 用例自身在开头都会重建种子库，因此重跑是幂等的。
 */
internal class DatabaseTestRetryRule(private val attempts: Int = 2) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            var last: Throwable? = null
            for (attempt in 1..attempts) {
                try {
                    base.evaluate()
                    return
                } catch (failure: Throwable) {
                    last = failure
                    if (attempt < attempts) {
                        runCatching { EtaDatabase.closeForTests() }
                        Thread.sleep(50L * attempt)
                    }
                }
            }
            throw requireNotNull(last)
        }
    }
}
