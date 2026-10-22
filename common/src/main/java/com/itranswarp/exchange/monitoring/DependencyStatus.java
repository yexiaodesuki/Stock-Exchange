/* 依赖检查缓存：尚未探测和结果过期都不能假装成功；不包含连接串和异常内容。 */
package com.itranswarp.exchange.monitoring;

import java.util.function.LongSupplier;

public final class DependencyStatus {
    private final LongSupplier clock;
    private final long validityNanos;
    private volatile long checked;
    private volatile long timestamp;
    private volatile boolean success;
    private volatile boolean known;

    /** 使用单调时钟判断有效期，墙上时钟只用于展示最近检查时间。 */
    public DependencyStatus(long validityMs) { this(System::nanoTime, validityMs); }

    /** 测试注入时钟，不使用睡眠验证过期。 */
    public DependencyStatus(LongSupplier clock, long validityMs) {
        this.clock = clock;
        this.validityNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(validityMs);
    }

    /** 一次探测完成后发布结果；known 最后赋值保证首次读取可见。 */
    public void record(boolean success) {
        this.success = success;
        this.checked = clock.getAsLong();
        this.timestamp = System.currentTimeMillis();
        this.known = true;
    }

    /** 只有检查过且未过期时才有有效结论。 */
    public boolean fresh() { return known && clock.getAsLong() - checked <= validityNanos; }

    /** 过期的旧成功也返回不可用。 */
    public boolean up() { return fresh() && success; }

    /** 返回最近完成检查的 Unix 秒，首次检查前为零。 */
    public double checkedAt() { return timestamp / 1000.0; }
}
