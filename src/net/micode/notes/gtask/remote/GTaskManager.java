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
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.data.MetaData;
import net.micode.notes.gtask.data.Node;
import net.micode.notes.gtask.data.SqlNote;
import net.micode.notes.gtask.data.Task;
import net.micode.notes.gtask.data.TaskList;
import net.micode.notes.gtask.exception.ActionFailureException;
import net.micode.notes.gtask.exception.NetworkFailureException;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.GTaskStringUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;


/**
 * GTaskManager - Google Task 同步管理器（单例模式）
 * 
 * 【类概述】
 * 这是小米便签应用中最核心的同步管理类，负责将本地便签数据与 Google Tasks 服务进行双向同步。
 * 整个同步流程遵循：登录验证 → 获取远程列表 → 数据对比 → 冲突解决 → 批量提交的顺序执行。
 * 
 * 【单例模式说明】
 * - 使用经典的懒汉式单例实现（线程安全）
 * - getInstance() 方法使用 synchronized 关键字确保多线程环境下的线程安全
 * - 整个类只有一个实例，所有同步操作都通过这个唯一实例进行
 * - 实例在首次调用 getInstance() 时创建，之后的调用直接返回已创建的实例
 * 
 * 【线程安全说明】
 * - 同步状态标志 mSyncing 和 mCancelled 使用 volatile 语义（通过 synchronized 访问）
 * - 所有公共同步方法（setActivityContext、sync、cancelSync）都是 synchronized
 * - 内部同步逻辑通过检查 mCancelled 标志来支持取消操作
 * 
 * 【四种同步状态含义】
 * 1. STATE_SUCCESS (0) - 同步成功完成，所有数据已正确同步
 * 2. STATE_NETWORK_ERROR (1) - 网络错误，Google 服务器连接失败
 * 3. STATE_INTERNAL_ERROR (2) - 内部错误，可能是 JSON 解析失败、数据格式错误等
 * 4. STATE_SYNC_IN_PROGRESS (3) - 同步正在进行中，拒绝重复同步请求
 * 5. STATE_SYNC_CANCELLED (4) - 同步被用户取消
 * 
 * 【同步流程完整步骤】
 * 1. sync() 入口方法被调用
 * 2. 检查是否已有同步在进行（防止重复同步）
 * 3. 清理所有临时数据（HashMap 等）
 * 4. 调用 GTaskClient.login() 进行 Google 账号登录验证
 * 5. 调用 initGTaskList() 获取远程 Google Tasks 列表和任务
 * 6. 调用 syncContent() 进行实际的数据同步
 *    - 处理本地删除的便签（远程也要删除）
 *    - 同步文件夹（根文件夹、通话记录文件夹、自定义文件夹）
 *    - 同步便签（新增、更新、删除）
 *    - 处理远程新增的内容（添加到本地）
 * 7. 调用 GTaskClient.commitUpdate() 提交所有远程更新
 * 8. 调用 refreshLocalSyncId() 刷新本地同步 ID
 * 9. 清理所有临时数据，返回同步状态
 * 
 * 【数据结构说明】
 * - mGTaskListHashMap: Google Task 列表的映射表，key 是 Google Task ID，value 是 TaskList 对象
 *                       用于存储从远程获取的所有便签文件夹（TaskList）
 * - mGTaskHashMap: Google Task 节点的映射表，key 是 Google Task ID，value 是 Node 对象
 *                  用于存储从远程获取的所有便签和文件夹（Task/TaskList）
 *                  在同步过程中会逐步移除已处理的节点，处理完成后剩余的就是需要新增到本地的
 * - mMetaHashMap: 元数据映射表，存储便签的元信息，用于追踪便签的同步状态
 * - mMetaList: 元数据列表，用于在 Google Tasks 上创建一个特殊的列表来存储所有便签的元数据
 * - mLocalDeleteIdMap: 本地删除的便签 ID 集合，在同步完成后批量删除
 * - mGidToNid: Google ID 到本地 ID 的映射，用于快速查找本地便签对应的 Google ID
 * - mNidToGid: 本地 ID 到 Google ID 的映射，用于快速查找 Google 便签对应的本地 ID
 * 
 * 【冲突解决策略】
 * - 冲突检测：当本地和远程都修改了同一个便签时发生冲突
 * - 当前策略：以本地为准，将本地修改推送到远程（updateRemoteNode）
 * - 注释中提到"merging both modifications maybe a good idea"，暗示未来可能实现更智能的合并策略
 * 
 * 【同步动作类型】（定义在 Node 类中）
 * - SYNC_ACTION_ADD_LOCAL: 远程新增，需要添加到本地数据库
 * - SYNC_ACTION_ADD_REMOTE: 本地新增，需要推送到远程 Google Tasks
 * - SYNC_ACTION_DEL_LOCAL: 远程已删除，需要从本地删除
 * - SYNC_ACTION_DEL_REMOTE: 本地已删除，需要从远程删除
 * - SYNC_ACTION_UPDATE_LOCAL: 远程有更新，需要更新本地
 * - SYNC_ACTION_UPDATE_REMOTE: 本地有更新，需要推送到远程
 * - SYNC_ACTION_UPDATE_CONFLICT: 冲突发生，以本地为准更新远程
 * - SYNC_ACTION_NONE: 无需同步
 * - SYNC_ACTION_ERROR: 同步错误
 * 
 * 【特殊文件夹处理】
 * - 根文件夹 (ID_ROOT_FOLDER): 所有便签的默认父文件夹，对应 Google Tasks 中的默认列表
 * - 通话记录文件夹 (ID_CALL_RECORD_FOLDER): 存储通话时创建的便签
 * - 元数据文件夹: 内部使用的特殊文件夹，用于存储便签的同步元信息
 * 
 * @author MiCode Open Source Community
 * @since 2010-2011
 */
public class GTaskManager {
    /** 日志标签，用于 Log.d()、Log.e() 等调试输出 */
    private static final String TAG = GTaskManager.class.getSimpleName();

    /**
     * 同步状态常量：成功
     * 表示同步操作成功完成，所有数据已正确同步到本地或远程
     */
    public static final int STATE_SUCCESS = 0;

    /**
     * 同步状态常量：网络错误
     * 表示无法连接到 Google 服务器，可能是网络不可用或账号认证失败
     */
    public static final int STATE_NETWORK_ERROR = 1;

    /**
     * 同步状态常量：内部错误
     * 表示发生了程序内部错误，如 JSON 解析失败、数据验证失败等
     */
    public static final int STATE_INTERNAL_ERROR = 2;

    /**
     * 同步状态常量：同步正在进行中
     * 表示当前已有同步任务在执行，新同步请求会被拒绝
     */
    public static final int STATE_SYNC_IN_PROGRESS = 3;

    /**
     * 同步状态常量：同步已取消
     * 表示用户主动取消了正在进行的同步操作
     */
    public static final int STATE_SYNC_CANCELLED = 4;

    /** 单例实例，全局唯一 */
    private static GTaskManager mInstance = null;

    /** Activity 上下文，用于获取 Google 认证令牌（AuthToken） */
    private Activity mActivity;

    /** Application/Service 上下文，用于访问 ContentResolver */
    private Context mContext;

    /** 内容解析器，用于访问本地便签数据库 */
    private ContentResolver mContentResolver;

    /**
     * 同步进行中标志
     * true: 表示当前有同步任务正在执行，防止重复同步
     * 在 sync() 方法开始时设为 true，同步完成后（无论成功与否）设为 false
     */
    private boolean mSyncing;

    /**
     * 同步取消标志
     * true: 表示用户请求取消同步，方法内部会检查此标志并提前返回
     * 通过 cancelSync() 方法设置为 true
     */
    private boolean mCancelled;

