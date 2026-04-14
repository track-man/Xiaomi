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

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/**
 * GTaskSyncService - Google Task 同步服务入口
 * 
 * ========================================
 * 服务概述
 * ========================================
 * GTaskSyncService 是小米便签应用中负责与 Google Tasks 服务器进行数据同步的核心服务组件。
 * 它作为 Android Service 运行在后台，专门处理笔记数据与 Google Tasks 服务器之间的双向同步。
 * 
 * 设计意图：
 * 1. 【后台同步】将耗时的网络同步操作放在 Service 中执行，不阻塞主线程
 * 2. 【生命周期管理】利用 Android Service 的生命周期来管理同步任务的启动、取消和状态跟踪
 * 3. 【进程间通信】通过 Intent 和广播机制与 UI 层解耦，实现组件间的松耦合通信
 * 4. 【单任务模式】确保同一时间只有一个同步任务在执行，避免资源冲突和数据不一致
 * 
 * 使用方式：
 * - 启动同步：GTaskSyncService.startSync(activity)
 * - 取消同步：GTaskSyncService.cancelSync(context)
 * - 查询状态：GTaskSyncService.isSyncing()
 * - 获取进度：GTaskSyncService.getProgressString()
 * 
 * @see GTaskASyncTask 实际的异步同步任务实现
 * @see GTaskManager 同步管理器，负责具体的同步逻辑
 */
public class GTaskSyncService extends Service {

    // ========================================
    // Intent Action 相关的常量定义
    // ========================================
    
    /**
     * Intent 中用于传递动作类型的键名
     * 
     * 当外部组件通过 Intent 启动此 Service 时，通过此键名来指定要执行的动作类型
     * 取值可以是：ACTION_START_SYNC、ACTION_CANCEL_SYNC 或 ACTION_INVALID
     */
    public final static String ACTION_STRING_NAME = "sync_action_type";

    /**
     * 开始同步的动作标识
     * 
     * 当 Intent 中 ACTION_STRING_NAME 的值为此常量时，Service 将执行启动同步操作。
     * 这会创建一个新的 GTaskASyncTask 并开始执行笔记与 Google Tasks 的同步。
     * 
     * 使用场景：
     * - 用户点击同步按钮时
     * - 应用启动时自动同步
     * - 定时任务触发同步
     */
    public final static int ACTION_START_SYNC = 0;

    /**
     * 取消同步的动作标识
     * 
     * 当 Intent 中 ACTION_STRING_NAME 的值为此常量时，Service 将尝试取消正在进行的同步操作。
     * 如果当前没有正在执行的同步任务，此操作将被忽略。
     * 
     * 使用场景：
     * - 用户主动取消同步时
     * - 应用进入后台且同步时间过长时
     * - 需要中断同步以执行其他优先级更高的操作时
     */
    public final static int ACTION_CANCEL_SYNC = 1;

    /**
     * 无效/未知动作的标识
     * 
     * 当 Intent 中的动作类型无法识别或未指定时使用。
     * Service 收到此动作后将不执行任何操作，直接返回。
     * 
     * 作用：
     * - 作为 switch 语句的 default 分支
     * - 提供防御性编程，防止意外的动作类型导致异常
     */
    public final static int ACTION_INVALID = 2;

    // ========================================
    // 广播相关的常量定义
    // ========================================

    /**
     * 同步服务广播的名称（Action）
     * 
     * 这是同步服务向外发送状态更新的唯一广播通道。
     * 任何希望接收同步进度和状态更新的组件，都需要注册监听此 Action 的广播。
     * 
     * 广播过滤器示例：
     * IntentFilter filter = new IntentFilter(GTaskSyncService.GTASK_SERVICE_BROADCAST_NAME);
     * 
     * 广播内容包含：
     * - isSyncing: 当前是否正在同步
     * - progressMsg: 当前的同步进度消息
     * 
     * @see #sendBroadcast(String) 发送广播的方法
     */
    public final static String GTASK_SERVICE_BROADCAST_NAME = "net.micode.notes.gtask.remote.gtask_sync_service";

