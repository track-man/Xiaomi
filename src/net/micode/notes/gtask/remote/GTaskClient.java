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

package net.micode.notes.gtask.remote;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.gtask.data.Node;
import net.micode.notes.gtask.data.Task;
import net.micode.notes.gtask.data.TaskList;
import net.micode.notes.gtask.exception.ActionFailureException;
import net.micode.notes.gtask.exception.NetworkFailureException;
import net.micode.notes.tool.GTaskStringUtils;
import net.micode.notes.ui.NotesPreferenceActivity;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;


/**
 * GTaskClient - Google Tasks API HTTP 客户端
 * 
 * ========================================
 * 类的职责说明
 * ========================================
 * 
 * GTaskClient 是小米便签应用中用于与 Google Tasks API 进行通信的核心 HTTP 客户端类。
 * 它封装了所有与 Google Tasks 服务器交互所需的功能，包括：
 * 
 * 1. Google 账号认证
 *    - 通过 Android AccountManager 获取 Google 账号的认证令牌 (AuthToken)
 *    - 处理 Gmail 账号和企业 Google Apps 账号的认证
 * 
 * 2. HTTP 通信管理
 *    - 使用 Apache HttpClient 发送 GET/POST 请求
 *    - 支持 GZIP/Deflate 压缩响应解压缩
 *    - 管理 HTTP 连接超时和 Socket 超时
 *    - 处理 Cookie 存储和发送
 * 
 * 3. JSON 数据交互
 *    - 将本地数据对象序列化为 JSON 格式发送给服务器
 *    - 解析服务器返回的 JSON 响应数据
 *    - 构建符合 Google Tasks API 规范的操作请求
 * 
 * 4. Task/TaskList CRUD 操作
 *    - 创建任务 (createTask)
 *    - 创建任务列表 (createTaskList)
 *    - 更新任务/任务列表 (addUpdateNode -> commitUpdate)
 *    - 删除任务/任务列表 (deleteNode)
 *    - 移动任务位置 (moveTask)
 *    - 获取任务列表 (getTaskLists)
 *    - 获取特定列表中的任务 (getTaskList)
 * 
 * 5. 错误处理
 *    - 网络异常处理 (NetworkFailureException)
 *    - 操作失败异常处理 (ActionFailureException)
 *    - JSON 解析异常处理
 *    - HTTP 协议异常处理
 * 
 * ========================================
 * Google Tasks API 基础信息
 * ========================================
 * 
 * Google Tasks API 使用 Gmail Tasks 的内部 API（非官方公开 API），通过以下端点进行通信：
 * 
 * - GTASK_URL: https://mail.google.com/tasks/  (基础 URL)
 * - GTASK_GET_URL: https://mail.google.com/tasks/ig  (用于 GET 请求，获取任务数据)
 * - GTASK_POST_URL: https://mail.google.com/tasks/r/ig  (用于 POST 请求，执行操作)
 * 
 * 注意：对于企业 Google Apps 账号，URL 格式会包含域名：
 *       https://mail.google.com/tasks/a/{domain}/ig
 * 
 * ========================================
 * Google 认证流程说明
 * ========================================
 * 
 * 完整的认证流程如下：
 * 
 * 1. login() 方法作为入口点，调用 loginGoogleAccount() 获取认证令牌
 * 2. loginGoogleAccount() 执行以下步骤：
 *    a. 通过 AccountManager.getAccountsByType("com.google") 获取设备上所有 Google 账号
 *    b. 根据用户设置的同步账号名称找到对应账号
 *    c. 调用 AccountManager.getAuthToken() 获取认证令牌 (AuthToken)
 *    d. 使用 "goanna_mobile" 作为认证令牌类型（这是 Google Tasks 使用的特定类型）
 * 
 * 3. 认证令牌的获取可能失败，需要处理以下情况：
 *    - 账号已过期，需要 invalidateAuthToken 后重新获取
 *    - 用户未在设备上添加 Google 账号
 *    - 网络连接问题
 * 
 * 4. 获得认证令牌后，调用 loginGtask() 验证令牌并初始化会话：
 *    a. 使用认证令牌构建登录 URL: {GET_URL}?auth={token}
 *    b. 发送 GET 请求，服务器会设置认证 Cookie
 *    c. 从响应 HTML 中解析客户端版本号 (client_version)
 *    d. 该版本号在后续所有请求中都需要发送
 * 
 * ========================================
 * HTTP 请求方法使用场景
 * ========================================
 * 
 * GET 请求 - 用于获取数据：
 * - getTaskLists(): 获取用户的所有任务列表
 *   URL: {GET_URL}?auth={token}
 *   响应: HTML 页面，其中包含 _setup() JavaScript 调用，包含了所有任务列表数据
 * 
 * POST 请求 - 用于执行操作（创建、更新、删除、移动任务）：
 * - createTask(): 创建新任务
 * - createTaskList(): 创建新任务列表
 * - commitUpdate(): 提交待处理的更新操作
 * - moveTask(): 移动任务到不同位置或列表
 * - deleteNode(): 删除任务或任务列表
 * - getTaskList(): 获取特定列表中的所有任务
 * 
 * POST 请求格式：
 * - Content-Type: application/x-www-form-urlencoded;charset=utf-8
 * - 请求体包含名为 "r" 的参数，值为 JSON 格式的请求数据
 * 
 * ========================================
 * JSON 数据格式说明
 * ========================================
 * 
 * Google Tasks API 使用特定的 JSON 格式进行数据交换：
 * 
 * 【POST 请求格式】
 * {
 *   "action_list": [
 *     {
 *       // 操作类型和数据
 *     }
 *   ],
 *   "client_version": 1234567890  // 从登录响应中获取的客户端版本号
 * }
 * 
 * 【action_list 中的操作类型】
 * - "create": 创建新任务或任务列表
 * - "move": 移动任务位置
 * - "update": 更新现有任务或任务列表（标记删除也使用此类型）
 * 
 * 【GET 请求响应解析】
 * 响应是 HTML 页面，需要从中提取 JavaScript 代码：
 * - 查找: _setup(  开始的 JavaScript 调用
 * - 查找: )}</script>  结束标记
 * - 提取并解析中间的 JSON 对象
 * 
 * ========================================
 * 错误处理策略
 * ========================================
 * 
 * 1. NetworkFailureException - 网络相关错误
 *    - HTTP 连接超时 (10秒)
 *    - Socket 超时 (15秒)
 *    - IO 异常 (网络断开、服务器无响应等)
 * 
 * 2. ActionFailureException - 操作相关错误
 *    - 未登录就执行操作
 *    - JSON 解析失败
 *    - 服务器返回错误响应
 * 
 * 3. 认证失败处理
 *    - 如果认证令牌过期，会自动 invalidate 并重新获取
 *    - 最多重试一次
 * 
 * ========================================
 * 使用示例
 * ========================================
 * 
 * // 1. 获取单例实例
 * GTaskClient client = GTaskClient.getInstance();
 * 
 * // 2. 登录（通常在同步开始时调用）
 * if (!client.login(activity)) {
 *     // 登录失败处理
 * }
 * 
 * // 3. 获取任务列表
 * JSONArray taskLists = client.getTaskLists();
 * 
 * // 4. 获取特定列表中的任务
 * JSONArray tasks = client.getTaskList(listGid);
 * 
 * // 5. 创建新任务
 * Task task = new Task(...);
 * client.createTask(task);
 * 
 * // 6. 批量更新（先添加更新，再提交）
 * client.addUpdateNode(node1);
 * client.addUpdateNode(node2);
 * client.commitUpdate();
 * 
 * // 7. 移动任务
 * client.moveTask(task, oldParentList, newParentList);
 * 
 * // 8. 删除节点
 * client.deleteNode(node);
 * 
 * ========================================
 */