    /**
     * Google Task 列表哈希表
     * 键（key）: Google Task List 的唯一标识符（GID）
     * 值（value）: TaskList 对象，代表一个便签文件夹
     * 
     * 【用途】
     * 1. 存储从 Google 服务器获取的所有便签文件夹列表
     * 2. 在同步便签时，用于查找便签所属的父文件夹
     * 3. 在 addRemoteNode() 中用于检查文件夹是否已存在
     * 
     * 【生命周期】
     * - sync() 开始时清空
     * - initGTaskList() 中填充远程数据
     * - syncFolder() 中用于创建新的远程文件夹
     * - sync() 结束时清空
     */
    private HashMap<String, TaskList> mGTaskListHashMap;

    /**
     * Google Task 节点哈希表
     * 键（key）: Google Task/TaskList 的唯一标识符（GID）
     * 值（value）: Node 对象，代表一个便签（Task）或文件夹（TaskList）
     * 
     * 【用途】
     * 1. 存储从 Google 服务器获取的所有便签和文件夹
     * 2. 在 syncContent() 中，通过 GID 查找本地便签对应的远程节点
     * 3. 同步过程中，已处理的节点会被移除，处理完成后剩余的就是远程新增需要添加到本地的
     * 
     * 【核心同步算法】
     * 遍历本地便签数据库时：
     * - 如果 mGTaskHashMap 中存在该 GID，说明两边都有该便签，需要进一步判断是更新还是删除
     * - 如果不存在，说明是本地新增，需要推送到远程
     * 
     * 遍历完本地便签后，mGTaskHashMap 中剩余的节点就是远程新增的，需要添加到本地
     */
    private HashMap<String, Node> mGTaskHashMap;

    /**
     * 元数据哈希表
     * 键（key）: Google Task 的 GID
     * 值（value）: MetaData 对象，包含便签的元信息
     * 
     * 【用途】
     * 存储便签的同步元信息，用于追踪便签的创建时间、修改时间等同步状态
     * 元数据存储在 Google Tasks 上的一个特殊列表中（MIUI_FOLDER_META）
     */
    private HashMap<String, MetaData> mMetaHashMap;

    /**
     * 元数据列表
     * 用于在 Google Tasks 上创建和管理所有便签的元数据
     * 元数据列表的名称为：MIUI_FOLDER_PREFIX + FOLDER_META
     */
    private TaskList mMetaList;

    /**
     * 本地删除 ID 映射表
     * 存储在本地已删除但尚未同步到远程的便签 ID
     * 
     * 【用途】
     * 在同步过程中，当发现本地便签已移到垃圾箱（TRASH_FOLDER）时：
     * 1. 先将其 GID 加入此集合
     * 2. 在 syncContent() 结束时，如果同步未被取消，批量删除这些便签
     * 
     * 【为什么需要延迟删除】
     * 因为同步过程中可能需要读取这些便签的信息，所以先标记，等到同步完成后再删除
     */
    private HashSet<Long> mLocalDeleteIdMap;

    /**
     * Google ID 到本地 ID 的映射
     * 键（key）: Google Task 的 GID（String）
     * 值（value）: 本地便签的 ID（Long）
     * 
     * 【用途】
     * 1. 在同步过程中记录已处理的 GID-NID 对应关系
     * 2. 当需要查找某个远程便签对应的本地便签 ID 时快速查询
     * 3. 用于在 addLocalNode() 中更新便签的 GID
     */
    private HashMap<String, Long> mGidToNid;

    /**
     * 本地 ID 到 Google ID 的映射
     * 键（key）: 本地便签的 ID（Long）
     * 值（value）: Google Task 的 GID（String）
     * 
     * 【用途】
     * 1. 在同步过程中记录已处理的 NID-GID 对应关系
     * 2. 当需要查找某个本地便签对应的远程 GID 时快速查询
     * 3. 用于在 addRemoteNode() 中查找便签所属的父文件夹 GID
     */
    private HashMap<Long, String> mNidToGid;

    /**
     * 私有构造函数 - 实现单例模式
     * 
     * 【设计说明】
     * 构造函数私有化，防止外部直接创建实例，确保全局只有一个 GTaskManager 实例
     * 
     * 【初始化工作】
     * 初始化所有成员变量：
     * - mSyncing = false: 当前没有同步在进行
     * - mCancelled = false: 未请求取消同步
     * - 初始化所有 HashMap 和 HashSet 为空集合
     */
    private GTaskManager() {
        mSyncing = false;
        mCancelled = false;
        mGTaskListHashMap = new HashMap<String, TaskList>();
        mGTaskHashMap = new HashMap<String, Node>();
        mMetaHashMap = new HashMap<String, MetaData>();
        mMetaList = null;
        mLocalDeleteIdMap = new HashSet<Long>();
        mGidToNid = new HashMap<String, Long>();
        mNidToGid = new HashMap<Long, String>();
    }

    /**
     * 获取 GTaskManager 单例实例（线程安全）
     * 
     * 【同步机制】
     * 使用 synchronized 关键字确保多线程环境下的线程安全
     * 当多个线程同时调用此方法时，只有一个线程能进入同步块，其他线程需要等待
     * 
     * 【懒加载】
     * 采用懒加载（Lazy Loading）模式，实例在首次调用时才创建
     * 如果从未调用此方法，则不会创建实例，节省资源
     * 
     * 【调用示例】
     * GTaskManager manager = GTaskManager.getInstance();
     * 
     * @return GTaskManager 单例实例
     */
    public static synchronized GTaskManager getInstance() {
        if (mInstance == null) {
            mInstance = new GTaskManager();
        }
        return mInstance;
    }

    /**
     * 设置 Activity 上下文
     * 
     * 【用途】
     * Activity 上下文用于获取 Google 账号的认证令牌（AuthToken）
     * 在调用 GTaskClient.login() 之前必须先设置此上下文
     * 
     * 【线程安全】
     * 使用 synchronized 关键字确保线程安全
     * 
     * @param activity Activity 上下文，用于获取 AuthToken
     */
    public synchronized void setActivityContext(Activity activity) {
        // 用于获取 Google 账号认证令牌
        mActivity = activity;
    }

