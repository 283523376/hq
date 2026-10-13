/**
 * 永鑫WMS 业务模块根包。
 *
 * <p>覆盖三类仓储场景：半成品立体仓、成品敏捷仓、线边柜；需求来源为工作区的
 * {@code wms-blueprint/}（《永鑫WMS软件蓝图》中的 {@code YX-WMS-*} 流程即为本模块要实现的需求集合）。
 *
 * <p>约定：业务表统一以 {@code wms_} 为前缀；所有对外接口统一返回
 * {@link org.jeecg.common.api.vo.Result}。
 */
package org.jeecg.modules.wms;
