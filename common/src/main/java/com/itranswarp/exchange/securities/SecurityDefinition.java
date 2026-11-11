/* 不可变证券规则，由数据库目录加载；第一版只接受美元计价、分价格和整数股。 */
package com.itranswarp.exchange.securities;

import java.math.BigDecimal;
import java.util.Objects;

public record SecurityDefinition(long securityId, String symbol, String name, Currency quoteCurrency,
        BigDecimal priceTick, BigDecimal quantityStep, SecurityStatus status, int definitionVersion) {
    /** 创建并校验证券规则；不允许借助配置偷偷启用碎股或非美元市场。 */
    public SecurityDefinition {
        if (securityId <= 0 || symbol == null || !symbol.matches("[A-Z][A-Z0-9]{0,15}")
                || name == null || name.isBlank() || name.length() > 100
                || quoteCurrency != Currency.USD || definitionVersion != 1) {
            throw new IllegalArgumentException("无效或不支持的证券定义");
        }
        Objects.requireNonNull(status, "证券状态必填");
        if (priceTick == null || priceTick.compareTo(new BigDecimal("0.01")) != 0
                || quantityStep == null || quantityStep.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("第一版仅支持 0.01 USD 价格步长和 1 股数量步长");
        }
    }

    /** 校验新委托，拒绝超范围和不对齐的输入，不截断或修改用户输入。 */
    public void validateOrder(BigDecimal price, BigDecimal quantity) {
        if (status != SecurityStatus.ACTIVE) throw new IllegalArgumentException("证券暂停交易");
        requireStep(price, priceTick, new BigDecimal("9999999999.99"), "价格");
        requireStep(quantity, quantityStep, new BigDecimal("1000000000"), "股数");
    }

    /** 先检查数值和精度上限，再做余数运算，避免异常规模的十进制输入。 */
    private static void requireStep(BigDecimal value, BigDecimal step, BigDecimal maximum, String field) {
        if (value == null || value.signum() <= 0 || value.precision() > 36
                || value.scale() < -18 || value.scale() > 18 || value.compareTo(maximum) > 0
                || value.remainder(step).signum() != 0)
            throw new IllegalArgumentException(field + "必须为范围内的正数且符合步长");
    }
}
