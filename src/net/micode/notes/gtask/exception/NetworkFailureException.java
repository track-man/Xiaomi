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
 * 网络连接失败异常
 * 
 * <p>当网络通信过程中发生错误时抛出的异常类型。它继承自Java标准异常类.Exception。
 * 由于继承自受检异常(Checked Exception)，调用者必须进行处理或声明抛出。</p>
 * 
 * <h2>用途</h2>
 * <p>专门用于标识网络层面的操作失败，包括但不限于以下场景：</p>
 * <ul>
 *   <li><b>HTTP请求超时</b> - 服务器在规定时间内未响应，如网络延迟过高或服务器负载过大</li>
 *   <li><b>网络不可用</b> - 设备处于飞行模式、WiFi未连接、移动数据未开启等</li>
 *   <li><b>DNS解析失败</b> - 无法将域名解析为IP地址，如DNS服务器不可用或域名错误</li>
 *   <li><b>连接被拒绝</b> - 服务器主动拒绝连接请求，如端口未开放或访问受限</li>
 *   <li><b>SSL/TLS握手失败</b> - HTTPS连接时证书验证失败或协议不匹配</li>
 *   <li><b>连接重置</b> - 连接被对方过早关闭，如服务器超时断开</li>
 *   <li><b>Socket超时</b> - 读取或写入数据时超时</li>
 * </ul>
 * 
 * <h2>使用场景</h2>
 * <p>在小米便签(GTask)同步服务中，当应用需要与服务器进行网络通信时，
 *    如果网络操作失败，会抛出此异常。调用者可以根据异常信息判断是否需要
 *    重试网络请求，或者向用户提示网络状态异常。</p>
 * <p>常见的使用方式：</p>
 * <pre>
 * try {
 *     // 执行网络请求，如同步任务数据
 *     syncTaskData();
 * } catch (NetworkFailureException e) {
 *     // 处理网络失败：提示用户检查网络
 *     showNetworkErrorDialog();
 *     // 或者将任务加入重试队列
 *     retryQueue.add(task);
 * }
 * </pre>
 * 
 * <h2>与ActionFailureException的区别</h2>
 * <table border="1" cellpadding="5">
 * <tr><th>对比项</th><th>NetworkFailureException</th><th>ActionFailureException</th></tr>
 * <tr><td>父类</td><td>Exception (受检异常)</td><td>RuntimeException (非受检异常)</td></tr>
 * <tr><td>类型定义</td><td>Checked Exception - 必须捕获或声明</td><td>Unchecked Exception - 无需强制捕获</td></tr>
 * <tr><td>语义</td><td>网络层面的物理/协议错误</td><td>业务逻辑层面的操作失败</td></tr>
 * <tr><td>产生原因</td><td>网络不通、超时、DNS错误等</td><td>服务器返回错误码、数据格式错误等</td></tr>
 * <tr><td>处理方式</td><td>通常需要用户干预或等待网络恢复</td><td>通常需要修改请求参数或重试操作</td></tr>
 * </table>
 * <p><b>简单来说：</b>NetworkFailureException表示"网络本身有问题"，
 *    而ActionFailureException表示"网络通了但操作失败"。</p>
 * 
 * <h2>设计考虑</h2>
 * <ul>
 *   <li>继承Exception而非RuntimeException是为了强制调用者关注网络失败情况，
 *      避免因未处理网络异常而导致应用崩溃</li>
 *   <li>提供带Throwable参数的构造函数，便于包装底层异常，
 *      保留完整的异常堆栈信息用于调试</li>
 * </ul>
 */

public class NetworkFailureException extends Exception {
    private static final long serialVersionUID = 2107610287180234136L;

    /**
     * 默认无参构造函数
     * 
     * <p>创建一个不带有详细错误信息的异常对象。</p>
     * <p>通常在不确定具体失败原因时使用，后续可通过getMessage()获取异常描述。</p>
     */
    public NetworkFailureException() {
        super();
    }

    /**
     * 带错误消息的构造函数
     * 
     * <p>创建一个带有详细描述信息的异常对象。</p>
     * 
     * @param paramString 具体的异常描述信息
     *        可以是简短的错误摘要，如"连接服务器超时"、
     *        或更详细的技术信息，如"Connection to https://example.com timed out after 30000ms"
     */
    public NetworkFailureException(String paramString) {
        super(paramString);
    }

    /**
     * 带错误消息和原始异常的构造函数
     * 
     * <p>创建一个带有详细描述信息并包含原始异常的异常对象。</p>
     * <p>这种构造方式特别适合用于异常链(Exception Chaining)，
     *    可以保留原始异常的堆栈信息，便于调试和排查问题。</p>
     * 
     * @param paramString 异常描述信息，说明当前异常的发生原因
     * @param paramThrowable 导致当前异常的原始异常（通常为底层网络库抛出的异常）
     *        如java.net.SocketTimeoutException、java.io.IOException等
     */
    public NetworkFailureException(String paramString, Throwable paramThrowable) {
        super(paramString, paramThrowable);
    }
}