public class GTaskClient {
    
    // ========================================
    // 日志标签
    // ========================================
    private static final String TAG = GTaskClient.class.getSimpleName();

    // ========================================
    // Google Tasks API 端点 URL
    // ========================================
    
    /**
     * Google Tasks 基础 URL
     * 用于构建完整的 API 端点地址
     */
    private static final String GTASK_URL = "https://mail.google.com/tasks/";

    /**
     * Google Tasks GET 请求端点
     * 用于获取任务数据和任务列表
     * 
     * 用途：发送 GET 请求获取用户的所有任务列表和任务数据
     * 特点：返回的 HTML 页面中包含 _setup() JavaScript 调用，
     *       其中包含完整的任务数据结构
     */
    private static final String GTASK_GET_URL = "https://mail.google.com/tasks/ig";

    /**
     * Google Tasks POST 请求端点
     * 用于执行创建、更新、删除、移动等操作
     * 
     * 用途：发送 POST 请求执行各种任务操作
     * 请求格式：application/x-www-form-urlencoded
     * 数据格式：JSON 对象，包含 action_list 和 client_version
     */
    private static final String GTASK_POST_URL = "https://mail.google.com/tasks/r/ig";

    // ========================================
    // 单例模式相关
    // ========================================
    
    /**
     * GTaskClient 单例实例
     * 
     * 设计说明：使用单例模式确保整个应用只有一个 GTaskClient 实例，
     * 这样可以维护一致的登录状态、HTTP 客户端连接和 Cookie 存储。
     */
    private static GTaskClient mInstance = null;

    // ========================================
    // HTTP 客户端相关
    // ========================================
    
    /**
     * Apache HttpClient 实例
     * 
     * 用于执行所有 HTTP 请求（GET 和 POST）
     * 配置了以下参数：
     * - 连接超时：10 秒
     * - Socket 超时：15 秒
     * - Cookie 存储：使用 BasicCookieStore 管理认证 Cookie
     * - Expect-Continue：禁用（对 Google 服务器更友好）
     */
    private DefaultHttpClient mHttpClient;

    // ========================================
    // API 端点 URL（动态）
    // ========================================
    
    /**
     * 动态 GET 请求 URL
     * 
     * 初始值为 GTASK_GET_URL
     * 如果用户使用的是 Google Apps 企业账号（如 user@company.com），
     * 会动态修改为包含域名的 URL：
     * https://mail.google.com/tasks/a/{domain}/ig
     */
    private String mGetUrl;

    /**
     * 动态 POST 请求 URL
     * 
     * 初始值为 GTASK_POST_URL
     * 如果用户使用的是 Google Apps 企业账号，
     * 会动态修改为包含域名的 URL：
     * https://mail.google.com/tasks/a/{domain}/r/ig
     */
    private String mPostUrl;

    // ========================================
    // 认证和会话状态
    // ========================================
    
    /**
     * 客户端版本号
     * 
     * 从服务器响应中获取，用于标识客户端会话。
     * 这个版本号在所有后续的 POST 请求中都必须包含，
     * 服务器用它来验证请求的合法性。
     * 
     * 获取方式：登录成功后从 _setup() 响应的 JSON 中提取 "v" 字段
     */
    private long mClientVersion;

    /**
     * 登录状态标志
     * 
     * true: 已成功登录，可以执行 API 操作
     * false: 未登录或登录已过期，需要重新登录
     * 
     * 注意：登录状态会在 5 分钟后过期，或在切换账号后失效
     */
    private boolean mLoggedin;

    /**
     * 上次登录时间（毫秒）
     * 
     * 用于判断登录是否已过期
     * 登录有效期为 5 分钟（1000 * 60 * 5 毫秒）
     * 超过有效期后需要重新登录
     */
    private long mLastLoginTime;

    /**
     * 当前 Google 账号
     * 
     * 保存用户选择用于同步的 Google 账号信息
     * 用于：
     * - 验证登录后账号是否切换
     * - 获取认证令牌
     */
    private Account mAccount;

    // ========================================
    // 操作队列
    // ========================================
    
    /**
     * 操作 ID 计数器
     * 
     * 每个操作都需要一个唯一的 ID，用于服务器追踪和响应匹配
     * 从 1 开始，每次递增
     */
    private int mActionId;

    /**
     * 待提交的更新数组
     * 
     * 用于批量更新操作。在执行更新时，不是每条更新立即提交，
     * 而是先添加到 mUpdateArray 中，然后通过 commitUpdate() 一次性提交。
     * 
     * 设计目的：
     * - 减少网络请求次数，提高效率
     * - 将多个相关更新合并为一个请求
     * 
     * 限制：最多缓存 10 条更新，超过后会自动触发提交
     *       防止一次请求过大导致服务器拒绝
     */
    private JSONArray mUpdateArray;

    // ========================================
    // 构造函数和单例获取
    // ========================================
    
    /**
     * 私有构造函数
     * 
     * 使用单例模式，对象创建由 getInstance() 方法控制
     * 初始化所有成员变量为默认值
     */
    private GTaskClient() {
        // 初始化 HTTP 客户端为 null，延迟到登录时创建
        mHttpClient = null;
        
        // 默认使用标准 Google URL，企业账号会动态修改
        mGetUrl = GTASK_GET_URL;
        mPostUrl = GTASK_POST_URL;
        
        // 客户端版本未知，登录后从服务器获取
        mClientVersion = -1;
        
        // 初始状态为未登录
        mLoggedin = false;
        
        // 上次登录时间为 0，表示从未登录
        mLastLoginTime = 0;
        
        // 操作 ID 从 1 开始
        mActionId = 1;
        
        // 账号为 null，登录时设置
        mAccount = null;
        
        // 更新数组初始化为 null
        mUpdateArray = null;
    }

    /**
     * 获取 GTaskClient 单例实例
     * 
     * 这是获取 GTaskClient 实例的唯一入口点。
     * 线程安全的方法，使用 synchronized 保证多线程环境下的安全。
     * 
     * @return GTaskClient 单例实例
     */
    public static synchronized GTaskClient getInstance() {
        if (mInstance == null) {
            // 首次调用时创建实例
            mInstance = new GTaskClient();
        }
        return mInstance;
    }

    // ========================================
    // 登录流程
    // ========================================
    