    /**
     * 广播中表示同步状态的键名
     * 
     * 这是一个 boolean 类型的值，表示当前是否正在进行同步操作。
     * 用于 UI 层显示同步状态（如显示/隐藏同步图标）。
     * 
     * 取值：
     * - true: 正在同步中
     * - false: 未在同步
     */
    public final static String GTASK_SERVICE_BROADCAST_IS_SYNCING = "isSyncing";

    /**
     * 广播中表示同步进度消息的键名
     * 
     * 这是一个 String 类型的值，包含当前同步操作的详细进度信息。
     * 用于向用户展示同步进展（如"正在同步第 5 个文件夹，共 10 个"）。
     * 
     * 格式示例：
     * - "正在同步..."
     * - "正在同步笔记: Meeting Notes"
     * - "同步完成: 15 条笔记已更新"
     */
    public final static String GTASK_SERVICE_BROADCAST_PROGRESS_MSG = "progressMsg";

    // ========================================
    // 静态变量 - 同步任务管理
    // ========================================

    /**
     * 当前正在执行的同步任务（单例模式）
     * 
     * 【为什么使用 static？】
     * 
     * 1. Service 多实例问题的解决：
     *    Android Service 可能因为系统资源紧张而被销毁后重新创建。
     *    使用 static 变量可以确保在整个应用进程中，同步任务实例是唯一的。
     *    即使 Service 被重建，只要进程还在，mSyncTask 仍然指向原始任务。
     * 
     * 2. 静态常驻性：
     *    static 变量存储在 Method Area（方法区），不会因为 Service 的生命周期
     *    变化而丢失。这保证了即使 Service.onCreate() 被多次调用，
     *    已启动的同步任务也不会被意外清空。
     * 
     * 3. 进程级共享：
     *    static 变量在同一个 Java 进程的所有线程间共享。
     *    这使得 Service 可以跨多个调用共享同步任务状态。
     * 
     * 【线程安全考虑】：
     * 当前实现中，对 mSyncTask 的访问都在主线程（UI 线程）和 Service 的 onStartCommand
     * 回调中执行。由于 Android Service 默认在主线程运行，且通过 Intent 串行处理请求，
     * 因此不需要额外的同步机制。但如果未来有多个线程访问，需要添加 synchronized。
     * 
     * 【单例模式的意义】：
     * - 确保同一时间只有一个同步任务在运行
     * - 避免同时进行多个同步导致的数据冲突和资源竞争
     * - 节省系统资源（不需要维护多个同步连接）
     * - 简化状态管理（只有"正在同步"和"未同步"两种状态）
     */
    private static GTaskASyncTask mSyncTask = null;

    /**
     * 当前同步的进度消息
     * 
     * 用于存储最后一次通过广播发送的进度消息。
     * 通过静态变量保存，使得外部组件可以随时调用 getProgressString() 
     * 查询当前的同步进度，而不需要依赖广播监听。
     * 
     * 这个设计的好处：
     * 1. 提供查询接口：外部可以随时获取最新进度，无需注册广播接收器
     * 2. 减少广播开销：如果 UI 只是偶尔需要显示进度，可以直接查询而非持续监听
     * 3. 解耦：广播用于状态变化通知，进度字符串用于状态查询
     * 
     * 注意：此变量仅保存最后一次的进度值，在同步开始时会被重置为空字符串
     */
    private static String mSyncProgress = "";

    // ========================================
    // 私有方法 - 内部同步逻辑
    // ========================================

