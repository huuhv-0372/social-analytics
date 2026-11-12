/**
 * Cấu hình hệ thống ({@code @Configuration}): Security, Session, OAuth2 client, Async, Scheduling, JMS, WebSocket,
 * WebService, OpenAPI — kèm thành phần phụ trợ của executor do {@code AsyncConfig} dựng ({@code MdcTaskDecorator},
 * {@code WaitForQueueSpacePolicy}, {@code LoggingAsyncUncaughtExceptionHandler}). Filter / handler / principal của bảo
 * mật nằm ở {@code security}; riêng {@code StaleSessionFilter} ở đây vì phụ thuộc {@code service} và
 * {@code exception}.
 */
package com.huuhv.socialanalytics.config;