    /**
     * 执行 Google Tasks 登录流程
     * 
     * 这是登录的入口方法，负责完整的登录过程：
     * 1. 检查登录是否过期（5分钟有效期）
     * 2. 检查账号是否切换
     * 3. 获取 Google 认证令牌
     * 4. 根据账号类型选择正确的 URL（普通 Gmail 或企业 Google Apps）
     * 5. 尝试验证令牌并初始化会话
     * 
     * 【登录有效期策略】
     * - 登录状态有效期为 5 分钟
     * - 如果用户切换了同步账号，也需要重新登录
     * 
     * 【自定义域名支持】
     * Google Apps 企业用户使用 user@company.com 格式的邮箱，
     * 需要访问 https://mail.google.com/tasks/a/{domain}/...
     * 
     * 普通 Gmail 用户直接使用标准 URL：
     * https://mail.google.com/tasks/ig
     * 
     * @param activity Activity 上下文，用于访问 AccountManager
     * @return true: 登录成功，可以执行后续操作
     *         false: 登录失败，需要检查网络或账号配置
     */
    public boolean login(Activity activity) {
        // 定义登录有效期为 5 分钟
        // 1000 毫秒 * 60 秒 * 5 分钟 = 300000 毫秒
        final long interval = 1000 * 60 * 5;
        
        // 检查登录是否已过期
        // 如果当前时间 > 上次登录时间 + 5分钟，则认为登录已过期
        if (mLastLoginTime + interval < System.currentTimeMillis()) {
            Log.d(TAG, "登录已过期，需要重新登录");
            mLoggedin = false;
        }

        // 检查用户是否切换了同步账号
        // 如果当前登录的账号与用户设置的账号不一致，需要重新登录
        if (mLoggedin
                && !TextUtils.equals(getSyncAccount().name, NotesPreferenceActivity
                        .getSyncAccountName(activity))) {
            Log.d(TAG, "账号已切换，需要重新登录");
            mLoggedin = false;
        }

        // 如果已经登录且状态有效，直接返回成功
        if (mLoggedin) {
            Log.d(TAG, "已经登录，无需重复登录");
            return true;
        }

        // 记录新的登录时间
        mLastLoginTime = System.currentTimeMillis();
        
        // 获取 Google 认证令牌
        // 参数 false 表示如果令牌过期，不要自动 invalidate
        String authToken = loginGoogleAccount(activity, false);
        if (authToken == null) {
            Log.e(TAG, "获取 Google 账号认证令牌失败");
            return false;
        }

        // 【自定义域名处理】
        // 检查是否是 Gmail 或 Googlemail 账号
        // Gmail 账号格式：username@gmail.com
        // Googlemail 账号格式：username@googlemail.com
        // 企业账号格式：username@company.com（需要特殊处理）
        if (!(mAccount.name.toLowerCase().endsWith("gmail.com") || mAccount.name.toLowerCase()
                .endsWith("googlemail.com"))) {
            // 非 Gmail 账号，构建包含域名的自定义 URL
            // URL 格式: https://mail.google.com/tasks/a/{domain}/ig
            
            StringBuilder url = new StringBuilder(GTASK_URL).append("a/");
            // 从邮箱地址中提取域名部分（@后面的内容）
            int index = mAccount.name.indexOf('@') + 1;
            String suffix = mAccount.name.substring(index);
            // 拼接完整的 GET 和 POST URL
            url.append(suffix + "/");
            mGetUrl = url.toString() + "ig";
            mPostUrl = url.toString() + "r/ig";

            Log.d(TAG, "企业账号，尝试登录到: " + mGetUrl);
            
            // 尝试使用企业账号 URL 登录
            if (tryToLoginGtask(activity, authToken)) {
                mLoggedin = true;
            }
        }

        // 【标准 Gmail 账号处理】
        // 如果是企业账号登录失败，或者直接是 Gmail 账号，
        // 尝试使用标准 Google URL 登录
        if (!mLoggedin) {
            // 重置为标准 URL
            mGetUrl = GTASK_GET_URL;
            mPostUrl = GTASK_POST_URL;
            
            Log.d(TAG, "尝试登录到标准 Gmail URL");
            
            // 执行实际的登录尝试
            if (!tryToLoginGtask(activity, authToken)) {
                Log.e(TAG, "标准 Gmail URL 登录失败");
                return false;
            }
        }

        // 登录成功
        mLoggedin = true;
        return true;
    }

    // ========================================
    // Google 账号认证
    // ========================================
    
    /**
     * 获取 Google 账号认证令牌 (AuthToken)
     * 
     * 【认证流程说明】
     * 
     * 1. 获取设备上的所有 Google 账号
     *    使用 AccountManager.getAccountsByType("com.google") 获取
     *    这会返回设备上所有添加的 Google 账号
     * 
     * 2. 找到用户配置的同步账号
     *    从 NotesPreferenceActivity 获取用户设置的账号名称
     *    在设备账号列表中找到匹配的账号
     * 
     * 3. 请求认证令牌
     *    调用 AccountManager.getAuthToken() 获取令牌
     *    使用 "goanna_mobile" 作为认证类型
     *    "goanna_mobile" 是 Google Tasks 使用的特定认证类型
     * 
     * 4. 处理令牌过期（可选）
     *    如果 invalidateToken 为 true，会先 invalidate 当前令牌
     *    然后递归调用自己重新获取新令牌
     *    这用于处理令牌过期的情况
     * 
     * 【令牌类型 "goanna_mobile" 说明】
     * 这是 Google Tasks 移动应用使用的内部令牌类型，
     * 用于访问 Gmail Tasks 的内部 API。
     * 这个令牌会被包含在后续 HTTP 请求的 URL 参数中。
     * 
     * @param activity Activity 上下文
     * @param invalidateToken 是否在获取前 invalidate 旧令牌
     *                         true: 令牌已过期，需要先清除再重新获取
     *                         false: 正常获取令牌
     * @return 认证令牌字符串，失败返回 null
     */
    private String loginGoogleAccount(Activity activity, boolean invalidateToken) {
        String authToken;
        
        // 获取 Android 账号管理器
        // AccountManager 是 Android 系统服务，用于管理设备上的用户账号
        AccountManager accountManager = AccountManager.get(activity);
        
        // 获取设备上所有 Google 类型的账号
        // "com.google" 是 Google 账号的类型标识符
        Account[] accounts = accountManager.getAccountsByType("com.google");

        // 检查是否有可用的 Google 账号
        if (accounts.length == 0) {
            Log.e(TAG, "设备上没有可用的 Google 账号");
            return null;
        }

        // 从偏好设置中获取用户配置的同步账号名称
        String accountName = NotesPreferenceActivity.getSyncAccountName(activity);
        
        // 在设备账号列表中查找匹配的账号
        Account account = null;
        for (Account a : accounts) {
            if (a.name.equals(accountName)) {
                account = a;
                break;
            }
        }
        
        // 检查是否找到了匹配的账号
        if (account != null) {
            mAccount = account;  // 保存账号引用供后续使用
        } else {
            // 没有找到与设置匹配的账号
            Log.e(TAG, "无法找到设置中配置的 Google 账号: " + accountName);
            return null;
        }

        // 【获取认证令牌】
        // getAuthToken 是异步方法，但这里使用 .getResult() 阻塞等待结果
        // 参数说明：
        // - account: 要获取令牌的账号
        // - "goanna_mobile": 认证令牌类型，Google Tasks 使用这个特定类型
        // - null: ActivityOptions（可选）
        // - activity: 用于回调的 Activity
        // - null: 登录结果回调（null 表示使用 getResult 同步获取）
        // - null: 取消回调
        AccountManagerFuture<Bundle> accountManagerFuture = accountManager.getAuthToken(account,
                "goanna_mobile", null, activity, null, null);
        try {
            // 阻塞等待认证结果
            Bundle authTokenBundle = accountManagerFuture.getResult();
            
            // 从结果中提取认证令牌
            authToken = authTokenBundle.getString(AccountManager.KEY_AUTHTOKEN);
            
            // 如果需要 invalidate 旧令牌（令牌过期处理）
            if (invalidateToken) {
                // 清除过期的令牌
                accountManager.invalidateAuthToken("com.google", authToken);
                
                // 递归调用自己，重新获取新令牌
                // 注意：这里传入 false，避免无限递归
                authToken = loginGoogleAccount(activity, false);
            }
        } catch (Exception e) {
            // 处理所有可能的异常（IOException, AuthenticatorException 等）
            Log.e(TAG, "获取认证令牌失败: " + e.getMessage());
            authToken = null;
        }

        return authToken;
    }