    /**
     * 启动同步任务（内部方法）
     * 
     * 这是 Service 内部的同步启动逻辑，由 onStartCommand() 调用。
     * 
     * 【完整流程】：
     * 
     * 1. 检查是否已有同步任务在运行
     *    if (mSyncTask == null)
     *    - 如果没有正在运行的任务，才创建新任务（单例保证）
     *    - 如果已有任务在运行，直接忽略（防止重复启动）
     * 
     * 2. 创建 GTaskASyncTask 实例
     *    new GTaskASyncTask(this, listener)
     *    - 第一个参数：Context，用于访问应用资源和发起网络请求
     *    - 第二个参数：完成监听器，定义同步完成后的回调处理
     * 
     * 3. 设置完成监听器 onComplete()
     *    - 同步完成后将 mSyncTask 设为 null（标记同步结束）
     *    - 发送广播通知同步完成（msg 为空字符串）
     *    - 调用 stopSelf() 停止 Service，释放系统资源
     * 
     * 4. 发送开始同步的广播
     *    sendBroadcast("")
     *    - 通知所有监听者：同步已开始
     *    - 参数为空字符串表示进度消息为空或使用默认消息
     * 
     * 5. 启动异步执行
     *    mSyncTask.execute()
     *    - 调用 AsyncTask 的 execute() 方法开始后台同步
     *    - 同步过程在独立的线程中运行，不会阻塞主线程
     * 
     * 【状态变化】：
     * 开始时：mSyncTask = null  →  mSyncTask = new GTaskASyncTask
     * 广播：发送"开始同步"状态
     * 结束时（通过 onComplete 回调）：mSyncTask = null
     * 
     * @see GTaskASyncTask 异步任务的具体实现
     */
    private void startSync() {
        // 只有当没有正在运行的同步任务时才启动新任务
        if (mSyncTask == null) {
            // 创建新的异步同步任务，并设置完成监听器
            mSyncTask = new GTaskASyncTask(this, new GTaskASyncTask.OnCompleteListener() {
                /**
                 * 同步完成时的回调处理
                 * 
                 * 重要：此回调在后台线程中执行
                 * 需要注意与主线程的交互（如更新 UI 需要切换到主线程）
                 */
                public void onComplete() {
                    // 1. 清空任务引用，表示同步已完成
                    mSyncTask = null;
                    
                    // 2. 发送广播通知同步结束
                    //    传入空字符串表示进度消息为空或同步已完成
                    sendBroadcast("");
                    
                    // 3. 停止 Service
                    //    stopSelf() 会让系统在该 Service 不再被使用时销毁它
                    //    如果还有待处理的 Intent，Service 会继续运行直到处理完毕
                    stopSelf();
                }
            });
            
            // 发送广播通知同步已开始
            // 此时 mSyncTask != null，所以广播的 isSyncing = true
            sendBroadcast("");
            
            // 启动异步任务的执行
            // 这是非阻塞调用，会立即返回，actual 同步在后台线程中进行
            mSyncTask.execute();
        }
        // 如果 mSyncTask != null，说明已有同步任务在运行，不做任何操作
        // 这样自然地实现了单例模式，防止并发同步
    }

    /**
     * 取消正在进行的同步任务（内部方法）
     * 
     * 这是 Service 内部的取消同步逻辑，由 onStartCommand() 调用。
     * 
     * 【完整流程】：
     * 
     * 1. 检查是否有正在运行的同步任务
     *    if (mSyncTask != null)
     *    - 如果有任务在运行，则尝试取消
     *    - 如果没有任务在运行，不做任何操作
     * 
     * 2. 调用任务的取消方法
     *    mSyncTask.cancelSync()
     *    - 这会请求终止正在进行的同步操作
     *    - 具体的取消行为由 GTaskASyncTask.cancelSync() 实现
     *    - 通常会中断网络请求、标记取消状态等
     * 
     * 【取消机制说明】：
     * Android 的 AsyncTask 提供了 cancel(boolean mayInterruptIfRunning) 方法来取消任务。
     * 但这里使用了自定义的 cancelSync() 方法，可能的原因：
     * 1. 需要在取消前做额外的清理工作
     * 2. 需要更精细地控制取消过程（如优雅地中断 vs 立即中断）
     * 3. 可能在取消过程中需要与服务器交互（如发送取消请求）
     * 
     * 【取消后的状态】：
     * 调用 cancelSync() 后，同步任务会逐渐停止。完成后会触发 onComplete() 回调，
     * 在回调中会将 mSyncTask 设为 null，并发送广播通知同步已结束。
     * 
     * @see GTaskASyncTask#cancelSync() 具体取消逻辑
     */
    private void cancelSync() {
        // 只有当存在正在运行的同步任务时才执行取消操作
        if (mSyncTask != null) {
            // 调用任务的取消方法
            // 注意：取消是异步的，任务不会立即停止
            mSyncTask.cancelSync();
        }
        // 如果 mSyncTask == null，说明没有正在运行的同步任务，忽略取消请求
    }

