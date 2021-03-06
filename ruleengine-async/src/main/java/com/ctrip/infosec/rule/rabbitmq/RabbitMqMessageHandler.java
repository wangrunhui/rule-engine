/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.ctrip.infosec.rule.rabbitmq;

import com.ctrip.infosec.common.Constants;
import com.ctrip.infosec.common.model.RiskFact;
import com.ctrip.infosec.common.model.RiskResult;
import com.ctrip.infosec.configs.event.*;
import com.ctrip.infosec.configs.event.enums.PersistColumnSourceType;
import com.ctrip.infosec.configs.rule.monitor.RuleMonitorRepository;
import com.ctrip.infosec.configs.rule.trace.logger.TraceLogger;
import com.ctrip.infosec.configs.rulemonitor.RuleMonitorHelper;
import com.ctrip.infosec.configs.rulemonitor.RuleMonitorType;
import com.ctrip.infosec.configs.utils.EventBodyUtils;
import com.ctrip.infosec.configs.utils.Utils;
import com.ctrip.infosec.rule.Contexts;
import com.ctrip.infosec.rule.convert.RiskFactPersistStrategy;
import com.ctrip.infosec.rule.convert.internal.InternalRiskFact;
import com.ctrip.infosec.rule.convert.offline4j.RiskEventConvertor;
import com.ctrip.infosec.rule.convert.persist.*;
import com.ctrip.infosec.rule.executor.*;
import com.ctrip.infosec.rule.utils.ValueExtractUtils;
import com.ctrip.infosec.sars.monitor.SarsMonitorContext;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.meidusa.fastjson.JSON;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static com.ctrip.infosec.common.SarsMonitorWrapper.*;
import static com.ctrip.infosec.configs.utils.EventBodyUtils.valueAsString;

/**
 * @author zhengby
 */
public class RabbitMqMessageHandler {

    private static Logger logger = LoggerFactory.getLogger(RabbitMqMessageHandler.class);

    @Autowired
    private RulesExecutorService rulesExecutorService;
    @Autowired
    private PreRulesExecutorService preRulesExecutorService;
    @Autowired
    private PostRulesExecutorService postRulesExecutorService;
    @Autowired
    private ModelRulesExecutorService modelRulesExecutorService;
    @Autowired
    private DispatcherMessageSender dispatcherMessageSender;
    @Autowired
    private CallbackMessageSender callbackMessageSender;
    @Autowired
    private EventDataMergeService eventDataMergeService;
    @Autowired
    private OfflineMessageSender offlineMessageSender;
    @Autowired
    private CounterPushRulesExecutorService counterPushRuleExrcutorService;
    @Autowired
    private RiskEventConvertor riskEventConvertor;
    @Autowired
    private Offline4jService offline4jService;