    /**
     * 尝试登录 Google Tasks（带令牌刷新重试）
     * 
     * 这是登录的核心逻辑，负责：
     * 1. 首先尝试使用当前令牌登录
     * 2. 如果失败，尝试刷新令牌（invalidate 后重新获取）
     * 3. 使用新令牌再次尝试登录
     * 
     * 【重试机制】
     * Google 认证令牌可能会过期，当服务器返回认证失败时，
     * 我们会尝试刷新令牌并重试一次。
     * 
     * @param activity Activity 上下文
     * @param authToken 当前认证令牌
     * @return true: 登录成功
     *         false: 登录失败
     */
    private boolean tryToLoginGtask(Activity activity, String authToken) {
        // 第一次尝试：使用提供的令牌登录
        if (!loginGtask(authToken)) {
            // 登录失败，可能是令牌过期
            // 【令牌刷新流程】
            Log.w(TAG, "初始令牌登录失败，尝试刷新令牌");
            
            // 调用 loginGoogleAccount，invalidateToken=true 表示先清除旧令牌
            authToken = loginGoogleAccount(activity, true);
            if (authToken == null) {
                Log.e(TAG, "刷新令牌失败");
                return false;
            }

            // 使用新令牌再次尝试登录
            if (!loginGtask(authToken)) {
                Log.e(TAG, "刷新令牌后登录仍然失败");
                return false;
            }
        }
        return true;
    }

    /**
     * 执行实际的 Google Tasks 登录
     * 
     * 这是真正与 Google Tasks 服务器建立会话的方法：
     * 1. 初始化 HTTP 客户端（设置超时、Cookie 存储等）
     * 2. 构建包含认证令牌的登录 URL
     * 3. 发送 GET 请求建立会话
     * 4. 从响应中提取认证 Cookie
     * 5. 从响应 HTML 中解析客户端版本号
     * 
     * 【HTTP 客户端配置】
     * - 连接超时: 10 秒
     * - Socket 超时: 15 秒
     * - Cookie 存储: BasicCookieStore（用于保存认证 Cookie）
     * - Expect-Continue: 禁用
     * 
     * 【登录 URL 格式】
     * {GET_URL}?auth={认证令牌}
     * 例如: https://mail.google.com/tasks/ig?auth=ya29.xxx...
     * 
     * 【响应解析】
     * 服务器返回 HTML 页面，包含 JavaScript 代码 _setup({...});
     * 我们需要：
     * 1. 找到 "_setup(" 开始位置
     * 2. 找到 ")}</script>" 结束位置
     * 3. 提取中间的 JSON 对象
     * 4. 解析 JSON 获取客户端版本号 "v"
     * 
     * 【Cookie 验证】
     * 登录成功后，服务器会设置认证 Cookie（名称包含 "GTL"）
     * 这是后续请求的身份标识
     * 
     * @param authToken Google 认证令牌
     * @return true: 登录成功，已获取有效的客户端版本号
     *         false: 登录失败
     */
    private boolean loginGtask(String authToken) {
        // 【HTTP 客户端配置】
        // 设置连接超时：10 秒（建立 TCP 连接的最大等待时间）
        int timeoutConnection = 10000;
        
        // 设置 Socket 超时：15 秒（数据传输的最大等待时间）
        int timeoutSocket = 15000;
        
        // 创建 HTTP 参数对象
        HttpParams httpParameters = new BasicHttpParams();
        
        // 设置连接超时
        HttpConnectionParams.setConnectionTimeout(httpParameters, timeoutConnection);
        
        // 设置 Socket 超时
        HttpConnectionParams.setSoTimeout(httpParameters, timeoutSocket);
        
        // 创建配置好的 HTTP 客户端
        mHttpClient = new DefaultHttpClient(httpParameters);
        
        // 【Cookie 存储配置】
        // 创建本地 Cookie 存储
        BasicCookieStore localBasicCookieStore = new BasicCookieStore();
        
        // 设置 HTTP 客户端使用本地 Cookie 存储
        // 这样服务器设置的认证 Cookie 会被保存
        mHttpClient.setCookieStore(localBasicCookieStore);
        
        // 禁用 HTTP Expect-Continue 握手
        // Expect-Continue 是 HTTP/1.1 的一种机制，用于在发送大数据前先询问服务器是否接受
        // 对于 Google 服务器，禁用此机制更可靠
        HttpProtocolParams.setUseExpectContinue(mHttpClient.getParams(), false);

        // 【执行登录请求】
        try {
            // 构建登录 URL: {GET_URL}?auth={token}
            String loginUrl = mGetUrl + "?auth=" + authToken;
            
            // 创建 GET 请求
            HttpGet httpGet = new HttpGet(loginUrl);
            
            HttpResponse response = null;
            
            // 执行请求并获取响应
            response = mHttpClient.execute(httpGet);

            // 【验证 Cookie】
            // 登录成功时，服务器会设置认证 Cookie
            // 这里检查 Cookie 存储中是否包含有效的认证 Cookie
            // Google 认证 Cookie 的名称通常包含 "GTL" 字符串
            List<Cookie> cookies = mHttpClient.getCookieStore().getCookies();
            boolean hasAuthCookie = false;
            for (Cookie cookie : cookies) {
                if (cookie.getName().contains("GTL")) {
                    hasAuthCookie = true;
                    Log.d(TAG, "找到认证 Cookie: " + cookie.getName());
                }
            }
            if (!hasAuthCookie) {
                Log.w(TAG, "警告：未找到认证 Cookie，登录可能不成功");
            }

            // 【解析响应，提取客户端版本号】
            // Google Tasks 返回的 HTML 页面中包含 JavaScript 代码
            // 格式: <script>_setup({...});</script>
            // 这里的 JSON 对象包含客户端版本号和其他数据
            
            // 获取响应内容
            String resString = getResponseContent(response.getEntity());
            
            // 定义要提取的 JavaScript 代码边界
            String jsBegin = "_setup(";    // 开始标记
            String jsEnd = ")}</script>";  // 结束标记
            
            // 查找开始和结束位置
            int begin = resString.indexOf(jsBegin);
            int end = resString.lastIndexOf(jsEnd);
            
            String jsString = null;
            
            // 提取 JSON 字符串
            if (begin != -1 && end != -1 && begin < end) {
                // + jsBegin.length() 是为了跳过 "_setup(" 这6个字符
                // 这样提取的才是 JSON 对象本身
                jsString = resString.substring(begin + jsBegin.length(), end);
            }
            
            // 解析 JSON 获取客户端版本号
            JSONObject js = new JSONObject(jsString);
            
            // "v" 字段是客户端版本号，必须在后续请求中使用
            mClientVersion = js.getLong("v");
            Log.d(TAG, "获取到客户端版本号: " + mClientVersion);
            
        } catch (JSONException e) {
            // JSON 解析失败，可能响应格式不符合预期
            Log.e(TAG, "JSON 解析失败: " + e.toString());
            e.printStackTrace();
            return false;
        } catch (Exception e) {
            // 捕获所有其他异常（网络异常、IO 异常等）
            Log.e(TAG, "HTTP GET 请求失败: " + e.toString());
            return false;
        }

        return true;
    }