    // ========================================
    // Service 生命周期方法
    // ========================================

    /**
     * Service 创建时调用的回调方法
     * 
     * onCreate() 在 Service 第一次被创建时调用，整个 Service 生命周期只会被调用一次。
     * 适合进行一些一次性的初始化操作。
     * 
     * 【在此处的作用】：
     * 将 mSyncTask 设为 null，确保每次 Service 创建时都是干净的初始状态。
     * 这是一个防御性编程的措施，防止因为 Service 被重建而保留了旧的引用。
     * 
     * 【注意】：
     * 虽然 Service 被重建时会再次调用 onCreate()，
     * 但由于 mSyncTask 是 static 的，它不会因为 Service 的销毁而丢失。
     * 因此这里的 null 初始化主要是为了代码的清晰性和防御性。
     * 
     * 【调用时机】：
     * - 第一次启动 Service 时
     * - Service 被系统销毁后重新创建时（如果配置允许）
     */
    @Override
    public void onCreate() {
        // 初始化同步任务为空
        // 确保 Service 启动时处于干净的初始状态
        mSyncTask = null;
    }

    /**
     * Service 启动时的主入口方法
     * 
     * 每次通过 startService() 启动或重新启动 Service 时都会调用此方法。
     * 这是处理外部请求（启动同步、取消同步）的核心方法。
     * 
     * 【参数说明】：
     * - intent: 启动 Service 时传递的 Intent，包含了要执行的动作类型
     * - flags: 启动标志，可能的值包括：
     *   - 0: 正常启动
     *   - START_FLAG_REDELIVERY: 如果 Service 之前返回了 START_REDELIVER_INTENT，
     *     系统会重新传递原始 Intent
     * - startId: 唯一的启动 ID，每次 startService() 调用都会递增
     * 
     * 【返回值说明】：
     * - START_STICKY: 如果 Service 被系统销毁后重启，会用 null Intent 调用 onStartCommand()
     *   这是适合同步服务这种"后台任务"的返回值，确保任务即使被打断也能重新启动
     * - START_NOT_STICKY: 如果系统杀死 Service，不会重新启动
     * - START_REDELIVER_INTENT: 如果系统杀死 Service，会重新传递最后一个 Intent
     * 
     * 【完整流程】：
     * 
     * 1. 从 Intent 中获取额外数据
     *    Bundle bundle = intent.getExtras()
     *    - 从 Intent 中提取存储在 extras 中的键值对
     * 
     * 2. 检查是否有有效的动作类型
     *    if (bundle != null && bundle.containsKey(ACTION_STRING_NAME))
     *    - bundle 不为空
     *    - bundle 中包含 ACTION_STRING_NAME 键
     * 
     * 3. 根据动作类型执行相应操作
     *    switch (bundle.getInt(ACTION_STRING_NAME, ACTION_INVALID))
     *    - ACTION_START_SYNC: 调用 startSync() 开始同步
     *    - ACTION_CANCEL_SYNC: 调用 cancelSync() 取消同步
     *    - default (ACTION_INVALID 或其他): 不做任何操作
     * 
     * 4. 返回 START_STICKY
     *    - 确保 Service 被系统重启后仍能继续同步任务
     *    - 适合长时间运行的后台同步任务
     * 
     * @param intent 包含动作指令的 Intent
     * @param flags 启动标志
     * @param startId 启动 ID
     * @return Service 的启动模式（START_STICKY 或 super 的返回值）
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 安全地获取 Intent 中的额外数据
        Bundle bundle = intent.getExtras();
        
        // 检查是否包含有效的动作类型参数
        if (bundle != null && bundle.containsKey(ACTION_STRING_NAME)) {
            // 提取动作类型，如果不存在则默认为 ACTION_INVALID
            int actionType = bundle.getInt(ACTION_STRING_NAME, ACTION_INVALID);
            
            // 根据动作类型分发处理
            switch (actionType) {
                case ACTION_START_SYNC:
                    // 启动同步任务
                    startSync();
                    break;
                    
                case ACTION_CANCEL_SYNC:
                    // 取消正在进行的同步任务
                    cancelSync();
                    break;
                    
                default:
                    // 无效的动作类型，不做任何处理
                    // 这是防御性编程，处理未知或不支持的动作
                    break;
            }
            
            // 返回 START_STICKY
            // 含义：如果 Service 被系统杀死后会重新创建，并用 null Intent 调用 onStartCommand
            // 这确保了即使系统资源紧张导致 Service 被销毁，也能保证同步任务有机会继续
            return START_STICKY;
        }
        
        // 如果没有包含有效参数，调用父类的默认处理
        return super.onStartCommand(intent, flags, startId);
    }

    /**
     * 系统内存不足时的回调
     * 
     * 当系统内存不足时，Android 会调用此方法通知所有正在运行的 Service。
     * 这是 Service 释放资源、降低内存占用的最后机会。
     * 
     * 【在此处的作用】：
     * 如果有正在进行的同步任务，取消它以释放系统资源。
     * 
     * 【为什么取消同步？】
     * 1. 同步任务通常涉及网络请求和临时数据结构，会占用较多内存
     * 2. 网络同步不是用户当前正在使用的功能，可以延迟到内存恢复后再进行
     * 3. 取消后用户可以手动重新触发同步，保证数据最终一致性
     * 
     * 【取消策略说明】：
     * 这里选择直接取消同步而不是尝试保存进度后暂停。
     * 这是因为：
     * 1. 同步操作的中间状态难以保存和恢复
     * 2. 网络请求不支持暂停/恢复
     * 3. 简化实现，避免复杂的状态管理
     * 
     * 【最佳实践】：
     * 在实际应用中，如果同步任务很耗时，可能需要实现更复杂的内存管理策略，
     * 比如：
     * - 将进度保存到数据库
     * - 实现断点续传机制
     * - 尽可能释放非必要的内存（如缓存图片等）
     */
    @Override
    public void onLowMemory() {
        // 如果有正在运行的同步任务，取消它以释放资源
        if (mSyncTask != null) {
            mSyncTask.cancelSync();
        }
    }

