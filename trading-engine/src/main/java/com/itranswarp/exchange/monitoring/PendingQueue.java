/* 输出任务计数队列：保持 FIFO 和并发语义，size 为常数时间，监控不遍历业务任务。 */
package com.itranswarp.exchange.monitoring;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class PendingQueue<E> extends AbstractQueue<E> {
    private final ConcurrentLinkedQueue<E> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();

    /** 入队前计数，允许并发采样短暂高估；非法元素不会改变计数或队列。 */
    @Override
    public boolean offer(E element) {
        java.util.Objects.requireNonNull(element);
        pending.incrementAndGet();
        try {
            return queue.offer(element);
        } catch (RuntimeException | Error e) {
            pending.decrementAndGet();
            throw e;
        }
    }

    /** 出队成功后减少计数，空队列轮询不减少；出队不代表输出已经成功。 */
    @Override
    public E poll() {
        E value = queue.poll();
        if (value != null) pending.decrementAndGet();
        return value;
    }

    /** 只查看队首，不改变待处理任务数。 */
    @Override
    public E peek() { return queue.peek(); }

    /** 返回近似待出队任务数；不包含线程已取出但尚未处理成功的任务。 */
    @Override
    public int size() { return pending.get(); }

    /** 提供只读弱一致迭代器，禁止绕过 poll 删除导致计数失真。 */
    @Override
    public Iterator<E> iterator() {
        return java.util.Collections.unmodifiableCollection(queue).iterator();
    }

    /** 通过统一出队路径清空队列，不破坏计数；业务不会用此方法替代可靠输出。 */
    @Override
    public void clear() { while (poll() != null) { /* 每次出队更新计数。 */ } }
}