    public void handleMessage(Object message) throws Exception {
        RiskFact fact = null;
        String factTxt;
        InternalRiskFact internalRiskFact = null;
        try {

            if (message instanceof byte[]) {
                factTxt = new String((byte[]) message, Constants.defaultCharset);
            } else if (message instanceof String) {
                factTxt = (String) message;
            } else {
                throw new IllegalArgumentException("消息格式只支持\"String\"或\"byte[]\"");
            }

            logger.info("MQ: fact=" + factTxt);
            fact = JSON.parseObject((String) factTxt, RiskFact.class);
            Contexts.setAsync(true);
            Contexts.setLogPrefix("[" + fact.eventPoint + "][" + fact.eventId + "] ");
            SarsMonitorContext.setLogPrefix(Contexts.getLogPrefix());

            boolean traceLoggerEnabled = MapUtils.getBoolean(fact.ext, Constants.key_traceLogger, true);
            TraceLogger.enabled(traceLoggerEnabled);

            RuleMonitorHelper.newTrans(fact, RuleMonitorType.CP_ASYNC);

            // 引入节点编号优化排序
            // S0 - 接入层同步前
            // S1 - 同步引擎
            // S2 - 接入层同步后
            // S3 - 异步引擎
            // 执行数据合并（GET）
            try {
                RuleMonitorHelper.newTrans(fact, RuleMonitorType.GET);
                TraceLogger.beginTrans(fact.eventId, "S3");
                TraceLogger.setLogPrefix("[异步数据合并]");
                eventDataMergeService.executeRedisGet(fact);
            } finally {
                TraceLogger.commitTrans();
                RuleMonitorHelper.commitTrans(fact);
            }
            // 执行预处理            
            try {
                RuleMonitorHelper.newTrans(fact, RuleMonitorType.PRE_RULE_WRAP);
                TraceLogger.beginTrans(fact.eventId, "S3");
                TraceLogger.setLogPrefix("[异步预处理]");
                preRulesExecutorService.executePreRules(fact, true);
            } finally {
                TraceLogger.commitTrans();
                RuleMonitorHelper.commitTrans(fact);
            }
            // 执行异步规则
            try {
                RuleMonitorHelper.newTrans(fact, RuleMonitorType.RULE_WRAP);
                TraceLogger.beginTrans(fact.eventId, "S3");
                TraceLogger.setLogPrefix("[异步规则]");
                rulesExecutorService.executeAsyncRules(fact);
            } finally {
                TraceLogger.commitTrans();
                RuleMonitorHelper.commitTrans(fact);
            }
            // 执行模型规则（异步）
            try {
                RuleMonitorHelper.newTrans(fact, RuleMonitorType.MODEL_RULE_WRAP);
                TraceLogger.beginTrans(fact.eventId, "S3");
                TraceLogger.setLogPrefix("[模型规则]");
                modelRulesExecutorService.executeModelRules(fact);
            } finally {
                TraceLogger.commitTrans();
                RuleMonitorHelper.commitTrans(fact);
            }
            // 执行后处理
            try {
                RuleMonitorHelper.newTrans(fact, RuleMonitorType.POST_RULE_WRAP);
                TraceLogger.beginTrans(fact.eventId, "S3");
                TraceLogger.setLogPrefix("[异步后处理]");
                postRulesExecutorService.executePostRules(fact, true);
            } finally {
                TraceLogger.commitTrans();
                RuleMonitorHelper.commitTrans(fact);
            }
            // 执行数据合并（PUT）
            try {
                RuleMonitorHelper.newTrans(fact, RuleMonitorType.PUT);
                TraceLogger.beginTrans(fact.eventId, "S3");
                TraceLogger.setLogPrefix("[异步数据合并]");
                eventDataMergeService.executeRedisPut(fact);
            } finally {
                TraceLogger.commitTrans();
                RuleMonitorHelper.commitTrans(fact);
            }
            //Counter推送规则处理
            try {
                RuleMonitorHelper.newTrans(fact, RuleMonitorType.PUSH_WRAP);
                TraceLogger.beginTrans(fact.eventId, "S3");
                TraceLogger.setLogPrefix("[Counter推送]");
                counterPushRuleExrcutorService.executeCounterPushRules(fact, true);
            } finally {
                TraceLogger.commitTrans();
                RuleMonitorHelper.commitTrans(fact);
            }

            RuleMonitorHelper.commitTrans(fact);

            // -------------------------------- 规则引擎结束 -------------------------------------- //
            beforeInvoke("CardRiskDB.CheckResultLog.saveRuleResult");
            Long riskReqId = MapUtils.getLong(fact.ext, Constants.key_reqId);
            boolean outerReqId = riskReqId != null;
            internalRiskFact = offline4jService.saveForOffline(fact);
            if (internalRiskFact != null && internalRiskFact.getReqId() > 0) {
                riskReqId = internalRiskFact.getReqId();
            }

            // 落地规则结果
            beforeInvoke("CardRiskDB.CheckResultLog.saveRuleResult");
            try {
                TraceLogger.beginTrans(fact.eventId, "S3");
                TraceLogger.setLogPrefix("[保存CheckResultLog]");
                if (riskReqId != null && riskReqId > 0) {
                    TraceLogger.traceLog("reqId = " + riskReqId);
                    saveRuleResult(riskReqId, fact, fact.whitelistResults, outerReqId);
                    saveRuleResult(riskReqId, fact, fact.results, outerReqId);
                    saveRuleResult(riskReqId, fact, fact.results4Async, outerReqId);
                    saveRuleResult(riskReqId, fact, fact.resultsGroupByScene, outerReqId);
                    saveRuleResult(riskReqId, fact, fact.resultsGroupByScene4Async, outerReqId);
                }
            } catch (Exception ex) {
                fault("CardRiskDB.CheckResultLog.saveRuleResult");
                logger.error(Contexts.getLogPrefix() + "保存规则执行结果至[InfoSecurity_CheckResultLog]表时发生异常.", ex);
            } finally {
                long usage = afterInvoke("CardRiskDB.CheckResultLog.saveRuleResult");
                TraceLogger.traceLog("耗时: " + usage + "ms");
                TraceLogger.commitTrans();
            }

        } catch (Throwable ex) {
            logger.error(Contexts.getLogPrefix() + "invoke handleMessage exception.", ex);
        } finally {
            if (fact != null) {
                // 发送给DataDispatcher
                try {
                    beforeInvoke("DataDispatcher.sendMessage");
                    dispatcherMessageSender.sendToDataDispatcher(fact);
                } catch (Exception ex) {
                    fault("DataDispatcher.sendMessage");
                    logger.error(Contexts.getLogPrefix() + "send dispatcher message fault.", ex);
                } finally {
                    afterInvoke("DataDispatcher.sendMessage");
                }

                int riskLevel = MapUtils.getInteger(fact.finalResult, Constants.riskLevel, 0);
                if (riskLevel > 0) {
                    // 发送Offline4J
                    if (internalRiskFact != null && MapUtils.getBoolean(fact.ext, Offline4jService.PUSH_OFFLINE_WORK_ORDER_KEY, false)) {
                        beforeInvoke("Offline.sendMessage");
                        try {
                            Object eventObj = riskEventConvertor.convert(internalRiskFact, riskLevel, HeaderMappingBizType.Offline4J);
                            offlineMessageSender.sendToOffline(eventObj);
                        } catch (Exception ex) {
                            fault("Offline.sendMessage");
                            logger.error(Contexts.getLogPrefix() + "send Offline4J message fault.", ex);
                        } finally {
                            afterInvoke("Offline.sendMessage");
                        }
                    }
                }

                try {

                    //遍历fact的所有results，如果有风险值大于0的，则进行计数操作
                    boolean withScene = Constants.eventPointsWithScene.contains(fact.eventPoint);
                    if (!withScene) {
                        //非场景
                        for (Entry<String, Map<String, Object>> entry : fact.results.entrySet()) {
                            String ruleNo = entry.getKey();
                            int rLevel = NumberUtils.toInt(MapUtils.getString(entry.getValue(), Constants.riskLevel));
                            if (rLevel > 0) {
                                //获取去重字段值
                                String distinct = getDistinctValue(fact, ruleNo);
                                RuleMonitorRepository.increaseCounter(fact.getEventPoint(), ruleNo, distinct);
                            }
                        }
                        for (Entry<String, Map<String, Object>> entry : fact.results4Async.entrySet()) {
                            String ruleNo = entry.getKey();
                            int rLevel = NumberUtils.toInt(MapUtils.getString(entry.getValue(), Constants.riskLevel));
                            if (rLevel > 0) {
                                //获取去重字段值
                                String distinct = getDistinctValue(fact, ruleNo);
                                RuleMonitorRepository.increaseCounter(fact.getEventPoint(), ruleNo, distinct);
                            }
                        }
                    } else {
                        //场景
                        for (Entry<String, Map<String, Object>> entry : fact.resultsGroupByScene.entrySet()) {
                            String ruleNo = entry.getKey();
                            int rLevel = NumberUtils.toInt(MapUtils.getString(entry.getValue(), Constants.riskLevel));
                            if (rLevel > 0) {
//                                RuleMonitorRepository.increaseCounter(fact.getEventPoint(), ruleNo);
                                //获取去重字段值
                                String distinct = getDistinctValue(fact, ruleNo);
                                RuleMonitorRepository.increaseCounter(fact.getEventPoint(), ruleNo, distinct);
                            }
                        }
                        for (Entry<String, Map<String, Object>> entry : fact.resultsGroupByScene4Async.entrySet()) {
                            String ruleNo = entry.getKey();
                            int rLevel = NumberUtils.toInt(MapUtils.getString(entry.getValue(), Constants.riskLevel));
                            if (rLevel > 0) {
//                                RuleMonitorRepository.increaseCounter(fact.getEventPoint(), ruleNo);
                                //获取去重字段值
                                String distinct = getDistinctValue(fact, ruleNo);
                                RuleMonitorRepository.increaseCounter(fact.getEventPoint(), ruleNo, distinct);
                            }
                        }
                    }

                } catch (Exception ex) {
                    logger.error(Contexts.getLogPrefix() + "RuleMonitorRepository increaseCounter fault.", ex);
                }

            }
        }
    }

