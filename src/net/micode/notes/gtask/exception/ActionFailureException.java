/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.gtask.exception;

/**
 * 操作失败异常类
 * 
 * 用途说明：
 * 当Google同步服务执行同步操作失败时抛出此异常。此异常表示客户端能够成功连接到Google服务器，
 * 但在执行具体的同步操作（如创建任务、更新任务、删除任务等）时发生了错误。
 * 
 * 使用场景：
 * 1. Google API返回错误响应 - 例如任务ID不存在、操作冲突、服务器内部错误等
 * 2. 数据解析失败 - 解析Google返回的JSON/XML数据时发生格式错误或数据不匹配
 * 3. 权限不足 - 用户授权的OAuth令牌权限不够，无法执行特定操作（如只读权限尝试写入）
 * 4. 任务冲突 - 尝试更新或删除的任务已被其他客户端修改（乐观锁冲突）
 * 5. 配额超出 - 用户已超出Google Tasks API的配额限制
 * 
 * 与 NetworkFailureException 的区别：
 * - ActionFailureException: 连接成功，但操作执行失败（服务器端错误或客户端逻辑错误）
 * - NetworkFailureException: 网络连接本身失败（如无法连接到服务器、超时、DNS解析失败等）
 * 
 * 简而言之：
 * - NetworkFailureException 表示"无法与服务器通信"
 * - ActionFailureException 表示"能与服务器通信，但操作失败"
 * 
 * 异常处理建议：
 * - 捕获此异常时，通常需要向用户显示友好的错误提示
 * - 可以根据异常消息判断具体失败原因，并提供相应的恢复建议
 * - 对于可重试的操作（如任务冲突），可以在适当延迟后进行重试
 */
public class ActionFailureException extends RuntimeException {
    /** 序列化版本UID，用于反序列化时版本兼容检查 */
    private static final long serialVersionUID = 4425249765923293627L;

    /**
     * 默认构造函数
     * 
     * 使用场景：
     * - 当不需要提供详细错误信息时使用
     * - 适用于简单的异常标记场景
     * - 常见于需要向上层抛出异常但不想在此处详细描述错误的情况
     * 
     * 示例用途：
     * throw new ActionFailureException();
     */
    public ActionFailureException() {
        super();
    }

    /**
     * 带错误消息的构造函数
     * 
     * 使用场景：
     * - 当需要提供详细的错误信息供调试或日志记录时使用
     * - 适用于需要向上层传达具体失败原因的情况
     * - 有助于快速定位问题发生的具体环节
     * 
     * 参数说明：
     * @param paramString 错误消息字符串，描述具体的失败原因
     *                    可以是Google API返回的错误信息，或者是本地解析/业务逻辑错误描述
     * 
     * 示例用途：
     * throw new ActionFailureException("Failed to update task: task not found");
     * throw new ActionFailureException("JSON parsing error: unexpected field 'status'");
     */
    public ActionFailureException(String paramString) {
        super(paramString);
    }

    /**
     * 带错误消息和根异常的构造函数
     * 
     * 使用场景：
     * - 当需要保留原始异常信息进行完整的问题追踪时使用
     * - 适用于异常链场景，需要保留底层异常（如IOException、JSONException等）
     * - 有助于在多层调用中保留完整的异常堆栈信息
     * 
     * 参数说明：
     * @param paramString 错误消息字符串，描述当前层面的错误原因
     * @param paramThrowable 根异常，包含导致此异常的原始异常
     *                       通常是底层技术异常，如网络异常、解析异常等
     * 
     * 示例用途：
     * try {
     *     // 解析JSON数据
     *     parseTaskData(jsonString);
     * } catch (JSONException e) {
     *     throw new ActionFailureException("Failed to parse task data", e);
     * }
     */
    public ActionFailureException(String paramString, Throwable paramThrowable) {
        super(paramString, paramThrowable);
    }
}