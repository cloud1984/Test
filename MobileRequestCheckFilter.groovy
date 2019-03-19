package com.lufax.mobile.gateway.filters.pre
import com.lufax.mobile.gateway.context.LuRequestContext
import com.lufax.mobile.gateway.domain.code.ResponseCode
import com.lufax.mobile.gateway.domain.enums.RouteStrategy
import com.lufax.mobile.gateway.exception.GateException
import com.lufax.mobile.gateway.service.RoutingTableProvider
import com.lufax.mobile.gateway.service.entity.BlacklistRouteConfig
import com.lufax.mobile.gateway.service.entity.AppConfig
import com.lufax.mobile.gateway.service.entity.RouteConfig
import com.lufax.mobile.gateway.service.filter.GatewayUserContextFilter
import com.lufax.mobile.gateway.util.SpringContextUtil
import com.netflix.zuul.ZuulFilter
import org.apache.commons.lang.StringUtils

import javax.servlet.http.HttpServletRequest

class MobileRequestCheckFilter extends ZuulFilter {
    RoutingTableProvider routingTableProvider = null;
    GatewayUserContextFilter gatewayUserContextFilter = null;

    @Override
    int filterOrder() {
        return 6
    }

    @Override
    String filterType() {
        return "pre"
    }

    @Override
    boolean shouldFilter() {
        return true;
    }

    @Override
    Object run() {
        LuRequestContext context = LuRequestContext.getCurrentContext();
        String pathInfo = context.getRequest().getRequestURI();
        int maxLength = 128 * 2 + 2;
        if (StringUtils.isBlank(pathInfo) || pathInfo.length() > maxLength) {
            throw new GateException(String.format("pathInfo length not valid: %s", pathInfo), ResponseCode.BAD_REQUEST, "pathinfo_length_invalid");
        }
        String[] strings = StringUtils.split(pathInfo, '/');
        String errorCause = context.getRequestPath();
        if (strings == null || strings.length < 3) {
            throw new GateException(String.format("Split request path error, Request Path = [%s]", errorCause), ResponseCode.REQUEST_URL_NOT_AVAILABLE, ResponseCode.REQUEST_URL_NOT_AVAILABLE.toString());
        }
        String appCode = strings[1];
        String routeCode = StringUtils.join(strings, "/", 2, strings.length);

        RouteConfig routeConfig = getRouteConfig(context.getRequest(), appCode, routeCode, errorCause);

        if (routeConfig == null) {
            throw new GateException(String.format("Cannot find rout record, Request Path = [%s]", errorCause), ResponseCode.ROUTE_RECORD_NOT_FOUND, ResponseCode.ROUTE_RECORD_NOT_FOUND.toString());
        }
        context.setRouteConfig(routeConfig);
        context.setAppName(routeConfig.getAppName());
        context.setServiceURL(routeConfig.getServiceUrl());
    }

    private RouteConfig buildRouteConfig(AppConfig appConfig, String routeCode, boolean isIgnore) {
        RouteConfig routeConfig = new RouteConfig();
        routeConfig.setAppName(appConfig.getAppName());
        routeConfig.setRouteUrl(routeCode);
        routeConfig.setHystrixGroupName(appConfig.getAppName().toLowerCase());
        String key = appConfig.getAppName() + ":" + routeCode.toLowerCase();
        key = key.toLowerCase();
        routeConfig.setHystrixCommandKey(key);
        routeConfig.setServiceUrl(routeCode);
        routeConfig.setRouteCode(routeCode.toLowerCase())
        routeConfig.setEnableRateLimit(appConfig.getEnableRateLimit());
        routeConfig.setNeedAuth(!isIgnore);
        routeConfig.setEnableCircuitBreaker(true);
        routeConfig.setMaxConcurrentRequests(appConfig.getMaxConcurrentRequests());
        routeConfig.setRequestVolumeThreShold(appConfig.getRequestVolumeThreShold());
        routeConfig.setErrorThresholdPercentage(appConfig.getErrorThresholdPercentage());
        routeConfig.setExtInfo(appConfig.getExtInfo());

        return routeConfig;
    }

    private boolean isInBlackList(String appCode, String routeCode) {
        Map<String, Map<String, BlacklistRouteConfig>> appRoutesMap = routingTableProvider.getBlacklistRoutingTable();

        if (appRoutesMap == null || appRoutesMap.isEmpty()) {
            return false;
        }
        Map<String, BlacklistRouteConfig> routeConfigMap = appRoutesMap.get(appCode);
        if (routeConfigMap == null || routeConfigMap.isEmpty()) {
            return false;
        }
        BlacklistRouteConfig routeConfig = routeConfigMap.get(routeCode);
        if (routeConfig == null) {
            return false;
        }
        return true;
    }