    //去重字段集合
    private final List<String> distinctFields = Lists.newArrayList("orderID", "OrderID", "orderId");

    /**
     * 获取命中规则的去重字段
     *
     * @param fact
     * @param ruleNo
     * @return
     */
    private String getDistinctValue(RiskFact fact, String ruleNo) {

        String distinctValue = null;

        for (String distinctField : distinctFields) {

            distinctValue = EventBodyUtils.valueAsString(fact.eventBody, distinctField);
            if (StringUtils.isNotBlank(distinctValue)) {
                //只要取到一个值，则立即跳出循环
                break;
            }
        }

        return distinctValue;
    }

    private void saveRuleResult(Long riskReqId, RiskFact fact, Map<String, Map<String, Object>> results, boolean outerReqId) throws DbExecuteException {
        String eventPoint = fact.eventPoint;
        Long orderId = ValueExtractUtils.extractLongIgnoreCase(fact.eventBody, "orderId");
        Integer orderType = ValueExtractUtils.extractIntegerIgnoreCase(fact.eventBody, "orderType");
        Integer subOrderType = ValueExtractUtils.extractIntegerIgnoreCase(fact.eventBody, "subOrderType");
        RdbmsInsert insert = new RdbmsInsert();
        DistributionChannel channel = new DistributionChannel();
        channel.setChannelNo(RiskFactPersistStrategy.allInOne4ReqId);
        channel.setDatabaseType(DatabaseType.AllInOne_SqlServer);
        channel.setChannelDesc(RiskFactPersistStrategy.allInOne4ReqId);
        channel.setDatabaseURL(RiskFactPersistStrategy.allInOne4ReqId);
        insert.setChannel(channel);

        /**
         * [LogID] = 主键 [ReqID] [RuleType] [RuleID] = 0 [RuleName] [RiskLevel]
         * [RuleRemark] [CreateDate] = now [DataChange_LastTime] = now
         * [IsHighlight] = 1
         */
        if (MapUtils.isNotEmpty(results)) {
            for (Entry<String, Map<String, Object>> entry : results.entrySet()) {
                try {
                    Long riskLevel = MapUtils.getLong(entry.getValue(), Constants.riskLevel);
                    if (riskLevel > 0) {
                        String ruleType = (String) entry.getValue().get(Constants.ruleType);//withScene ? (isAsync ? "SA" : "S") : (isAsync ? "NA" : "N");
                        TraceLogger.traceLog("[" + entry.getKey() + "] riskLevel = " + riskLevel + ", ruleType = " + ruleType);
                        insert.setTable("RiskControl_CheckResultLog");
                        insert.setColumnPropertiesMap(prepareRiskControlCheckResultLog(riskReqId, ruleType, entry, riskLevel, eventPoint, orderId, orderType, subOrderType));
                        execute(insert);
                        if ("B".equals(ruleType) || "N".equals(ruleType)) {
                            insert.setTable("InfoSecurity_CheckResultLog");
                            insert.setColumnPropertiesMap(prepareInfoSecurityCheckResultLog(riskReqId, ruleType, entry, riskLevel));
                            execute(insert);
                        }
                    }
                } catch (Exception e) {
                    logger.error(Contexts.getLogPrefix() + "save InfoSecurity_CheckResultLog failed. reqId=" + riskReqId + ", result=" + entry, e);
                }
            }
        }
    }