    /**
     * 绑定 Service 时调用的回调方法（暂不支持绑定模式）
     * 
     * 这是 Android Service 绑定机制的入口点，用于实现进程间通信（IPC）。
     * 当前 Service 不支持通过 bindService() 方式启动，因此始终返回 null。
     * 
     * 【为什么返回 null？】
     * 1. 设计选择：此 Service 通过 startService() 启动，返回 START_STICKY，
     *    不需要也不应该支持绑定模式
     * 2. 简化设计：避免处理绑定的生命周期复杂性
     * 3. 单向通信：只需要向外部发送广播通知，不需要双向通信
     * 
     * 【绑定模式 vs 启动模式】：
     * - 启动模式（startService）：启动后台服务，不在乎是否有组件绑定。
     *   Service 会一直运行直到 stopSelf() 或 stopService() 被调用。
     * - 绑定模式（bindService）：允许其他组件与 Service 通信，返回 IBinder 接口。
     *   Service 只在有组件绑定时运行，所有组件取消绑定后 Service 被销毁。
     * 
     * @param intent 绑定请求的 Intent
     * @return null，表示不支持绑定模式
     */
    @Override
    public IBinder onBind(Intent intent) {
        // 不支持绑定模式，始终返回 null
        return null;
    }

    // ========================================
    // 广播发送方法
    // ========================================