    // ========================================
    // 操作 ID 管理
    // ========================================
    
    /**
     * 获取下一个操作 ID
     * 
     * 每个 API 操作都需要一个唯一的 ID，用于：
     * 1. 服务器追踪请求
     * 2. 将响应与请求匹配
     * 3. 支持批量操作中的每个子操作
     * 
     * ID 从 1 开始，每次调用后递增。
     * 注意：这是在客户端递增，服务器可能会用不同的方式处理。
     * 
     * @return 下一个可用的操作 ID
     */
    private int getActionId() {
        return mActionId++;
    }

    // ========================================
    // HTTP 请求工具方法
    // ========================================
    
    /**
     * 创建 HTTP POST 请求对象
     * 
     * 所有 POST 请求都使用相同的配置：
     * - 使用 mPostUrl 作为请求 URL
     * - Content-Type 设置为 application/x-www-form-urlencoded;charset=utf-8
     * - AT 头设置为 "1"（可能是 API 版本或认证标记）
     * 
     * 【POST 请求格式】
     * Google Tasks API 的 POST 请求使用表单编码格式：
     * - 参数名: "r"
     * - 参数值: JSON 格式的请求数据
     * 
     * @return 配置好的 HttpPost 对象
     */
    private HttpPost createHttpPost() {
        // 创建 POST 请求
        HttpPost httpPost = new HttpPost(mPostUrl);
        
        // 设置 Content-Type 头
        // application/x-www-form-urlencoded: 表单数据编码格式
        // charset=utf-8: 使用 UTF-8 编码，支持中文
        httpPost.setHeader("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");
        
        // 设置 AT 头（具体含义未明，可能是 API 标记）
        httpPost.setHeader("AT", "1");
        
        return httpPost;
    }

    /**
     * 从 HTTP 响应实体中获取内容字符串
     * 
     * 处理多种压缩格式的响应：
     * - gzip: GZIP 压缩（最常用）
     * - deflate: Deflate 压缩
     * - 无压缩: 普通文本
     * 
     * 读取响应后，将其转换为字符串返回。
     * 使用 BufferedReader 高效读取文本内容。
     * 
     * 【压缩格式说明】
     * - GZIP: 基于 DEFLATE 的压缩格式，带有头部和尾部校验
     * - Deflate: 原始 DEFLATE 压缩数据
     * Google 服务器通常使用 GZIP 压缩响应，以减少传输数据量
     * 
     * @param entity HTTP 响应实体
     * @return 响应内容的字符串形式
     * @throws IOException 如果读取失败
     */
    private String getResponseContent(HttpEntity entity) throws IOException {
        // 获取响应内容的编码格式
        String contentEncoding = null;
        if (entity.getContentEncoding() != null) {
            contentEncoding = entity.getContentEncoding().getValue();
            Log.d(TAG, "响应编码: " + contentEncoding);
        }

        // 获取输入流
        InputStream input = entity.getContent();
        
        // 【根据编码格式包装输入流】
        
        // GZIP 压缩处理
        // GZIPInputStream 会自动处理 GZIP 格式的解压缩
        if (contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip")) {
            Log.d(TAG, "使用 GZIP 解压缩");
            input = new GZIPInputStream(entity.getContent());
        } 
        // Deflate 压缩处理
        // Inflater(true) 使用 ZLIB 头格式
        else if (contentEncoding != null && contentEncoding.equalsIgnoreCase("deflate")) {
            Log.d(TAG, "使用 Deflate 解压缩");
            Inflater inflater = new Inflater(true);
            input = new InflaterInputStream(entity.getContent(), inflater);
        }
        // 无压缩：直接使用原始输入流

        // 【读取响应内容】
        try {
            // 创建字符输入流，使用系统默认编码
            InputStreamReader isr = new InputStreamReader(input);
            
            // 创建缓冲字符流，提高读取效率
            BufferedReader br = new BufferedReader(isr);
            
            // 使用 StringBuilder 拼接所有行
            StringBuilder sb = new StringBuilder();

            // 循环读取所有行
            while (true) {
                String buff = br.readLine();  // 读取一行
                if (buff == null) {
                    // 到达文件末尾
                    return sb.toString();
                }
                sb = sb.append(buff);  // 追加行（不添加换行符，因为 readLine 已经去掉了）
            }
        } finally {
            // 确保输入流被关闭
            input.close();
        }
    }

    // ========================================
    // POST 请求执行
    // ========================================
    
    /**
     * 执行 POST 请求并解析 JSON 响应
     * 
     * 这是所有修改操作（创建、更新、删除、移动）的核心方法。
     * 
     * 【请求格式】
     * {
     *   "r": "{JSON 请求对象}"
     * }
     * 
     * 注意：请求数据被包装在一个表单参数 "r" 中，值为 JSON 字符串。
     * 
     * 【响应格式】
     * 服务器返回 JSON 对象，通常包含：
     * - "results": 操作结果数组
     * - "tasks": 任务数据数组（对于获取任务的请求）
     * 
     * 【错误处理】
     * - ClientProtocolException: HTTP 协议错误
     * - IOException: 网络 IO 错误
     * - JSONException: JSON 解析错误
     * - Exception: 其他未知错误
     * 
     * @param js 要发送的 JSON 请求对象
     * @return 服务器响应的 JSON 对象
     * @throws NetworkFailureException 网络相关错误
     */
    private JSONObject postRequest(JSONObject js) throws NetworkFailureException {
        // 检查登录状态
        // 所有 POST 操作都需要先登录
        if (!mLoggedin) {
            Log.e(TAG, "未登录，无法执行请求");
            throw new ActionFailureException("未登录");
        }

        // 创建配置好的 POST 请求
        HttpPost httpPost = createHttpPost();
        try {
            // 【构建请求体】
            // 使用表单编码格式发送数据
            LinkedList<BasicNameValuePair> list = new LinkedList<BasicNameValuePair>();
            
            // "r" 参数的值为 JSON 字符串
            // toString() 会生成标准的 JSON 字符串
            list.add(new BasicNameValuePair("r", js.toString()));
            
            // 创建表单编码实体，使用 UTF-8 编码
            UrlEncodedFormEntity entity = new UrlEncodedFormEntity(list, "UTF-8");
            
            // 设置 POST 请求体
            httpPost.setEntity(entity);

            // 【执行 POST 请求】
            HttpResponse response = mHttpClient.execute(httpPost);
            
            // 【解析响应】
            String jsString = getResponseContent(response.getEntity());
            
            // 将响应字符串解析为 JSON 对象
            return new JSONObject(jsString);

        } catch (ClientProtocolException e) {
            // HTTP 协议错误（如无效的 HTTP 版本）
            Log.e(TAG, "HTTP 协议错误: " + e.toString());
            e.printStackTrace();
            throw new NetworkFailureException("POST 请求失败: HTTP 协议错误");
        } catch (IOException e) {
            // 网络 IO 错误（连接断开、超时等）
            Log.e(TAG, "IO 错误: " + e.toString());
            e.printStackTrace();
            throw new NetworkFailureException("POST 请求失败: 网络 IO 错误");
        } catch (JSONException e) {
            // JSON 解析错误（响应不是有效的 JSON）
            Log.e(TAG, "JSON 解析错误: " + e.toString());
            e.printStackTrace();
            throw new ActionFailureException("无法将响应解析为 JSON 对象");
        } catch (Exception e) {
            // 其他未知错误
            Log.e(TAG, "未知错误: " + e.toString());
            e.printStackTrace();
            throw new ActionFailureException("POST 请求执行时发生错误");
        }
    }