    /**
     * 执行同步操作 - 同步入口方法
     * 
     * 【同步流程】
     * 1. 检查是否已有同步在进行（mSyncing 标志）
     * 2. 设置 Context 和 ContentResolver
     * 3. 初始化同步状态（mSyncing=true, mCancelled=false）
     * 4. 清空所有临时数据结构
     * 5. 登录 Google 账号
     * 6. 获取远程 Google Tasks 列表（initGTaskList）
     * 7. 执行数据同步（syncContent）
     * 8. 提交远程更新
     * 9. 刷新本地同步 ID
     * 10. 清理资源，返回同步状态
     * 
     * 【防重复同步】
     * 如果当前已有同步在进行，直接返回 STATE_SYNC_IN_PROGRESS
     * 这确保了同一时间只有一个同步任务在执行
     * 
     * 【取消支持】
     * 同步过程中会检查 mCancelled 标志
     * 用户可以通过 cancelSync() 方法请求取消同步
     * 取消后正在执行的方法会提前返回
     * 
     * 【异常处理】
     * - NetworkFailureException: 网络相关错误，返回 STATE_NETWORK_ERROR
     * - ActionFailureException: 操作失败错误，返回 STATE_INTERNAL_ERROR
     * - 其他异常: 统一处理为内部错误，返回 STATE_INTERNAL_ERROR
     * 
     * @param context Context 上下文，用于访问 ContentResolver
     * @param asyncTask GTaskASyncTask，用于发布同步进度
     * @return 同步状态码（STATE_SUCCESS, STATE_NETWORK_ERROR 等）
     */
    public int sync(Context context, GTaskASyncTask asyncTask) {
        // 检查是否已有同步在进行
        if (mSyncing) {
            Log.d(TAG, "同步正在进行中...");
            return STATE_SYNC_IN_PROGRESS;
        }
        
        // 保存上下文
        mContext = context;
        mContentResolver = mContext.getContentResolver();
        
        // 设置同步状态
        mSyncing = true;    // 同步开始
        mCancelled = false; // 清除取消标志
        
        // 清空所有临时数据结构，为新的同步做准备
        mGTaskListHashMap.clear();
        mGTaskHashMap.clear();
        mMetaHashMap.clear();
        mLocalDeleteIdMap.clear();
        mGidToNid.clear();
        mNidToGid.clear();

        try {
            // 获取 GTaskClient 单例实例
            GTaskClient client = GTaskClient.getInstance();
            // 重置更新数组，清空之前累积的更新操作
            client.resetUpdateArray();

            // ========== 步骤1: 登录 Google 账号 ==========
            if (!mCancelled) {
                // 调用 GTaskClient 进行登录验证
                // 登录过程会获取/刷新 Google 账号的认证令牌
                if (!client.login(mActivity)) {
                    // 登录失败，抛出网络异常
                    throw new NetworkFailureException("登录 Google 账号失败");
                }
            }

            // ========== 步骤2: 获取远程 Task 列表 ==========
            // 发布进度：正在初始化列表
            asyncTask.publishProgess(mContext.getString(R.string.sync_progress_init_list));
            // 从 Google 服务器获取所有便签列表和便签内容
            initGTaskList();

            // ========== 步骤3: 执行数据同步 ==========
            // 发布进度：正在同步
            asyncTask.publishProgess(mContext.getString(R.string.sync_progress_syncing));
            // 执行本地和远程数据的对比和同步
            syncContent();
            
        } catch (NetworkFailureException e) {
            // 网络异常：无法连接 Google 服务器
            Log.e(TAG, e.toString());
            return STATE_NETWORK_ERROR;
        } catch (ActionFailureException e) {
            // 操作异常：同步过程中发生错误
            Log.e(TAG, e.toString());
            return STATE_INTERNAL_ERROR;
        } catch (Exception e) {
            // 其他未知异常
            Log.e(TAG, e.toString());
            e.printStackTrace();
            return STATE_INTERNAL_ERROR;
        } finally {
            // ========== 步骤4: 清理资源 ==========
            // 无论同步成功还是失败，都要清理临时数据
            mGTaskListHashMap.clear();
            mGTaskHashMap.clear();
            mMetaHashMap.clear();
            mLocalDeleteIdMap.clear();
            mGidToNid.clear();
            mNidToGid.clear();
            // 重置同步状态，允许下一次同步
            mSyncing = false;
        }

        // 返回最终状态：取消则返回取消状态，否则返回成功
        return mCancelled ? STATE_SYNC_CANCELLED : STATE_SUCCESS;
    }