    /**
     * 发送同步状态广播
     * 
     * 这是 Service 向外部组件通知同步状态的唯一渠道。
     * 通过发送本地广播（LocalBroadcast），只通知本应用内的组件。
     * 
     * 【广播内容】：
     * 1. isSyncing (boolean): 当前是否正在同步
     *    - mSyncTask != null → true
     *    - mSyncTask == null → false
     * 
     * 2. progressMsg (String): 当前的进度消息
     *    - 用于向用户展示同步进展
     *    - 可能是空字符串表示无详细信息
     * 
     * 【调用时机】：
     * - startSync() 开始时调用，通知同步开始
     * - GTaskASyncTask 的 onComplete 回调中调用，通知同步结束
     * 
     * 【广播机制说明】：
     * 
     * 1. 【为什么要用广播？】
     *    - 解耦：Service 和 UI 组件不需要直接引用
     *    - 异步：广播是异步的，不会阻塞 Service 的执行
     *    - 简单：不需要复杂的回调接口或接口实现
     * 
     * 2. 【为什么用本地广播？】
     *    - 使用 LocalBroadcastManager 发送的广播只能在应用内部传递
     *    - 更安全：其他应用无法接收或伪造广播
     *    - 更高效：不需要跨进程通信开销
     * 
     * 3. 【广播的使用方式】：
     *    ```java
     *    // 注册广播接收器
     *    BroadcastReceiver receiver = new BroadcastReceiver() {
     *        @Override
     *        public void onReceive(Context context, Intent intent) {
     *            boolean isSyncing = intent.getBooleanExtra(
     *                GTaskSyncService.GTASK_SERVICE_BROADCAST_IS_SYNCING, false);
     *            String progress = intent.getStringExtra(
     *                GTaskSyncService.GTASK_SERVICE_BROADCAST_PROGRESS_MSG);
     *            // 更新 UI...
     *        }
     *    };
     *    IntentFilter filter = new IntentFilter(GTaskSyncService.GTASK_SERVICE_BROADCAST_NAME);
     *    LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter);
     *    ```
     * 
     * @param msg 同步进度消息，用于通知外部当前的同步进展
     * @see #GTASK_SERVICE_BROADCAST_NAME 广播的 Action 名称
     * @see #GTASK_SERVICE_BROADCAST_IS_SYNCING 同步状态键名
     * @see #GTASK_SERVICE_BROADCAST_PROGRESS_MSG 进度消息键名
     */
    public void sendBroadcast(String msg) {
        // 1. 更新内部的进度消息存储
        mSyncProgress = msg;
        
        // 2. 创建广播 Intent
        Intent intent = new Intent(GTASK_SERVICE_BROADCAST_NAME);
        
        // 3. 添加同步状态数据
        //    mSyncTask != null 表示有任务在运行
        intent.putExtra(GTASK_SERVICE_BROADCAST_IS_SYNCING, mSyncTask != null);
        
        // 4. 添加进度消息数据
        intent.putExtra(GTASK_SERVICE_BROADCAST_PROGRESS_MSG, msg);
        
        // 5. 发送广播
        //    这会通知所有注册监听此广播的组件
        sendBroadcast(intent);
    }

    // ========================================
    // 公开静态方法 - 供外部组件调用
    // ========================================