    private void execute(DbOperation operation) throws DbExecuteException {
        PersistContext ctx = new PersistContext();
        operation.execute(ctx);
    }

    private Map<String, PersistColumnProperties> prepareRiskControlCheckResultLog(Long riskReqId, String ruleType, Entry<String, Map<String, Object>> entry,
            Long riskLevel, String eventPoint, Long orderId, Integer orderType, Integer subOrderType) {
        Map<String, PersistColumnProperties> map = Maps.newHashMap();
        PersistColumnProperties props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DB_PK);
        props.setColumnType(DataUnitColumnType.Long);
        map.put("LogID", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.Long);
        props.setValue(riskReqId);
        map.put("RID", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.String);
        props.setValue(ruleType);
        map.put("RuleType", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.Int);
        props.setValue(MapUtils.getInteger(entry.getValue(), Constants.ruleId, 0));
        map.put("RuleID", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.String);
        if ("B".equals(valueAsString(entry.getValue(), Constants.ruleType))) {
            props.setValue(valueAsString(entry.getValue(), Constants.ruleName));
        } else {
            props.setValue(entry.getKey());
        }
        map.put("RuleName", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.Long);
        props.setValue(riskLevel);
        map.put("RiskLevel", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.String);
        props.setValue(MapUtils.getString(entry.getValue(), Constants.riskMessage));
        map.put("RuleRemark", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.CUSTOMIZE);
        props.setColumnType(DataUnitColumnType.Data);
        props.setExpression("const:now:date");
        map.put("DataChange_LastTime", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.String);
        props.setValue(eventPoint);
        map.put("EventPoint", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.Long);
        props.setValue(orderId);
        map.put("OrderId", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.Int);
        props.setValue(orderType);
        map.put("OrderType", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.Int);
        props.setValue(subOrderType);
        map.put("SubOrderType", props);
        return map;
    }

    private Map<String, PersistColumnProperties> prepareInfoSecurityCheckResultLog(Long riskReqId, String ruleType, Entry<String, Map<String, Object>> entry, Long riskLevel) {
        Map<String, PersistColumnProperties> map = Maps.newHashMap();
        PersistColumnProperties props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DB_PK);
        props.setColumnType(DataUnitColumnType.Long);
        map.put("LogID", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.Long);
        props.setValue(riskReqId);
        map.put("ReqID", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.String);
        props.setValue(ruleType);
        map.put("RuleType", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.Int);
        props.setValue(MapUtils.getInteger(entry.getValue(), Constants.ruleId, 0));
        map.put("RuleID", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.String);
        if ("B".equals(valueAsString(entry.getValue(), Constants.ruleType))) {
            props.setValue(valueAsString(entry.getValue(), Constants.ruleName));
        } else {
            props.setValue(entry.getKey());
        }
        map.put("RuleName", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.Long);
        props.setValue(riskLevel);
        map.put("RiskLevel", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.String);
        props.setValue(MapUtils.getString(entry.getValue(), Constants.riskMessage));
        map.put("RuleRemark", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.CUSTOMIZE);
        props.setColumnType(DataUnitColumnType.Data);
        props.setExpression("const:now:date");
        map.put("CreateDate", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.CUSTOMIZE);
        props.setColumnType(DataUnitColumnType.Data);
        props.setExpression("const:now:date");
        map.put("DataChange_LastTime", props);

        props = new PersistColumnProperties();
        props.setPersistColumnSourceType(PersistColumnSourceType.DATA_UNIT);
        props.setColumnType(DataUnitColumnType.Int);
        props.setValue(0);
        map.put("IsHighlight", props);

        return map;
    }

    /**
     * 组装Callback的报文
     */
    RiskResult buildRiskResult(RiskFact fact, CallbackRule callbackRule) {
        RiskResult result = new RiskResult();
        result.setEventPoint(fact.eventPoint);
        result.setEventId(fact.eventId);
        result.getResults().putAll(fact.finalResult);

        // 需要返回给PD的额外字段
        Map<String, String> fieldMapping = callbackRule.getFieldMapping();
        if (fieldMapping != null && !fieldMapping.isEmpty()) {
            for (String fieldName : fieldMapping.keySet()) {
                String newFieldName = fieldMapping.get(fieldName);
                Object fieldValue = getNestedProperty(fact, fieldName);
                if (fieldValue != null) {
                    result.getResults().put(newFieldName, fieldValue);
                }
            }
        }
//        result.getResults().put("orderId", fact.eventBody.get("orderID"));
//        result.getResults().put("hotelId", fact.eventBody.get("hotelID"));

        result.setRequestTime(fact.requestTime);
        result.setRequestReceive(fact.requestReceive);
        result.setResponseTime(Utils.fastDateFormatInMicroSecond.format(new Date()));
        return result;
    }

    Object getNestedProperty(Object factOrEventBody, String columnExpression) {
        try {
            Object value = PropertyUtils.getNestedProperty(factOrEventBody, columnExpression);
            return value;
        } catch (Exception ex) {
            logger.info(Contexts.getLogPrefix() + "getNestedProperty fault. message: " + ex.getMessage());
        }
        return null;
    }
}