    // ========================================
    // Task 操作
    // ========================================
    
    /**
     * 创建新任务
     * 
     * 在 Google Tasks 服务器上创建一个新任务。
     * 
     * 【操作流程】
     * 1. 先提交所有待处理的更新（commitUpdate）
     * 2. 构建创建任务的 JSON 请求
     * 3. 发送 POST 请求
     * 4. 从响应中提取新创建任务的 ID
     * 5. 将 ID 设置到任务对象中
     * 
     * 【JSON 请求格式】
     * {
     *   "action_list": [
     *     {
     *       "action_type": "create",
     *       "action_id": 1,
     *       ...任务属性...
     *     }
     *   ],
     *   "client_version": 1234567890
     * }
     * 
     * 【响应格式】
     * {
     *   "results": [
     *     {
     *       "new_id": "task_gid_xxx"
     *     }
     *   ]
     * }
     * 
     * @param task 要创建的任务对象
     * @throws NetworkFailureException 网络或服务器错误
     */
    public void createTask(Task task) throws NetworkFailureException {
        // 先提交所有待处理的更新
        // 确保之前的更新不会被遗漏
        commitUpdate();
        
        try {
            // 构建 POST 请求 JSON
            JSONObject jsPost = new JSONObject();
            JSONArray actionList = new JSONArray();

            // 【添加 action_list】
            // actionList 包含所有要执行的操作
            // 这里只有一个操作：创建任务
            // getCreateAction() 返回该任务的创建操作 JSON 对象
            actionList.put(task.getCreateAction(getActionId()));
            
            // 将 action_list 添加到请求中
            jsPost.put(GTaskStringUtils.GTASK_JSON_ACTION_LIST, actionList);

            // 【添加 client_version】
            // 客户端版本号必须在所有请求中发送
            jsPost.put(GTaskStringUtils.GTASK_JSON_CLIENT_VERSION, mClientVersion);

            // 【发送 POST 请求】
            JSONObject jsResponse = postRequest(jsPost);
            
            // 【解析响应，提取新任务的 ID】
            // 响应中 results 数组的第一个元素包含 new_id
            JSONObject jsResult = (JSONObject) jsResponse.getJSONArray(
                    GTaskStringUtils.GTASK_JSON_RESULTS).get(0);
            
            // 从响应中获取服务器分配的新任务 ID
            task.setGid(jsResult.getString(GTaskStringUtils.GTASK_JSON_NEW_ID));
            Log.d(TAG, "任务创建成功，服务器分配的 ID: " + task.getGid());

        } catch (JSONException e) {
            // JSON 解析错误
            Log.e(TAG, "JSON 处理错误: " + e.toString());
            e.printStackTrace();
            throw new ActionFailureException("创建任务: JSON 处理失败");
        }
    }

    // ========================================
    // TaskList 操作
    // ========================================
    
    /**
     * 创建新任务列表
     * 
     * 在 Google Tasks 服务器上创建一个新的任务列表。
     * 
     * 【操作流程】
     * 与 createTask 类似：
     * 1. 先提交待处理的更新
     * 2. 构建创建任务列表的 JSON 请求
     * 3. 发送 POST 请求
     * 4. 从响应中提取新创建的任务列表 ID
     * 5. 将 ID 设置到任务列表对象中
     * 
     * 【任务列表 vs 任务】
     * - TaskList（任务列表）：类似于文件夹，用于组织多个相关任务
     * - Task（任务）：具体的待办事项
     * 
     * @param tasklist 要创建的任务列表对象
     * @throws NetworkFailureException 网络或服务器错误
     */
    public void createTaskList(TaskList tasklist) throws NetworkFailureException {
        // 先提交所有待处理的更新
        commitUpdate();
        
        try {
            // 构建 POST 请求 JSON
            JSONObject jsPost = new JSONObject();
            JSONArray actionList = new JSONArray();

            // 【添加 action_list】
            // getCreateAction() 返回该任务列表的创建操作 JSON 对象
            actionList.put(tasklist.getCreateAction(getActionId()));
            jsPost.put(GTaskStringUtils.GTASK_JSON_ACTION_LIST, actionList);

            // 【添加 client_version】
            jsPost.put(GTaskStringUtils.GTASK_JSON_CLIENT_VERSION, mClientVersion);

            // 【发送 POST 请求】
            JSONObject jsResponse = postRequest(jsPost);
            
            // 【解析响应，提取新任务列表的 ID】
            JSONObject jsResult = (JSONObject) jsResponse.getJSONArray(
                    GTaskStringUtils.GTASK_JSON_RESULTS).get(0);
            
            // 从响应中获取服务器分配的新任务列表 ID
            tasklist.setGid(jsResult.getString(GTaskStringUtils.GTASK_JSON_NEW_ID));
            Log.d(TAG, "任务列表创建成功，服务器分配的 ID: " + tasklist.getGid());

        } catch (JSONException e) {
            Log.e(TAG, "JSON 处理错误: " + e.toString());
            e.printStackTrace();
            throw new ActionFailureException("创建任务列表: JSON 处理失败");
        }
    }

    // ========================================
    // 更新操作
    // ========================================
    
    /**
     * 提交待处理的更新操作
     * 
     * 这是批量更新机制的核心方法。
     * 
     * 【设计背景】
     * 如果每次修改都立即发送到服务器，会产生大量网络请求，
     * 影响性能和电量。因此采用批量更新机制：
     * 1. 修改操作先添加到 mUpdateArray 中
     * 2. 达到一定数量或需要时，调用 commitUpdate() 一次性提交
     * 
     * 【提交时机】
     * - addUpdateNode() 中，当更新项超过 10 个时自动提交
     * - createTask()、createTaskList() 开始前
     * - moveTask() 开始前
     * - deleteNode() 开始前
     * - 同步结束时
     * 
     * 【JSON 请求格式】
     * {
     *   "action_list": [多个 update 操作],
     *   "client_version": 1234567890
     * }
     * 
     * @throws NetworkFailureException 网络或服务器错误
     */
    public void commitUpdate() throws NetworkFailureException {
        // 检查是否有待提交的更新
        if (mUpdateArray != null) {
            try {
                // 构建 POST 请求
                JSONObject jsPost = new JSONObject();

                // 将待处理的更新数组直接作为 action_list
                jsPost.put(GTaskStringUtils.GTASK_JSON_ACTION_LIST, mUpdateArray);

                // 添加客户端版本号
                jsPost.put(GTaskStringUtils.GTASK_JSON_CLIENT_VERSION, mClientVersion);

                // 发送请求
                postRequest(jsPost);
                
                Log.d(TAG, "批量更新提交成功，共 " + mUpdateArray.length() + " 个操作");
                
                // 【重要】提交后清空更新数组
                // 避免重复提交
                mUpdateArray = null;
                
            } catch (JSONException e) {
                Log.e(TAG, "JSON 处理错误: " + e.toString());
                e.printStackTrace();
                throw new ActionFailureException("提交更新: JSON 处理失败");
            }
        }
    }