    /**
     * 初始化 Google Task 列表
     * 
     * 【功能说明】
     * 从 Google Tasks 服务器获取所有便签列表和便签内容，并存储到本地数据结构中
     * 
     * 【处理流程】
     * 1. 从 Google 服务器获取所有 Task List
     * 2. 遍历查找并初始化元数据列表（MIUI_FOLDER_META）
     *    - 如果元数据列表不存在，则创建一个新的
     *    - 加载所有元数据到 mMetaHashMap
     * 3. 遍历查找并初始化便签列表（以 MIUI_FOLDER_PREFIX 开头的）
     *    - 将 TaskList 添加到 mGTaskListHashMap 和 mGTaskHashMap
     *    - 加载列表中的所有 Task 到对应的 TaskList
     *    - 将 Task 添加到 mGTaskHashMap
     * 
     * 【数据结构填充】
     * - mMetaList: 元数据列表，用于存储便签的元信息
     * - mMetaHashMap: GID -> MetaData 的映射
     * - mGTaskListHashMap: GID -> TaskList 的映射
     * - mGTaskHashMap: GID -> Node（Task/TaskList）的映射
     * 
     * 【取消检查】
     * 方法开始时会检查 mCancelled 标志，如果已取消则直接返回
     * 在每个可能耗时的操作（HTTP 请求）前都会检查
     * 
     * @throws NetworkFailureException 网络错误
     * @throws ActionFailureException JSON 解析错误
     */
    private void initGTaskList() throws NetworkFailureException {
        // 检查是否已取消同步
        if (mCancelled)
            return;
            
        GTaskClient client = GTaskClient.getInstance();
        try {
            // 从 Google 服务器获取所有 Task List
            JSONArray jsTaskLists = client.getTaskLists();

            // ========== 第一步：初始化元数据列表 ==========
            mMetaList = null;
            for (int i = 0; i < jsTaskLists.length(); i++) {
                JSONObject object = jsTaskLists.getJSONObject(i);
                String gid = object.getString(GTaskStringUtils.GTASK_JSON_ID);
                String name = object.getString(GTaskStringUtils.GTASK_JSON_NAME);

                // 查找名为 "MIUI_FOLDER_META" 的特殊列表
                // 此列表用于存储所有便签的元数据
                if (name.equals(GTaskStringUtils.MIUI_FOLDER_PREFFIX + GTaskStringUtils.FOLDER_META)) {
                    // 找到元数据列表
                    mMetaList = new TaskList();
                    // 解析远程 JSON 数据，设置 TaskList 的内容
                    mMetaList.setContentByRemoteJSON(object);

                    // 加载元数据列表中的所有任务（这些是各个便签的元数据）
                    JSONArray jsMetas = client.getTaskList(gid);
                    for (int j = 0; j < jsMetas.length(); j++) {
                        object = (JSONObject) jsMetas.getJSONObject(j);
                        MetaData metaData = new MetaData();
                        metaData.setContentByRemoteJSON(object);
                        // 只保存有价值的元数据
                        if (metaData.isWorthSaving()) {
                            mMetaList.addChildTask(metaData);
                            // 建立 GID 到 MetaData 的映射
                            // getRelatedGid() 返回关联的便签 GID
                            if (metaData.getGid() != null) {
                                mMetaHashMap.put(metaData.getRelatedGid(), metaData);
                            }
                        }
                    }
                }
            }

            // 如果元数据列表不存在，则创建一个新的
            // 这通常发生在首次同步时
            if (mMetaList == null) {
                mMetaList = new TaskList();
                mMetaList.setName(GTaskStringUtils.MIUI_FOLDER_PREFFIX
                        + GTaskStringUtils.FOLDER_META);
                // 在 Google 服务器上创建这个列表
                GTaskClient.getInstance().createTaskList(mMetaList);
            }

            // ========== 第二步：初始化便签列表 ==========
            // 遍历所有 Google Task List
            for (int i = 0; i < jsTaskLists.length(); i++) {
                JSONObject object = jsTaskLists.getJSONObject(i);
                String gid = object.getString(GTaskStringUtils.GTASK_JSON_ID);
                String name = object.getString(GTaskStringUtils.GTASK_JSON_NAME);

                // 只处理以 MIUI_FOLDER_PREFIX 开头且不是元数据列表的列表
                // 这些是我们自己创建的便签文件夹
                if (name.startsWith(GTaskStringUtils.MIUI_FOLDER_PREFFIX)
                        && !name.equals(GTaskStringUtils.MIUI_FOLDER_PREFFIX
                                + GTaskStringUtils.FOLDER_META)) {
                    // 创建 TaskList 对象并解析远程数据
                    TaskList tasklist = new TaskList();
                    tasklist.setContentByRemoteJSON(object);
                    
                    // 存储到映射表
                    mGTaskListHashMap.put(gid, tasklist);
                    mGTaskHashMap.put(gid, tasklist);

                    // 加载此列表中的所有便签（Task）
                    JSONArray jsTasks = client.getTaskList(gid);
                    for (int j = 0; j < jsTasks.length(); j++) {
                        object = (JSONObject) jsTasks.getJSONObject(j);
                        gid = object.getString(GTaskStringUtils.GTASK_JSON_ID);
                        Task task = new Task();
                        task.setContentByRemoteJSON(object);
                        // 只保存有价值的任务
                        if (task.isWorthSaving()) {
                            // 设置任务的元信息（从 mMetaHashMap 中查找）
                            task.setMetaInfo(mMetaHashMap.get(gid));
                            // 添加为 TaskList 的子任务
                            tasklist.addChildTask(task);
                            // 存储到映射表
                            mGTaskHashMap.put(gid, task);
                        }
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("初始化 Google Task 列表失败：JSON 对象处理错误");
        }
    }

    /**
     * 同步便签内容 - 核心同步逻辑
     * 
     * 【功能说明】
     * 这是同步的核心方法，负责对比本地和远程数据，决定每个便签的同步动作
     * 
     * 【同步策略 - 三步走算法】
     * 
     * 第一步：处理本地删除
     * - 查询垃圾箱中的便签（TYPE_SYSTEM 且 parent_id = ID_TRASH_FOLDER）
     * - 如果便签在远程存在，则从远程删除
     * - 将便签 ID 加入 mLocalDeleteIdMap，稍后批量删除
     * 
     * 第二步：同步文件夹
     * - 同步根文件夹和通话记录文件夹（系统文件夹）
     * - 同步普通文件夹
     * - 处理远程新增的文件夹
     * 
     * 第三步：同步便签
     * - 查询所有不在垃圾箱的便签（TYPE_NOTE 且不在 ID_TRASH_FOLDER）
     * - 对每个便签，判断同步类型：
     *   * 如果 mGTaskHashMap 中存在该 GID：两边都有，需要更新或删除
     *   * 如果不存在但 GID 为空：本地新增，需要推送到远程
     *   * 如果不存在但 GID 非空：远程已删除，需要从本地删除
     * - 处理完成后，从 mGTaskHashMap 中移除已处理的节点
     * - 处理完成后，mGTaskHashMap 中剩余的节点就是远程新增的，需要添加到本地
     * 
     * 第四步：批量操作
     * - 如果未取消，批量删除本地垃圾箱中的便签
     * - 如果未取消，提交所有远程更新（commitUpdate）
     * - 刷新本地同步 ID
     * 
     * 【GID 追踪映射】
     * - mGidToNid: Google ID -> 本地 ID，用于记录已处理的对应关系
     * - mNidToGid: 本地 ID -> Google ID，用于查找父文件夹
     * 
     * @throws NetworkFailureException 网络错误
     */
    private void syncContent() throws NetworkFailureException {
        int syncType;        // 同步动作类型
        Cursor c = null;    // 数据库查询游标
        String gid;          // Google Task ID
        Node node;           // Google Task 节点

        // 清空本地删除映射表
        mLocalDeleteIdMap.clear();

        // 检查是否已取消
        if (mCancelled) {
            return;
        }

        // ========== 第一步：处理本地删除的便签 ==========
        // 查询垃圾箱中的便签（本地已删除但尚未同步到远程的便签）
        try {
            // 条件：type != TYPE_SYSTEM AND parent_id = ID_TRASH_FOLDER
            // 即：不是系统类型，且父文件夹是垃圾箱
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI, SqlNote.PROJECTION_NOTE,
                    "(type<>? AND parent_id=?)", new String[] {
                            String.valueOf(Notes.TYPE_SYSTEM), String.valueOf(Notes.ID_TRASH_FOLER)
                    }, null);
            if (c != null) {
                while (c.moveToNext()) {
                    // 获取便签的 Google Task ID
                    gid = c.getString(SqlNote.GTASK_ID_COLUMN);
                    // 在远程数据中查找该便签
                    node = mGTaskHashMap.get(gid);
                    if (node != null) {
                        // 远程也存在，需要从远程删除
                        // 从远程映射表中移除
                        mGTaskHashMap.remove(gid);
                        // 执行远程删除操作
                        doContentSync(Node.SYNC_ACTION_DEL_REMOTE, node, c);
                    }

                    // 记录本地删除的便签 ID，稍后批量删除
                    mLocalDeleteIdMap.add(c.getLong(SqlNote.ID_COLUMN));
                }
            } else {
                Log.w(TAG, "查询垃圾箱文件夹失败");
            }
        } finally {
            // 关闭游标
            if (c != null) {
                c.close();
                c = null;
            }
        }

        // ========== 第二步：同步文件夹 ==========
        // 同步根文件夹、通话记录文件夹、自定义文件夹
        syncFolder();

        // ========== 第三步：同步便签 ==========
        // 查询所有不在垃圾箱的便签
        try {
            // 条件：type = TYPE_NOTE AND parent_id != ID_TRASH_FOLDER
            // 按便签类型倒序排列
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI, SqlNote.PROJECTION_NOTE,
                    "(type=? AND parent_id<>?)", new String[] {
                            String.valueOf(Notes.TYPE_NOTE), String.valueOf(Notes.ID_TRASH_FOLER)
                    }, NoteColumns.TYPE + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    // 获取便签的 Google Task ID
                    gid = c.getString(SqlNote.GTASK_ID_COLUMN);
                    // 在远程数据中查找
                    node = mGTaskHashMap.get(gid);
                    if (node != null) {
                        // 远程也存在该便签
                        // 从远程映射表中移除（表示已处理）
                        mGTaskHashMap.remove(gid);
                        // 建立 GID-NID 映射关系
                        mGidToNid.put(gid, c.getLong(SqlNote.ID_COLUMN));
                        mNidToGid.put(c.getLong(SqlNote.ID_COLUMN), gid);
                        // 让节点自己判断需要的同步动作（更新或保持不变）
                        syncType = node.getSyncAction(c);
                    } else {
                        // 远程不存在该便签
                        if (c.getString(SqlNote.GTASK_ID_COLUMN).trim().length() == 0) {
                            // GID 为空，说明是本地新增的便签，需要推送到远程
                            syncType = Node.SYNC_ACTION_ADD_REMOTE;
                        } else {
                            // GID 非空但远程不存在，说明远程已删除该便签
                            // 需要从本地删除
                            syncType = Node.SYNC_ACTION_DEL_LOCAL;
                        }
                    }
                    // 执行同步操作
                    doContentSync(syncType, node, c);
                }
            } else {
                Log.w(TAG, "查询数据库中现有便签失败");
            }

        } finally {
            // 关闭游标
            if (c != null) {
                c.close();
                c = null;
            }
        }

        // ========== 第四步：处理远程新增的便签 ==========
        // 遍历 mGTaskHashMap 中剩余的节点
        // 这些节点在本地数据库中没有对应项，说明是远程新增的
        Iterator<Map.Entry<String, Node>> iter = mGTaskHashMap.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, Node> entry = iter.next();
            node = entry.getValue();
            // 执行本地添加操作
            doContentSync(Node.SYNC_ACTION_ADD_LOCAL, node, null);
        }

        // ========== 第五步：批量删除和提交 ==========
        // mCancelled 可能被另一个线程设置，需要逐个检查
        
        // 清空本地删除表（批量删除）
        if (!mCancelled) {
            if (!DataUtils.batchDeleteNotes(mContentResolver, mLocalDeleteIdMap)) {
                throw new ActionFailureException("批量删除本地已删除便签失败");
            }
        }

