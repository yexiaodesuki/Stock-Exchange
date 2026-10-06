/*
 * 事件执行模式：实时执行保留外部输出，历史重放只重建交易状态。
 */
package com.itranswarp.exchange;

/** 由引擎内部选择执行模式，不属于 Kafka 事件协议。 */
enum EventExecutionMode {
    LIVE,
    REPLAY
}