    /**
     * 添加待更新的节点到批量队列
     * 
     * 将任务或任务列表的更新操作添加到待提交队列中。
     * 
     * 【批量机制说明】
     * - 不是每次修改都立即发送
     * - 积累多个修改后一次性提交
     * - 减少网络请求次数
     * 
     * 【数量限制】
     * 为了防止单次请求过大，限制最多缓存 10 条更新。
     * 当达到 10 条时，会自动触发 commitUpdate()。
     * 
     * 【使用示例】
     * // 修改任务标题
     * task.setTitle("新标题");
     * client.addUpdateNode(task);
     * 
     * // 修改任务内容
     * task.setContent("新内容");
     * client.addUpdateNode(task);
     * 
     * // 最后提交所有更新
     * client.commitUpdate();
     * 
     * @param node 要更新的节点（Task 或 TaskList）
     * @throws NetworkFailureException 网络或服务器错误
     */
    public void addUpdateNode(Node node) throws NetworkFailureException {
        if (node != null) {
            // 【数量限制检查】
            // 一次请求包含太多更新项可能导致服务器拒绝
            // 这里限制最多 10 条，超过后自动提交
            if (mUpdateArray != null && mUpdateArray.length() > 10) {
                Log.d(TAG, "更新项超过 10 个，自动触发提交");
                commitUpdate();
            }

            // 初始化更新数组（如果为空）
            if (mUpdateArray == null)
                mUpdateArray = new JSONArray();
            
            // 【生成并添加更新操作】
            // getUpdateAction() 返回该节点的更新操作 JSON 对象
            // 每个操作都有唯一的 action_id
            mUpdateArray.put(node.getUpdateAction(getActionId()));
        }
    }

    // ========================================
    // 移动操作
    // ========================================
    
    /**
     * 移动任务到不同位置或任务列表
     * 
     * 可以实现以下功能：
     * 1. 在同一任务列表内调整任务顺序
     * 2. 将任务移动到不同的任务列表
     * 
     * 【移动类型】
     * 
     * 1. 同列表内移动（preParent == curParent）：
     *    - 任务在同一个列表中调整位置
     *    - 需要指定 prior_sibling_id（前置兄弟节点）
     *    - 如果是第一个任务，不指定 prior_sibling_id
     * 
     * 2. 跨列表移动（preParent != curParent）：
     *    - 任务从源列表移动到目标列表
     *    - 需要指定 source_list（源列表）
     *    - 需要指定 dest_list（目标列表）
     *    - 需要指定 dest_parent（目标父节点，通常是目标列表本身）
     * 
     * 【JSON 请求格式】
     * {
     *   "action_list": [
     *     {
     *       "action_type": "move",
     *       "action_id": 1,
     *       "id": "task_gid",
     *       "source_list": "source_list_gid",       // 源列表
     *       "dest_parent": "dest_parent_gid",       // 目标父节点
     *       "dest_list": "dest_list_gid",           // 目标列表（跨列表时）
     *       "prior_sibling_id": "sibling_gid"      // 前置兄弟（可选）
     *     }
     *   ],
     *   "client_version": 1234567890
     * }
     * 
     * @param task 要移动的任务
     * @param preParent 任务原来的父任务列表
     * @param curParent 任务新的父任务列表
     * @throws NetworkFailureException 网络或服务器错误
     */
    public void moveTask(Task task, TaskList preParent, TaskList curParent)
            throws NetworkFailureException {
        // 先提交待处理的更新
        commitUpdate();
        
        try {
            // 构建请求 JSON
            JSONObject jsPost = new JSONObject();
            JSONArray actionList = new JSONArray();
            JSONObject action = new JSONObject();

            // 【构建移动操作】
            
            // 操作类型：move
            action.put(GTaskStringUtils.GTASK_JSON_ACTION_TYPE,
                    GTaskStringUtils.GTASK_JSON_ACTION_TYPE_MOVE);
            
            // 操作 ID
            action.put(GTaskStringUtils.GTASK_JSON_ACTION_ID, getActionId());
            
            // 要移动的任务 ID
            action.put(GTaskStringUtils.GTASK_JSON_ID, task.getGid());
            
            // 【同列表内移动的处理】
            // 如果是在同一个列表中移动，且不是第一个任务
            // 需要指定 prior_sibling_id（前置兄弟节点）
            // 这样服务器知道把任务移动到哪个任务之后
            if (preParent == curParent && task.getPriorSibling() != null) {
                action.put(GTaskStringUtils.GTASK_JSON_PRIOR_SIBLING_ID, task.getPriorSibling());
            }
            
            // 源列表 ID（任务原来所在的列表）
            action.put(GTaskStringUtils.GTASK_JSON_SOURCE_LIST, preParent.getGid());
            
            // 目标父节点 ID（通常是目标列表本身）
            action.put(GTaskStringUtils.GTASK_JSON_DEST_PARENT, curParent.getGid());
            
            // 【跨列表移动的处理】
            // 只有移动到不同列表时，才需要指定 dest_list
            if (preParent != curParent) {
                action.put(GTaskStringUtils.GTASK_JSON_DEST_LIST, curParent.getGid());
            }
            
            // 添加操作到列表
            actionList.put(action);
            jsPost.put(GTaskStringUtils.GTASK_JSON_ACTION_LIST, actionList);

            // 添加客户端版本号
            jsPost.put(GTaskStringUtils.GTASK_JSON_CLIENT_VERSION, mClientVersion);

            // 发送请求
            postRequest(jsPost);
            
            Log.d(TAG, "任务移动成功: " + task.getGid());

        } catch (JSONException e) {
            Log.e(TAG, "JSON 处理错误: " + e.toString());
            e.printStackTrace();
            throw new ActionFailureException("移动任务: JSON 处理失败");
        }
    }

    // ========================================
    // 删除操作
    // ========================================
    
    /**
     * 删除节点（任务或任务列表）
     * 
     * Google Tasks API 使用"软删除"机制：
     * 不是真正从服务器删除数据，而是将节点标记为已删除状态。
     * 
     * 【软删除说明】
     * - 将 node 的 deleted 标志设置为 true
     * - 通过 update 操作提交这个变更
     * - 服务器会将该节点从正常列表中移除，但仍保留在"已删除"视图中
     * - 用户可以在 Google Tasks 界面中恢复已删除的项目
     * 
     * 【设计考量】
     * 软删除比硬删除更安全，因为：
     * 1. 用户可能误删除，可以通过回收站恢复
     * 2. 实现了更好的数据一致性
     * 3. 便于实现"撤销删除"功能
     * 
     * @param node 要删除的节点（Task 或 TaskList）
     * @throws NetworkFailureException 网络或服务器错误
     */
    public void deleteNode(Node node) throws NetworkFailureException {
        // 先提交待处理的更新
        commitUpdate();
        
        try {
            // 构建请求 JSON
            JSONObject jsPost = new JSONObject();
            JSONArray actionList = new JSONArray();

            // 【标记为已删除】
            // 使用软删除机制：将节点标记为 deleted
            node.setDeleted(true);
            
            // 添加更新操作
            // 虽然是删除操作，但仍然使用 update 类型的 action
            actionList.put(node.getUpdateAction(getActionId()));
            jsPost.put(GTaskStringUtils.GTASK_JSON_ACTION_LIST, actionList);

            // 添加客户端版本号
            jsPost.put(GTaskStringUtils.GTASK_JSON_CLIENT_VERSION, mClientVersion);

            // 发送请求
            postRequest(jsPost);
            
            // 清空更新数组
            mUpdateArray = null;
            
            Log.d(TAG, "节点删除成功: " + node.getGid());

        } catch (JSONException e) {
            Log.e(TAG, "JSON 处理错误: " + e.toString());
            e.printStackTrace();
            throw new ActionFailureException("删除节点: JSON 处理失败");
        }
    }

