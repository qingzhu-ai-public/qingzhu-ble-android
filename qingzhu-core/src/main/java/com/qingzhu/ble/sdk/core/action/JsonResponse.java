// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.action;

/** 沿用现有系统"统一 JSON 请求/响应"契约（接入方自始至终只见 JSON）。 */
public final class JsonResponse {

    private final boolean success;
    private final int errorCode;
    private final String message;
    private final String dataJson;

    private JsonResponse(boolean success, int errorCode, String message, String dataJson) {
        this.success = success;
        this.errorCode = errorCode;
        this.message = message;
        this.dataJson = dataJson;
    }

    public static JsonResponse ok(String dataJson) {
        return new JsonResponse(true, 0, null, dataJson);
    }

    public static JsonResponse error(int errorCode, String message) {
        return new JsonResponse(false, errorCode, message, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public String getDataJson() {
        return dataJson;
    }

    @Override
    public String toString() {
        return success
                ? "{\"success\":true,\"data\":" + (dataJson == null ? "null" : dataJson) + "}"
                : "{\"success\":false,\"errorCode\":" + errorCode + ",\"message\":\"" + message + "\"}";
    }
}