    /**
     * 启动同步任务（公开静态方法，供外部调用）
     * 
     * 这是启动同步的标准入口方法，供 Activity、Fragment 或其他组件调用。
     * 
     * 【使用场景】：
     * 1. 用户点击同步按钮时调用
     * 2. 应用启动时自动同步（如在 MainActivity 中调用）
     * 3. 定时任务触发同步（如使用 AlarmManager）
     * 4. 检测到数据变化时触发同步
     * 
     * 【调用示例】：
     * ```java
     * // 在 Activity 中
     * GTaskSyncService.startSync(this);
     * 
     * // 在 Fragment 中
     * GTaskSyncService.startSync(getActivity());
     * ```
     * 
     * 【完整流程】：
     * 
     * 1. 设置 Activity 上下文到 GTaskManager
     *    GTaskManager.getInstance().setActivityContext(activity)
     *    - GTaskManager 需要 Activity Context 来发起 OAuth 认证等需要 Activity 的操作
     *    - 这确保了同步过程中如需用户授权，可以正确弹出授权页面
     * 
     * 2. 创建启动 Intent
     *    new Intent(activity, GTaskSyncService.class)
     *    - 明确指定要启动的 Service 类
     *    - 使用 activity 作为 Context，确保在正确的主题和权限环境下启动
     * 
     * 3. 设置动作类型
     *    intent.putExtra(ACTION_STRING_NAME, ACTION_START_SYNC)
     *    - 告诉 Service 要执行的是"启动同步"操作
     * 
     * 4. 启动 Service
     *    activity.startService(intent)
     *    - 这是异步调用，会立即返回
     *    - Service 会在后台线程中开始同步
     * 
     * 【注意事项】：
     * - 如果已经有同步任务在运行，startSync() 会忽略此次请求
     * - 需要在 AndroidManifest.xml 中声明 GTaskSyncService
     * - 可能需要处理权限（如网络权限、账户权限等）
     * 
     * @param activity 启动同步的 Activity（用于创建 Intent 和设置 Context）
     * @see #ACTION_START_SYNC 启动同步的动作常量
     * @see #isSyncing() 查询是否正在同步
     * @see GTaskManager 同步管理器的具体实现
     */
    public static void startSync(Activity activity) {
        // 设置 Activity 上下文，供 GTaskManager 在需要时使用（如 OAuth 认证）
        GTaskManager.getInstance().setActivityContext(activity);
        
        // 创建指向 GTaskSyncService 的 Intent
        Intent intent = new Intent(activity, GTaskSyncService.class);
        
        // 设置动作为"启动同步"
        intent.putExtra(GTaskSyncService.ACTION_STRING_NAME, GTaskSyncService.ACTION_START_SYNC);
        
        // 启动 Service
        // 注意：startService() 是非阻塞的，会立即返回
        // Service 的 onStartCommand() 会异步执行
        activity.startService(intent);
    }

    /**
     * 取消正在进行的同步任务（公开静态方法，供外部调用）
     * 
     * 这是取消同步的标准入口方法，供 Activity、Fragment 或其他组件调用。
     * 
     * 【使用场景】：
     * 1. 用户点击"取消同步"按钮时调用
     * 2. 应用进入后台且同步时间过长时自动取消
     * 3. 用户登出账户时取消待执行的同步
     * 4. 检测到网络状态变化（如从 WiFi 切换到移动网络）时取消
     * 
     * 【调用示例】：
     * ```java
     * // 在 Activity 中
     * GTaskSyncService.cancelSync(this);
     * 
     * // 在 BroadcastReceiver 中
     * GTaskSyncService.cancelSync(context);
     * 
     * // 在 Service 中
     * GTaskSyncService.cancelSync(getApplicationContext());
     * ```
     * 
     * 【完整流程】：
     * 
     * 1. 创建取消 Intent
     *    new Intent(context, GTaskSyncService.class)
     *    - 可以使用任何 Context，不一定是 Activity
     *    - 因为取消操作不需要启动新的 Activity 或进行 UI 相关操作
     * 
     * 2. 设置动作类型
     *    intent.putExtra(ACTION_STRING_NAME, ACTION_CANCEL_SYNC)
     *    - 告诉 Service 要执行的是"取消同步"操作
     * 
     * 3. 启动 Service（传递取消命令）
     *    context.startService(intent)
     *    - 即使 Service 之前没有启动，调用此方法也是安全的
     *    - Service 收到 ACTION_CANCEL_SYNC 后会忽略（因为 mSyncTask == null）
     * 
     * 【注意事项】：
     * - 如果没有正在进行的同步任务，此调用不会有任何效果
     * - 取消是异步的，不会立即停止，可能需要一些时间才能完成
     * - 取消后，用户可以再次调用 startSync() 重新开始同步
     * 
     * @param context 任意 Context（用于创建 Intent）
     * @see #ACTION_CANCEL_SYNC 取消同步的动作常量
     * @see #isSyncing() 查询是否正在同步
     */
    public static void cancelSync(Context context) {
        // 创建指向 GTaskSyncService 的 Intent
        Intent intent = new Intent(context, GTaskSyncService.class);
        
        // 设置动作为"取消同步"
        intent.putExtra(GTaskSyncService.ACTION_STRING_NAME, GTaskSyncService.ACTION_CANCEL_SYNC);
        
        // 发送取消命令
        // 注意：这里用 context.startService 而不是 activity.startService
        // 因为取消操作不需要 Activity，只需要在正确的应用上下文中即可
        context.startService(intent);
    }