    private RouteConfig getRouteConfig(HttpServletRequest request, String appCode, String routeCode, String errorCause) {
        //region check appCode routeCode
        if (StringUtils.isBlank(appCode)) {
            throw new GateException(String.format("AppCode missing, Request Path = [%s]", errorCause), ResponseCode.APP_CODE_NOT_AVAILABLE, ResponseCode.APP_CODE_NOT_AVAILABLE.toString());
        }
        if (StringUtils.isBlank(routeCode)) {
            throw new GateException(String.format("RouteCode missing, Request Path = [%s]", errorCause), ResponseCode.ROUTE_CODE_NOT_AVAILABLE, ResponseCode.ROUTE_CODE_NOT_AVAILABLE.toString());
        }
        appCode = appCode.trim().toLowerCase()
        routeCode = routeCode.trim()
        
        LuRequestContext.getCurrentContext().setRoute(appCode + "-" + routeCode.toLowerCase())
        
        if (routingTableProvider == null) {
            try {
                routingTableProvider = (RoutingTableProvider) SpringContextUtil.getBean(RoutingTableProvider.class);
            } catch (Exception ex) {
                throw new GateException(ex.message, ResponseCode.REMOTE_APP_NOT_AVAILABLE, "get_routingTableProvider_fail");
            }
        }

        AppConfig appConfig = routingTableProvider.getAppConfigMap().get(appCode);
        if (appConfig == null) {
            throw new GateException("app code not exists", ResponseCode.APP_CODE_NOT_AVAILABLE, "app_code_not_exists");
        }

        if (gatewayUserContextFilter == null) {
            try {
                gatewayUserContextFilter = (GatewayUserContextFilter) SpringContextUtil.getBean(GatewayUserContextFilter.class);
            } catch (Exception ex) {
                throw new GateException(ex.message, ResponseCode.SYSTEM_IN_INITIALIZING_STATE, "get_gatewayUserContextFilter_fail");

            }
        }

        boolean isIgnore = gatewayUserContextFilter.isMatchIgnore(request);
        if (appConfig.getRouteStrategy() != null && appConfig.getRouteStrategy() == RouteStrategy.BLACKLIST.getValue()) {
            boolean isInBlackList = isInBlackList(appCode, routeCode.toLowerCase());
            if (isInBlackList) {
                throw new GateException(String.format("In blacklist, Request Path = [%s]", errorCause), ResponseCode.SERVICE_IN_BLACKLIST, ResponseCode.SERVICE_IN_BLACKLIST.toString());
            }
            return buildRouteConfigByStrategy(appConfig, routeCode, RouteStrategy.BLACKLIST, isIgnore);
        } else {
            //默认白名单
            return buildRouteConfigByStrategy(appConfig, routeCode, RouteStrategy.WHITELIST, isIgnore);
        }
    }

    private RouteConfig buildRouteConfigByStrategy(AppConfig appConfig, String routeCode, RouteStrategy routeStrategy, Boolean isIgnore) {
        Map<String, Map<String, RouteConfig>> appRoutesMap = routingTableProvider.getRoutingTable();
        RouteConfig routeConfig = null;
        String appCode = appConfig.getAppCode().toLowerCase()
        if (routeStrategy == RouteStrategy.WHITELIST) {
            if (appRoutesMap == null || appRoutesMap.isEmpty()) {
                throw new GateException("routing table is null or empty", ResponseCode.SYSTEM_IN_INITIALIZING_STATE, "routesMap_null_or_empty");
            }

            Map<String, RouteConfig> routeConfigMap = appRoutesMap.get(appCode);
            if (routeConfigMap == null || routeConfigMap.isEmpty()) {
                throw new GateException("app code not exists", ResponseCode.APP_CODE_NOT_AVAILABLE, "app_code_not_exists");
            }

            routeConfig = routeConfigMap.get(routeCode.toLowerCase());
            if (routeConfig == null) {
                throw new GateException("route code not exists", ResponseCode.ROUTE_RECORD_NOT_FOUND, "route_code_not_exists");
            }
            routeConfig.setNeedAuth(!isIgnore)
        } else {
            if (appRoutesMap != null && !appRoutesMap.isEmpty()) {
                Map<String, RouteConfig> routeConfigMap = appRoutesMap.get(appCode);
                if (routeConfigMap != null && !routeConfigMap.isEmpty()) {
                    routeConfig = routeConfigMap.get(routeCode.toLowerCase());
                }
            }
            if (routeConfig == null) {
                routeConfig = buildRouteConfig(appConfig, routeCode, isIgnore);
            }
        }
        return routeConfig;
    }
}