    // ========================================
    // 获取任务列表
    // ========================================
    
    /**
     * 获取用户的所有任务列表
     * 
     * 使用 GET 请求从服务器获取用户的任务列表数据。
     * 
     * 【请求方式】
     * GET https://mail.google.com/tasks/ig?auth={token}
     * 
     * 【响应格式】
     * 返回 HTML 页面，其中包含 JavaScript 代码：
     * <script>_setup({...});</script>
     * 
     * 提取并解析 JSON 对象后，结构如下：
     * {
     *   "t": {
     *     "lists": [
     *       { 任务列表1数据 },
     *       { 任务列表2数据 }
     *     ]
     *   }
     * }
     * 
     * 【与 POST 请求的区别】
     * - getTaskLists 使用 GET 请求获取所有数据
     * - 响应是一次性返回完整的任务列表结构
     * - 适合首次同步或完整刷新
     * 
     * @return 包含所有任务列表的 JSON 数组
     * @throws NetworkFailureException 网络或服务器错误
     */
    public JSONArray getTaskLists() throws NetworkFailureException {
        // 检查登录状态
        if (!mLoggedin) {
            Log.e(TAG, "请先登录");
            throw new ActionFailureException("未登录");
        }

        try {
            // 创建 GET 请求
            HttpGet httpGet = new HttpGet(mGetUrl);
            
            HttpResponse response = null;
            
            // 执行 GET 请求
            // 注意：这里没有附加 auth 参数
            // 因为在 loginGtask 时已经通过 Cookie 完成了认证
            response = mHttpClient.execute(httpGet);

            // 【获取并解析响应】
            String resString = getResponseContent(response.getEntity());
            
            // 提取 _setup() 中的 JSON 数据
            String jsBegin = "_setup(";
            String jsEnd = ")}</script>";
            int begin = resString.indexOf(jsBegin);
            int end = resString.lastIndexOf(jsEnd);
            String jsString = null;
            if (begin != -1 && end != -1 && begin < end) {
                jsString = resString.substring(begin + jsBegin.length(), end);
            }
            
            // 解析 JSON
            JSONObject js = new JSONObject(jsString);
            
            // 返回任务列表数组
            // 结构: js.getJSONObject("t").getJSONArray("lists")
            return js.getJSONObject("t").getJSONArray(GTaskStringUtils.GTASK_JSON_LISTS);
            
        } catch (ClientProtocolException e) {
            Log.e(TAG, "HTTP 协议错误: " + e.toString());
            e.printStackTrace();
            throw new NetworkFailureException("获取任务列表: HTTP GET 失败");
        } catch (IOException e) {
            Log.e(TAG, "IO 错误: " + e.toString());
            e.printStackTrace();
            throw new NetworkFailureException("获取任务列表: IO 错误");
        } catch (JSONException e) {
            Log.e(TAG, "JSON 解析错误: " + e.toString());
            e.printStackTrace();
            throw new ActionFailureException("获取任务列表: JSON 处理失败");
        }
    }

    /**
     * 获取指定任务列表中的所有任务
     * 
     * 使用 POST 请求获取特定任务列表的详细内容。
     * 
     * 【请求方式】
     * POST https://mail.google.com/tasks/r/ig
     * 
     * 【JSON 请求格式】
     * {
     *   "action_list": [
     *     {
     *       "action_type": "get_all",
     *       "action_id": 1,
     *       "list_id": "list_gid",
     *       "get_deleted": false
     *     }
     *   ],
     *   "client_version": 1234567890
     * }
     * 
     * 【参数说明】
     * - action_type: "get_all" 表示获取所有任务
     * - list_id: 要获取的任务列表 ID
     * - get_deleted: 是否包含已删除的任务
     *   - false: 只返回未删除的任务
     *   - true: 同时返回已删除的任务
     * 
     * 【与 getTaskLists 的区别】
     * - getTaskLists: GET 请求，获取所有任务列表的摘要
     * - getTaskList: POST 请求，获取特定列表的详细任务内容
     * - getTaskList 会在执行前先 commitUpdate，确保数据一致性
     * 
     * @param listGid 要获取的任务列表 ID
     * @return 包含该列表所有任务的 JSON 数组
     * @throws NetworkFailureException 网络或服务器错误
     */
    public JSONArray getTaskList(String listGid) throws NetworkFailureException {
        // 先提交待处理的更新
        commitUpdate();
        
        try {
            // 构建请求 JSON
            JSONObject jsPost = new JSONObject();
            JSONArray actionList = new JSONArray();
            JSONObject action = new JSONObject();

            // 【构建获取任务的操作】
            
            // 操作类型：get_all（获取所有）
            action.put(GTaskStringUtils.GTASK_JSON_ACTION_TYPE,
                    GTaskStringUtils.GTASK_JSON_ACTION_TYPE_GETALL);
            
            // 操作 ID
            action.put(GTaskStringUtils.GTASK_JSON_ACTION_ID, getActionId());
            
            // 任务列表 ID
            action.put(GTaskStringUtils.GTASK_JSON_LIST_ID, listGid);
            
            // 是否包含已删除的任务
            // false: 只获取未删除的任务
            action.put(GTaskStringUtils.GTASK_JSON_GET_DELETED, false);
            
            // 添加操作
            actionList.put(action);
            jsPost.put(GTaskStringUtils.GTASK_JSON_ACTION_LIST, actionList);

            // 添加客户端版本号
            jsPost.put(GTaskStringUtils.GTASK_JSON_CLIENT_VERSION, mClientVersion);

            // 发送请求
            JSONObject jsResponse = postRequest(jsPost);
            
            // 返回任务数组
            // 响应结构: { "tasks": [...] }
            return jsResponse.getJSONArray(GTaskStringUtils.GTASK_JSON_TASKS);
            
        } catch (JSONException e) {
            Log.e(TAG, "JSON 处理错误: " + e.toString());
            e.printStackTrace();
            throw new ActionFailureException("获取任务列表详情: JSON 处理失败");
        }
    }

    // ========================================
    // 辅助方法
    // ========================================
    
    /**
     * 获取当前同步使用的 Google 账号
     * 
     * 返回在 login 过程中保存的 Google 账号对象。
     * 可用于检查当前登录的账号信息。
     * 
     * @return 当前登录的 Account 对象，如果未登录返回 null
     */
    public Account getSyncAccount() {
        return mAccount;
    }

    /**
     * 重置更新数组
     * 
     * 清空待提交的更新队列。
     * 通常在同步完成后调用，用于清理状态。
     * 
     * 【使用场景】
     * - 同步完成后的清理
     * - 需要放弃所有未提交的更改时
     */
    public void resetUpdateArray() {
        mUpdateArray = null;
    }
}