    /**
     * 查询当前是否正在同步（公开静态方法）
     * 
     * 这是查询同步状态的便捷方法，无需注册广播接收器即可快速判断。
     * 
     * 【使用场景】：
     * 1. 在 UI 更新前检查是否需要显示同步状态
     * 2. 在 onResume() 中检查是否需要刷新同步按钮的状态
     * 3. 在需要条件执行其他操作前检查同步状态
     * 4. 单元测试中验证同步状态
     * 
     * 【调用示例】：
     * ```java
     * // 检查是否正在同步
     * if (GTaskSyncService.isSyncing()) {
     *     // 显示同步中的 UI
     *     syncButton.setText("同步中...");
     *     syncButton.setEnabled(false);
     * } else {
     *     // 显示可以同步的 UI
     *     syncButton.setText("同步");
     *     syncButton.setEnabled(true);
     * }
     * 
     * // 在启动同步前检查
     * if (!GTaskSyncService.isSyncing()) {
     *     GTaskSyncService.startSync(activity);
     * }
     * ```
     * 
     * 【实现原理】：
     * 直接返回静态变量 mSyncTask != null 的值。
     * - mSyncTask != null 表示有任务在运行
     * - mSyncTask == null 表示没有任务在运行
     * 
     * 【与广播机制的区别】：
     * - isSyncing(): 主动查询，返回当前时刻的状态
     * - 广播通知：被动接收，在状态变化时收到通知
     * 
     * 推荐用法：
     * - 状态查询：使用 isSyncing()
     * - 实时监听：使用广播接收器
     * - 两者结合：初始显示用 isSyncing()，后续状态变化用广播更新
     * 
     * @return true 表示正在同步中，false 表示未在同步
     * @see #mSyncTask 同步任务引用
     * @see #getProgressString() 获取同步进度消息
     */
    public static boolean isSyncing() {
        // 如果 mSyncTask 不为 null，说明有同步任务在运行
        return mSyncTask != null;
    }

    /**
     * 获取当前的同步进度消息（公开静态方法）
     * 
     * 这是获取同步进度文本的便捷方法，无需注册广播接收器即可获取最新进度。
     * 
     * 【使用场景】：
     * 1. 在 UI 中显示当前的同步进度文本
     * 2. 在日志中记录同步进度
     * 3. 在错误处理中获取失败信息
     * 
     * 【调用示例】：
     * ```java
     * // 获取并显示进度消息
     * String progress = GTaskSyncService.getProgressString();
     * if (progress != null && !progress.isEmpty()) {
     *     statusTextView.setText(progress);
     * }
     * ```
     * 
     * 【实现原理】：
     * 直接返回静态变量 mSyncProgress 的值。
     * - mSyncProgress 在每次调用 sendBroadcast() 时被更新
     * - 如果没有调用过 sendBroadcast() 或同步已完成，返回空字符串
     * 
     * 【与广播机制的区别】：
     * - getProgressString(): 返回最后一次广播的进度值
     * - 广播通知：每次进度变化都会收到通知
     * 
     * 【注意事项】：
     * - 如果从未发送过进度广播，返回空字符串
     * - 返回值可能为空字符串（""），需要调用者处理
     * - 不适合监听实时进度变化，建议使用广播机制
     * 
     * @return 当前同步的进度消息字符串，可能为空字符串
     * @see #mSyncProgress 进度消息的存储变量
     * @see #sendBroadcast(String) 更新进度消息的方法
     */
    public static String getProgressString() {
        // 返回当前的进度消息
        // 这是在最后一次 sendBroadcast() 调用时设置的值
        return mSyncProgress;
    }
}
