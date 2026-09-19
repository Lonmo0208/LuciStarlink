/**
 * 开发/基准测试装置 —— **不是模组运行时的一部分**，但故意留在主源码集里。
 *
 * <p>为什么留在这里：测量工具必须与被测代码一起构建，才能保证「测的就是发布的那份」——
 * {@code LuxServerBenchmark} 在服务端跑基准并打印 {@code LUCIS_BENCH_RESULT} 行，
 * {@code LuxBenchmarkSupport} 提供计数器与计时，{@code LuxClientLoadDiagnostics} 是客户端负载诊断。
 * 对照用的 rig jar（`mc-smoketest/rig-jar.jar`）就是用带 {@code -PbenchmarkAllow*} 的构建产出的，
 * 里面的类必须齐全。
 *
 * <p>代价与纪律：这些类会被打进正式发布件（体积很小，且默认全部关闭 —— 只由
 * {@code -Dlucistarlink.debug=true} / {@code -Dlucistarlink.benchmark=true} 之类的系统属性激活），
 * 所以**任何测量代码都不得改变默认行为**；反过来，凡是被搬进这个包的东西，都要在
 * {@code docs/verify-baseline.txt} 里说清它是怎么被驱动的。
 */
package dev.lucistarlink.test;