        // 刷新本地同步 ID
        if (!mCancelled) {
            // 提交所有远程更新到 Google 服务器
            GTaskClient.getInstance().commitUpdate();
            // 更新本地数据库中的同步 ID（远程最后修改时间）
            refreshLocalSyncId();
        }

    }

    /**
     * 同步文件夹
     * 
     * 【功能说明】
     * 负责同步所有文件夹（TaskList），包括系统文件夹和自定义文件夹
     * 
     * 【处理顺序】
     * 1. 同步根文件夹（Root Folder）- ID 为 ID_ROOT_FOLDER
     * 2. 同步通话记录文件夹（Call Note Folder）- ID 为 ID_CALL_RECORD_FOLDER
     * 3. 同步普通文件夹
     * 4. 处理远程新增的文件夹（添加到本地）
     * 5. 提交文件夹相关的远程更新
     * 
     * 【系统文件夹处理】
     * - 根文件夹：所有便签的默认父文件夹
     * - 通话记录文件夹：存储通话时创建的便签
     * - 系统文件夹同步时，只会更新远程名称（如果名称不一致）
     * 
     * @throws NetworkFailureException 网络错误
     */
    private void syncFolder() throws NetworkFailureException {
        Cursor c = null;
        String gid;
        Node node;
        int syncType;

        // 检查是否已取消
        if (mCancelled) {
            return;
        }

        // ========== 第一步：同步根文件夹 ==========
        // 根文件夹是所有便签的默认父文件夹，对应 Google Tasks 中的默认列表
        try {
            // 直接通过 ID 查询根文件夹
            c = mContentResolver.query(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI,
                    Notes.ID_ROOT_FOLDER), SqlNote.PROJECTION_NOTE, null, null, null);
            if (c != null) {
                c.moveToNext();
                gid = c.getString(SqlNote.GTASK_ID_COLUMN);
                node = mGTaskHashMap.get(gid);
                if (node != null) {
                    // 远程存在根文件夹
                    // 从远程映射表中移除
                    mGTaskHashMap.remove(gid);
                    // 建立映射关系
                    mGidToNid.put(gid, (long) Notes.ID_ROOT_FOLDER);
                    mNidToGid.put((long) Notes.ID_ROOT_FOLDER, gid);
                    // 对于系统文件夹，只在远程名称与本地不一致时更新
                    if (!node.getName().equals(
                            GTaskStringUtils.MIUI_FOLDER_PREFFIX + GTaskStringUtils.FOLDER_DEFAULT))
                        // 执行远程更新
                        doContentSync(Node.SYNC_ACTION_UPDATE_REMOTE, node, c);
                } else {
                    // 远程不存在根文件夹，需要创建
                    doContentSync(Node.SYNC_ACTION_ADD_REMOTE, node, c);
                }
            } else {
                Log.w(TAG, "查询根文件夹失败");
            }
        } finally {
            if (c != null) {
                c.close();
                c = null;
            }
        }

        // ========== 第二步：同步通话记录文件夹 ==========
        // 通话记录文件夹存储通话时创建的便签
        try {
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI, SqlNote.PROJECTION_NOTE, "(_id=?)",
                    new String[] {
                        String.valueOf(Notes.ID_CALL_RECORD_FOLDER)
                    }, null);
            if (c != null) {
                if (c.moveToNext()) {
                    gid = c.getString(SqlNote.GTASK_ID_COLUMN);
                    node = mGTaskHashMap.get(gid);
                    if (node != null) {
                        // 远程存在该文件夹
                        mGTaskHashMap.remove(gid);
                        mGidToNid.put(gid, (long) Notes.ID_CALL_RECORD_FOLDER);
                        mNidToGid.put((long) Notes.ID_CALL_RECORD_FOLDER, gid);
                        // 对于系统文件夹，只在远程名称不一致时更新
                        if (!node.getName().equals(
                                GTaskStringUtils.MIUI_FOLDER_PREFFIX
                                        + GTaskStringUtils.FOLDER_CALL_NOTE))
                            doContentSync(Node.SYNC_ACTION_UPDATE_REMOTE, node, c);
                    } else {
                        // 远程不存在，需要创建
                        doContentSync(Node.SYNC_ACTION_ADD_REMOTE, node, c);
                    }
                }
            } else {
                Log.w(TAG, "查询通话记录文件夹失败");
            }
        } finally {
            if (c != null) {
                c.close();
                c = null;
            }
        }

        // ========== 第三步：同步普通文件夹 ==========
        // 查询所有非系统类型的文件夹（排除根文件夹和通话记录文件夹）
        try {
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI, SqlNote.PROJECTION_NOTE,
                    "(type=? AND parent_id<>?)", new String[] {
                            String.valueOf(Notes.TYPE_FOLDER), String.valueOf(Notes.ID_TRASH_FOLER)
                    }, NoteColumns.TYPE + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    gid = c.getString(SqlNote.GTASK_ID_COLUMN);
                    node = mGTaskHashMap.get(gid);
                    if (node != null) {
                        // 两边都有该文件夹
                        mGTaskHashMap.remove(gid);
                        mGidToNid.put(gid, c.getLong(SqlNote.ID_COLUMN));
                        mNidToGid.put(c.getLong(SqlNote.ID_COLUMN), gid);
                        // 让节点自己判断同步动作
                        syncType = node.getSyncAction(c);
                    } else {
                        // 远程不存在该文件夹
                        if (c.getString(SqlNote.GTASK_ID_COLUMN).trim().length() == 0) {
                            // 本地新增，需要推送到远程
                            syncType = Node.SYNC_ACTION_ADD_REMOTE;
                        } else {
                            // 远程已删除，需要从本地删除
                            syncType = Node.SYNC_ACTION_DEL_LOCAL;
                        }
                    }
                    doContentSync(syncType, node, c);
                }
            } else {
                Log.w(TAG, "查询现有文件夹失败");
            }
        } finally {
            if (c != null) {
                c.close();
                c = null;
            }
        }

        // ========== 第四步：处理远程新增的文件夹 ==========
        // 遍历 mGTaskListHashMap 中但在 mGTaskHashMap 中剩余的文件夹
        // 这些是远程新增的，需要添加到本地
        Iterator<Map.Entry<String, TaskList>> iter = mGTaskListHashMap.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, TaskList> entry = iter.next();
            gid = entry.getKey();
            node = entry.getValue();
            if (mGTaskHashMap.containsKey(gid)) {
                // 该文件夹还在 mGTaskHashMap 中（未被本地处理过）
                // 说明是远程新增的
                mGTaskHashMap.remove(gid);
                // 添加到本地
                doContentSync(Node.SYNC_ACTION_ADD_LOCAL, node, null);
            }
        }

        // ========== 第五步：提交文件夹更新 ==========
        if (!mCancelled)
            // 提交所有文件夹相关的远程更新
            GTaskClient.getInstance().commitUpdate();
    }

    /**
     * 执行内容同步操作
     * 
     * 【功能说明】
     * 根据同步动作类型（syncType）执行相应的同步操作
     * 这是同步操作的路由中心，根据不同类型分发到不同的处理方法
     * 
     * 【同步动作处理】
     * 
     * SYNC_ACTION_ADD_LOCAL:
     *   - 功能：添加本地节点（远程新增）
     *   - 调用：addLocalNode(node)
     *   - 说明：在本地数据库中创建一个新的便签或文件夹
     * 
     * SYNC_ACTION_ADD_REMOTE:
     *   - 功能：添加远程节点（本地新增）
     *   - 调用：addRemoteNode(node, c)
     *   - 说明：将本地便签或文件夹推送到 Google 服务器
     * 
     * SYNC_ACTION_DEL_LOCAL:
     *   - 功能：删除本地节点（远程已删除）
     *   - 说明：将本地便签标记为删除（移入垃圾箱）
     *   - 注意：不会立即删除，而是记录到 mLocalDeleteIdMap
     * 
     * SYNC_ACTION_DEL_REMOTE:
     *   - 功能：删除远程节点（本地已删除）
     *   - 调用：deleteNode(node) 和 deleteNode(meta)
     *   - 说明：从 Google 服务器删除便签和元数据
     * 
     * SYNC_ACTION_UPDATE_LOCAL:
     *   - 功能：更新本地节点（远程有更新）
     *   - 调用：updateLocalNode(node, c)
     *   - 说明：用远程数据更新本地数据库
     * 
     * SYNC_ACTION_UPDATE_REMOTE:
     *   - 功能：更新远程节点（本地有更新）
     *   - 调用：updateRemoteNode(node, c)
     *   - 说明：将本地修改推送到 Google 服务器
     * 
     * SYNC_ACTION_UPDATE_CONFLICT:
     *   - 功能：冲突处理
     *   - 当前策略：以本地为准，调用 updateRemoteNode
     *   - 说明：注释提到"合并两个修改可能是个好主意"，暗示未来可能实现更智能的合并
     * 
     * SYNC_ACTION_NONE:
     *   - 功能：无需同步
     *   - 说明：便签没有变化，不需要任何操作
     * 
     * SYNC_ACTION_ERROR:
     *   - 功能：错误处理
     *   - 说明：抛出 ActionFailureException
     * 
     * 【取消检查】
     * 每个操作前都会检查 mCancelled 标志，如果已取消则直接返回
     * 
     * @param syncType 同步动作类型
     * @param node Google Task 节点（可能为 null，如 ADD_LOCAL 时）
     * @param c 本地便签数据库游标（可能为 null，如 ADD_LOCAL 时）
     * @throws NetworkFailureException 网络错误
     * @throws ActionFailureException 操作失败
     */
    private void doContentSync(int syncType, Node node, Cursor c) throws NetworkFailureException {
        // 检查是否已取消
        if (mCancelled) {
            return;
        }

        MetaData meta;
        // 根据同步动作类型执行相应操作
        switch (syncType) {
            case Node.SYNC_ACTION_ADD_LOCAL:
                // 【添加本地节点】远程新增的便签/文件夹，需要添加到本地
                addLocalNode(node);
                break;
                
            case Node.SYNC_ACTION_ADD_REMOTE:
                // 【添加远程节点】本地新增的便签/文件夹，需要推送到远程
                addRemoteNode(node, c);
                break;
                
            case Node.SYNC_ACTION_DEL_LOCAL:
                // 【删除本地节点】远程已删除，需要从本地删除
                // 先删除关联的元数据
                meta = mMetaHashMap.get(c.getString(SqlNote.GTASK_ID_COLUMN));
                if (meta != null) {
                    GTaskClient.getInstance().deleteNode(meta);
                }
                // 记录本地删除的便签 ID，稍后批量删除
                mLocalDeleteIdMap.add(c.getLong(SqlNote.ID_COLUMN));
                break;
                
            case Node.SYNC_ACTION_DEL_REMOTE:
                // 【删除远程节点】本地已删除，需要从远程删除
                // 先删除关联的元数据
                meta = mMetaHashMap.get(node.getGid());
                if (meta != null) {
                    GTaskClient.getInstance().deleteNode(meta);
                }
                // 删除便签本身
                GTaskClient.getInstance().deleteNode(node);
                break;
                
            case Node.SYNC_ACTION_UPDATE_LOCAL:
                // 【更新本地节点】远程有更新，需要更新本地
                updateLocalNode(node, c);
                break;
                
            case Node.SYNC_ACTION_UPDATE_REMOTE:
                // 【更新远程节点】本地有更新，需要更新远程
                updateRemoteNode(node, c);
                break;
                
            case Node.SYNC_ACTION_UPDATE_CONFLICT:
                // 【冲突处理】两边都有更新，当前策略以本地为准
                // 注释：合并两个修改可能是个好主意，但现在直接使用本地更新
                updateRemoteNode(node, c);
                break;
                
            case Node.SYNC_ACTION_NONE:
                // 【无需同步】便签没有变化
                break;
                
            case Node.SYNC_ACTION_ERROR:
            default:
                // 【错误】未知的同步动作类型
                throw new ActionFailureException("未知的同步动作类型");
        }
    }

    /**
     * 添加本地节点（远程新增）
     * 
     * 【功能说明】
     * 当远程有新增的便签或文件夹时，将它们添加到本地数据库
     * 
     * 【处理流程】
     * 1. 创建 SqlNote 对象
     *    - 如果是 TaskList（文件夹）：
     *      * 如果是根文件夹，映射到 ID_ROOT_FOLDER
     *      * 如果是通话记录文件夹，映射到 ID_CALL_RECORD_FOLDER
     *      * 否则创建新的文件夹
     *    - 如果是 Task（便签）：
     *      * 创建新的 SqlNote
     *      * 检查并处理 ID 冲突（确保 ID 可用）
     *      * 查找父文件夹的本地 ID
     * 2. 设置便签的 Google Task ID
     * 3. 提交到本地数据库
     * 4. 更新 GID-NID 映射关系
     * 5. 更新远程元数据
     * 
     * 【ID 冲突处理】
     * 在添加远程便签到本地时，可能会遇到 ID 冲突：
     * - 远程便签携带的本地 ID 可能已被其他便签使用
     * - 如果 ID 已存在，需要移除旧 ID，让系统生成新 ID
     * 
     * @param node 从 Google 服务器获取的远程节点（Task 或 TaskList）
     * @throws NetworkFailureException 网络错误
     * @throws ActionFailureException 添加失败
     */
    private void addLocalNode(Node node) throws NetworkFailureException {
        // 检查是否已取消
        if (mCancelled) {
            return;
        }

        SqlNote sqlNote;
        
        // ========== 第一步：创建 SqlNote 对象 ==========
        if (node instanceof TaskList) {
            // 【处理文件夹】
            // 检查是否是系统文件夹（根文件夹或通话记录文件夹）
            if (node.getName().equals(
                    GTaskStringUtils.MIUI_FOLDER_PREFFIX + GTaskStringUtils.FOLDER_DEFAULT)) {
                // 根文件夹：使用预定义的 ID
                sqlNote = new SqlNote(mContext, Notes.ID_ROOT_FOLDER);
            } else if (node.getName().equals(
                    GTaskStringUtils.MIUI_FOLDER_PREFFIX + GTaskStringUtils.FOLDER_CALL_NOTE)) {
                // 通话记录文件夹：使用预定义的 ID
                sqlNote = new SqlNote(mContext, Notes.ID_CALL_RECORD_FOLDER);
            } else {
                // 普通文件夹：创建新的 SqlNote
                sqlNote = new SqlNote(mContext);
                // 从远程 JSON 设置文件夹内容
                sqlNote.setContent(node.getLocalJSONFromContent());
                // 设置父文件夹为根文件夹
                sqlNote.setParentId(Notes.ID_ROOT_FOLDER);
            }
        } else {
            // 【处理便签】
            sqlNote = new SqlNote(mContext);
            // 从远程节点获取便签内容
            JSONObject js = node.getLocalJSONFromContent();
            try {
                // ========== 检查并处理 ID 冲突 ==========
                // 远程便签可能携带了旧的本地 ID，需要检查是否可用
                
                // 检查便签本身的 ID
                if (js.has(GTaskStringUtils.META_HEAD_NOTE)) {
                    JSONObject note = js.getJSONObject(GTaskStringUtils.META_HEAD_NOTE);
                    if (note.has(NoteColumns.ID)) {
                        long id = note.getLong(NoteColumns.ID);
                        // 检查 ID 是否已被使用
                        if (DataUtils.existInNoteDatabase(mContentResolver, id)) {
                            // ID 已被使用，需要移除，让系统生成新 ID
                            Log.w(TAG, "便签 ID 冲突，将使用新 ID");
                            note.remove(NoteColumns.ID);
                        }
                    }
                }

                // 检查便签数据（如文本内容）的 ID
                if (js.has(GTaskStringUtils.META_HEAD_DATA)) {
                    JSONArray dataArray = js.getJSONArray(GTaskStringUtils.META_HEAD_DATA);
                    for (int i = 0; i < dataArray.length(); i++) {
                        JSONObject data = dataArray.getJSONObject(i);
                        if (data.has(DataColumns.ID)) {
                            long dataId = data.getLong(DataColumns.ID);
                            // 检查数据 ID 是否已被使用
                            if (DataUtils.existInDataDatabase(mContentResolver, dataId)) {
                                // ID 已被使用，需要移除
                                Log.w(TAG, "便签数据 ID 冲突，将使用新 ID");
                                data.remove(DataColumns.ID);
                            }
                        }
                    }
                }
            } catch (JSONException e) {
                Log.w(TAG, e.toString());
                e.printStackTrace();
            }
            sqlNote.setContent(js);

            // 查找父文件夹的本地 ID
            Long parentId = mGidToNid.get(((Task) node).getParent().getGid());
            if (parentId == null) {
                // 无法找到父文件夹，报错
                Log.e(TAG, "无法找到便签的父文件夹 ID");
                throw new ActionFailureException("无法添加本地节点");
            }
            sqlNote.setParentId(parentId.longValue());
        }

        // ========== 第二步：设置 Google Task ID 并提交 ==========
        // 将远程节点的 GID 设置到本地便签
        sqlNote.setGtaskId(node.getGid());
        // commit(false) 表示这是新增操作，不是更新
        sqlNote.commit(false);

        // ========== 第三步：更新 GID-NID 映射 ==========
        // 记录这个对应关系，方便后续查找
        mGidToNid.put(node.getGid(), sqlNote.getId());
        mNidToGid.put(sqlNote.getId(), node.getGid());

        // ========== 第四步：更新远程元数据 ==========
        // 为新添加的便签创建/更新元数据
        updateRemoteMeta(node.getGid(), sqlNote);
    }

    /**
     * 更新本地节点（远程有更新）
     * 
     * 【功能说明】
     * 当远程便签有更新时，用远程数据更新本地数据库
     * 
     * 【处理流程】
     * 1. 从游标创建 SqlNote 对象
     * 2. 用远程节点的内容更新本地便签
     * 3. 更新父文件夹 ID（可能已移动）
     * 4. 提交更新到本地数据库
     * 5. 更新远程元数据
     * 
     * 【与 addLocalNode 的区别】
     * - addLocalNode：创建新的本地便签
     * - updateLocalNode：更新已存在的本地便签
     * - 两者都会检查 ID 冲突，但 updateLocalNode 的冲突概率较低
     * 
     * @param node 远程节点
     * @param c 本地便签游标
     * @throws NetworkFailureException 网络错误
     * @throws ActionFailureException 更新失败
     */
    private void updateLocalNode(Node node, Cursor c) throws NetworkFailureException {
        // 检查是否已取消
        if (mCancelled) {
            return;
        }

        SqlNote sqlNote;
        
        // ========== 第一步：用远程数据更新便签内容 ==========
        // 从游标创建 SqlNote 对象（代表本地便签）
        sqlNote = new SqlNote(mContext, c);
        // 用远程节点的内容覆盖本地内容
        sqlNote.setContent(node.getLocalJSONFromContent());

        // ========== 第二步：更新父文件夹 ==========
        // 检查便签是否移动到了其他文件夹
        // Task 有父节点（TaskList），文件夹没有
        Long parentId = (node instanceof Task) ? mGidToNid.get(((Task) node).getParent().getGid())
                : new Long(Notes.ID_ROOT_FOLDER);
        if (parentId == null) {
            Log.e(TAG, "无法找到便签的父文件夹 ID");
            throw new ActionFailureException("无法更新本地节点");
        }
        sqlNote.setParentId(parentId.longValue());
        
        // ========== 第三步：提交更新 ==========
        // commit(true) 表示这是更新操作
        sqlNote.commit(true);

        // ========== 第四步：更新远程元数据 ==========
        updateRemoteMeta(node.getGid(), sqlNote);
    }

    /**
     * 添加远程节点（本地新增）
     * 
     * 【功能说明】
     * 当本地有新增的便签或文件夹时，将它们推送到 Google 服务器
     * 
     * 【处理流程 - 便签（Task）】
     * 1. 创建 Task 对象，从本地内容设置
     * 2. 查找父文件夹的 GID
     * 3. 将 Task 添加到父 TaskList
     * 4. 调用 GTaskClient.createTask() 在远程创建
     * 5. 更新本地便签的 GID
     * 6. 更新 GID-NID 映射
     * 7. 创建/更新远程元数据
     * 
     * 【处理流程 - 文件夹（TaskList）】
     * 1. 检查同名文件夹是否已存在
     *    - 如果存在：复用该文件夹
     *    - 如果不存在：创建新的 TaskList
     * 2. 设置文件夹内容
     * 3. 调用 GTaskClient.createTaskList() 在远程创建
     * 4. 更新 GID-NID 映射
     * 
     * 【文件夹去重逻辑】
     * 如果远程已有名为 "MIUI_Notes_xxx" 的文件夹，就复用它，而不是创建新的
     * 这是为了避免重复创建同名的文件夹
     * 
     * @param node 本地节点
     * @param c 本地便签游标
     * @throws NetworkFailureException 网络错误
     * @throws ActionFailureException 添加失败
     */
    private void addRemoteNode(Node node, Cursor c) throws NetworkFailureException {
        // 检查是否已取消
        if (mCancelled) {
            return;
        }

        SqlNote sqlNote = new SqlNote(mContext, c);
        Node n;

        // ========== 判断是便签还是文件夹 ==========
        if (sqlNote.isNoteType()) {
            // 【处理便签】
            
            // 创建 Task 对象
            Task task = new Task();
            // 从本地 SQLite 数据库的内容设置 Task
            task.setContentByLocalJSON(sqlNote.getContent());

            // 查找父文件夹的 GID
            String parentGid = mNidToGid.get(sqlNote.getParentId());
            if (parentGid == null) {
                Log.e(TAG, "无法找到便签的父任务列表");
                throw new ActionFailureException("无法添加远程便签");
            }
            
            // 将 Task 添加到父 TaskList
            mGTaskListHashMap.get(parentGid).addChildTask(task);

            // 调用 GTaskClient 在 Google 服务器上创建这个便签
            GTaskClient.getInstance().createTask(task);
            n = (Node) task;

            // 创建/更新远程元数据
            updateRemoteMeta(task.getGid(), sqlNote);
        } else {
            // 【处理文件夹】
            TaskList tasklist = null;

            // 构建文件夹名称
            // 规则：MIUI_FOLDER_PREFIX + 文件夹标识
            // - 根文件夹：MIUI_FOLDER_PREFIX + "default"
            // - 通话记录文件夹：MIUI_FOLDER_PREFIX + "call_note"
            // - 普通文件夹：MIUI_FOLDER_PREFIX + 便签摘要
            String folderName = GTaskStringUtils.MIUI_FOLDER_PREFFIX;
            if (sqlNote.getId() == Notes.ID_ROOT_FOLDER)
                folderName += GTaskStringUtils.FOLDER_DEFAULT;
            else if (sqlNote.getId() == Notes.ID_CALL_RECORD_FOLDER)
                folderName += GTaskStringUtils.FOLDER_CALL_NOTE;
            else
                folderName += sqlNote.getSnippet();

            // 检查同名文件夹是否已存在（避免重复创建）
            Iterator<Map.Entry<String, TaskList>> iter = mGTaskListHashMap.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String, TaskList> entry = iter.next();
                String gid = entry.getKey();
                TaskList list = entry.getValue();

                if (list.getName().equals(folderName)) {
                    // 找到同名文件夹，复用它
                    tasklist = list;
                    // 从 mGTaskHashMap 中移除，避免后续重复处理
                    if (mGTaskHashMap.containsKey(gid)) {
                        mGTaskHashMap.remove(gid);
                    }
                    break;
                }
            }

            // 如果没有找到同名文件夹，创建新的
            if (tasklist == null) {
                tasklist = new TaskList();
                // 从本地内容设置文件夹
                tasklist.setContentByLocalJSON(sqlNote.getContent());
                // 在 Google 服务器上创建
                GTaskClient.getInstance().createTaskList(tasklist);
                // 添加到映射表
                mGTaskListHashMap.put(tasklist.getGid(), tasklist);
            }
            n = (Node) tasklist;
        }

        // ========== 更新本地便签的 GID ==========
        // 同步完成后，本地便签就知道了自己的 Google Task ID
        sqlNote.setGtaskId(n.getGid());
        // commit(false) 表示这是新增操作
        sqlNote.commit(false);
        // 重置本地修改标志
        sqlNote.resetLocalModified();
        // 再次提交，确保 GID 和修改标志都被保存
        sqlNote.commit(true);

        // ========== 更新 GID-NID 映射 ==========
        mGidToNid.put(n.getGid(), sqlNote.getId());
        mNidToGid.put(sqlNote.getId(), n.getGid());
    }

    /**
     * 更新远程节点（本地有更新）
     * 
     * 【功能说明】
     * 当本地便签有更新时，将修改推送到 Google 服务器
     * 
     * 【处理流程】
     * 1. 从游标创建 SqlNote 对象
     * 2. 用本地内容更新远程节点
     * 3. 调用 GTaskClient.addUpdateNode() 添加更新操作
     * 4. 更新远程元数据
     * 5. 如果便签移动了位置，调用 GTaskClient.moveTask() 移动任务
     * 6. 重置本地修改标志
     * 
     * 【任务移动处理】
     * 如果便签的父文件夹发生了变化：
     * 1. 从原父文件夹中移除该任务
     * 2. 添加到新父文件夹
     * 3. 调用 moveTask() 执行远程移动操作
     * 
     * @param node 远程节点
     * @param c 本地便签游标
     * @throws NetworkFailureException 网络错误
     * @throws ActionFailureException 更新失败
     */
    private void updateRemoteNode(Node node, Cursor c) throws NetworkFailureException {
        // 检查是否已取消
        if (mCancelled) {
            return;
        }

        SqlNote sqlNote = new SqlNote(mContext, c);

        // ========== 第一步：更新远程节点内容 ==========
        // 用本地 SQLite 数据库的内容更新远程节点
        node.setContentByLocalJSON(sqlNote.getContent());
        // 添加到更新队列
        GTaskClient.getInstance().addUpdateNode(node);

        // ========== 第二步：更新远程元数据 ==========
        updateRemoteMeta(node.getGid(), sqlNote);

        // ========== 第三步：处理任务移动 ==========
        // 如果是便签，检查是否移动到了其他文件夹
        if (sqlNote.isNoteType()) {
            Task task = (Task) node;
            TaskList preParentList = task.getParent();  // 原父文件夹

            // 查找便签当前所属的文件夹
            String curParentGid = mNidToGid.get(sqlNote.getParentId());
            if (curParentGid == null) {
                Log.e(TAG, "无法找到便签的父任务列表");
                throw new ActionFailureException("无法更新远程便签");
            }
            TaskList curParentList = mGTaskListHashMap.get(curParentGid);  // 当前父文件夹

            // 如果父文件夹发生变化，需要移动任务
            if (preParentList != curParentList) {
                // 从原文件夹移除
                preParentList.removeChildTask(task);
                // 添加到新文件夹
                curParentList.addChildTask(task);
                // 调用 GTaskClient 执行远程移动
                GTaskClient.getInstance().moveTask(task, preParentList, curParentList);
            }
        }

        // ========== 第四步：重置本地修改标志 ==========
        // 同步完成后，重置本地修改标志，表示已同步
        sqlNote.resetLocalModified();
        sqlNote.commit(true);
    }

    /**
     * 更新远程元数据
     * 
     * 【功能说明】
     * 为便签创建或更新元数据（MetaData）
     * 元数据存储在 Google Tasks 上的一个特殊列表中（MIUI_FOLDER_META）
     * 
     * 【元数据作用】
     * 元数据用于存储便签的额外信息，如：
     * - 便签的创建时间
     * - 便签的最后同步时间
     * - 其他便于追踪便签状态的信息
     * 
     * 【处理逻辑】
     * 1. 如果元数据已存在，更新它
     * 2. 如果元数据不存在，创建一个新的
     * 
     * 【与便签的关系】
     * - 每个便签（Task）对应一个 MetaData
     * - MetaData 存储在独立的 TaskList 中
     * - 通过 mMetaHashMap 可以快速查找便签对应的元数据
     * 
     * @param gid 便签的 Google Task ID
     * @param sqlNote 本地便签对象
     * @throws NetworkFailureException 网络错误
     */
    private void updateRemoteMeta(String gid, SqlNote sqlNote) throws NetworkFailureException {
        // 只处理便签，不处理文件夹
        if (sqlNote != null && sqlNote.isNoteType()) {
            // 在元数据映射表中查找
            MetaData metaData = mMetaHashMap.get(gid);
            if (metaData != null) {
                // 元数据已存在，更新它
                metaData.setMeta(gid, sqlNote.getContent());
                GTaskClient.getInstance().addUpdateNode(metaData);
            } else {
                // 元数据不存在，创建新的
                metaData = new MetaData();
                metaData.setMeta(gid, sqlNote.getContent());
                // 添加到元数据列表
                mMetaList.addChildTask(metaData);
                // 存储到映射表
                mMetaHashMap.put(gid, metaData);
                // 在 Google 服务器上创建
                GTaskClient.getInstance().createTask(metaData);
            }
        }
    }

    /**
     * 刷新本地同步 ID
     * 
     * 【功能说明】
     * 同步完成后，刷新本地数据库中便签的同步 ID
     * 同步 ID 通常是便签在 Google 服务器上的最后修改时间
     * 
     * 【处理流程】
     * 1. 清空并重新加载远程数据（确保有最新的数据）
     * 2. 查询所有本地便签
     * 3. 对于每个便签，在远程数据中找到对应的节点
     * 4. 更新本地便签的同步 ID（NoteColumns.SYNC_ID）为远程的最后修改时间
     * 
     * 【为什么需要刷新】
     * - 本地便签需要记录最后一次与远程同步的时间点
     * - 下次同步时，通过比较修改时间和同步时间，可以判断便签是否有变化
     * - 同步 ID 通常是远程便签的 lastModified 字段
     * 
     * 【错误处理】
     * 如果某个本地便签在同步后仍然没有 GID，说明同步出了问题
     * 会抛出 ActionFailureException
     * 
     * @throws NetworkFailureException 网络错误
     * @throws ActionFailureException 刷新失败
     */
    private void refreshLocalSyncId() throws NetworkFailureException {
        // 检查是否已取消
        if (mCancelled) {
            return;
        }

        // ========== 第一步：重新加载远程数据 ==========
        // 清空现有数据
        mGTaskHashMap.clear();
        mGTaskListHashMap.clear();
        mMetaHashMap.clear();
        // 重新从 Google 服务器获取
        initGTaskList();

        Cursor c = null;
        try {
            // ========== 第二步：更新本地便签的同步 ID ==========
            // 查询所有本地便签（排除系统便签和垃圾箱中的便签）
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI, SqlNote.PROJECTION_NOTE,
                    "(type<>? AND parent_id<>?)", new String[] {
                            String.valueOf(Notes.TYPE_SYSTEM), String.valueOf(Notes.ID_TRASH_FOLER)
                    }, NoteColumns.TYPE + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    // 获取本地便签的 GID
                    String gid = c.getString(SqlNote.GTASK_ID_COLUMN);
                    // 在远程数据中查找
                    Node node = mGTaskHashMap.get(gid);
                    if (node != null) {
                        // 找到了对应的远程节点
                        // 从远程映射表中移除（表示已处理）
                        mGTaskHashMap.remove(gid);
                        
                        // 创建更新值
                        ContentValues values = new ContentValues();
                        // 设置同步 ID 为远程便签的最后修改时间
                        values.put(NoteColumns.SYNC_ID, node.getLastModified());
                        
                        // 更新本地数据库
                        mContentResolver.update(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI,
                                c.getLong(SqlNote.ID_COLUMN)), values, null, null);
                    } else {
                        // 没有找到对应的远程节点，这不应该发生
                        Log.e(TAG, "某些本地项在同步后仍然没有 GID");
                        throw new ActionFailureException(
                                "同步后某些本地项缺少 GID");
                    }
                }
            } else {
                Log.w(TAG, "查询本地便签以刷新同步 ID 失败");
            }
        } finally {
            if (c != null) {
                c.close();
                c = null;
            }
        }
    }

    /**
     * 获取同步账号名称
     * 
     * 【功能说明】
     * 返回当前用于同步的 Google 账号名称
     * 
     * @return Google 账号名称
     */
    public String getSyncAccount() {
        return GTaskClient.getInstance().getSyncAccount().name;
    }

    /**
     * 取消同步
     * 
     * 【功能说明】
     * 请求取消正在进行的同步操作
     * 
     * 【取消机制】
     * - 将 mCancelled 标志设置为 true
     * - 正在执行的方法会检查此标志，如果为 true 则提前返回
     * - 不保证立即停止，会在当前操作完成后停止
     * 
     * 【使用场景】
     * - 用户主动取消同步
     * - Activity 销毁时取消后台同步
     * 
     * 【注意】
     * - 这是一个异步操作，设置标志后不会立即停止
     * - 已发送到 Google 服务器的操作无法撤回
     * - 已完成的数据库操作无法撤销
     */
    public void cancelSync() {
        mCancelled = true;
    }
}
