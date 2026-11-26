/* 单线程内存撮合基线；不连接数据库、Kafka 或 Redis，不设定机器相关的失败阈值。 */
package com.itranswarp.exchange;

import static com.itranswarp.exchange.SecuritiesCoreTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.itranswarp.exchange.core.SecuritiesEngine;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.securities.Currency;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "securities.benchmark", matches = "true")
class SecuritiesBenchmarkTest {
    private static final long SYMBOL = 1001;
    private static final int PAIRS = 1500;

    /** 两轮预热、三轮采样；交换两种场景的运行顺序。 */
    @Test void measureCoreAndRecovery() {
        for (int round = -2; round < 3; round++) {
            measure(round, round % 2 == 0 ? 0 : 250);
            measure(round, round % 2 == 0 ? 250 : 0);
        }
    }

    /** 测量固定成交序列，并断言两种恢复方式得到相同状态。 */
    private static void measure(int round, int resting) {
        var history = new History();
        var engine = new SecuritiesEngine(catalog());
        apply(engine, history, 2, new CreditCash(Currency.USD, n("1000000")));
        apply(engine, history, 3, new CreditPosition(SYMBOL, n("10000")));
        for (int i = 0; i < resting; i++)
            assertTrue(apply(engine, history, 2, new PlaceOrder(SYMBOL, Direction.BUY, n("1"), n("1"))).accepted());
        int start = history.events.size();
        for (int i = 0; i < PAIRS; i++) {
            history.add(3, new PlaceOrder(SYMBOL, Direction.SELL, n("20"), n("1")));
            history.add(2, new PlaceOrder(SYMBOL, Direction.BUY, n("20"), n("1")));
        }
        var samples = new long[PAIRS * 2];
        long elapsed = 0;
        var snapshot = engine.snapshot();
        int checkpoint = start + samples.length * 9 / 10;
        for (int i = 0; i < samples.length; i++) {
            long began = System.nanoTime();
            var result = engine.execute(history.events.get(start + i), SecuritiesEngine.Mode.LIVE);
            samples[i] = System.nanoTime() - began;
            elapsed += samples[i];
            var execution = result.orElseThrow();
            assertTrue(execution.accepted());
            assertEquals(i % 2, execution.trades().size());
            if (start + i + 1 == checkpoint) snapshot = engine.snapshot();
        }
        var expected = engine.snapshot();
        assertEquals(resting, expected.orders().size());
        var full = new SecuritiesEngine(catalog());
        long began = System.nanoTime();
        replay(full, history, 0);
        long fullNs = System.nanoTime() - began;
        var suffix = new SecuritiesEngine(catalog());
        began = System.nanoTime();
        suffix.restore(snapshot);
        replay(suffix, history, checkpoint);
        long suffixNs = System.nanoTime() - began;
        assertEquals(expected, full.snapshot());
        assertEquals(expected, suffix.snapshot());
        if (round < 0) return;
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT,
                "BENCH round=%d resting=%d events/s=%.1f p95_us=%.2f p99_us=%.2f full_ms=%.2f suffix_ms=%.2f%n",
                round + 1, resting, samples.length * 1e9 / elapsed,
                percentile(samples, .95) / 1e3, percentile(samples, .99) / 1e3, fullNs / 1e6, suffixNs / 1e6);
    }

    /** 按序重放指定后缀。 */
    private static void replay(SecuritiesEngine engine, History history, int from) {
        for (int i = from; i < history.events.size(); i++)
            engine.execute(history.events.get(i), SecuritiesEngine.Mode.REPLAY);
    }

    /** 按 nearest-rank 读取分位。 */
    private static long percentile(long[] sorted, double level) {
        return sorted[(int) Math.ceil(level * sorted.length) - 1];
    }
}
