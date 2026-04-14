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

package net.micode.notes.gtask.data;

import android.database.Cursor;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.exception.ActionFailureException;
import net.micode.notes.tool.GTaskStringUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;


/**
 * TaskList - Google TaskList 实体类
 * 
 * 【设计概述】
 * TaskList 是 Google Tasks API 中的"任务列表"实体在小米便签应用中的对应实现。
 * 在 Google Tasks 中，TaskList 就像是一个文件夹，用于组织和收纳多个相关的 Task（任务）。
 * 相应地，在小米便签的数据模型中，一个 TaskList 对应一个 Folder（文件夹）。
 * 
 * 【与 Folder 的映射关系】
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │                         数据模型映射关系                                      │
 * ├─────────────────────────────────────────────────────────────────────────────┤
 * │  小米便签本地数据                    ↔            Google Tasks 服务端        │
 * ├─────────────────────────────────────────────────────────────────────────────┤
 * │  Folder (文件夹)                     ↔            TaskList (任务列表)        │
 * │  - 文件夹名称 (name)                 ↔            TaskList 名称             │
 * │  - 文件夹类型 (TYPE_FOLDER/SYSTEM)  ↔            (通过命名约定区分)         │
 * │  - 唯一标识符 (id/gid)               ↔            TaskList ID (gid)          │
 * ├─────────────────────────────────────────────────────────────────────────────┤
 * │  Note (笔记/任务)                    ↔            Task (任务)                 │
 * │  - 笔记内容                          ↔            Task 内容                  │
 * │  - 创建/修改时间                    ↔            Task 时间戳                │
 * └─────────────────────────────────────────────────────────────────────────────┘
 * 
 * 【命名约定】
 * 为了区分不同类型的文件夹，系统使用特定前缀 "[MIUI_Notes]" 标记所有同步的文件夹：
 * - 用户创建的文件夹："[MIUI_Notes]" + 用户指定的名称
 * - 根文件夹（默认）："[MIUI_Notes]Default"
 * - 通话记录文件夹："[MIUI_Notes]Call_Note"
 * 
 * 【父子关系设计】
 * TaskList 与 Task 之间是一对多（1:N）的组合关系：
 * - 一个 TaskList 可以包含零个或多个 Task
 * - 每个 Task 必属一个且仅属一个 TaskList（或无所属，在移动过程中）
 * - TaskList 负责管理其子 Task 的增删改排序操作
 * - 通过 priorSibling（前置兄弟节点）实现 Task 间的有序排列
 * 
 * 【JSON 序列化说明】
 * TaskList 与 Google Tasks 服务端通过 JSON 进行数据交换：
 * - 创建操作：生成包含 action_type=create 的 JSON 对象
 * - 更新操作：生成包含 action_type=update 的 JSON 对象
 * - 本地存储：将 TaskList 映射为本地的 folder JSON 结构
 * 
 * 【同步机制】
 * TaskList 实现了完整的数据同步逻辑：
 * - 比较本地修改时间与远程修改时间
 * - 检测并处理同步冲突
 * - 根据对比结果决定同步方向（本地→远程 或 远程→本地）
 * 
 * @author MiCode Team
 * @see Node 父类，提供 gid、name、lastModified 等基础属性
 * @see Task 子任务类，代表 TaskList 中的单个任务
 * @see GTaskStringUtils Google Tasks JSON 字段常量定义
 */
public class TaskList extends Node {
    /** 日志标签，用于追踪本类相关的调试信息 */
    private static final String TAG = TaskList.class.getSimpleName();

    /**
     * 排序索引 (mIndex)
     * 
     * 用于确定 TaskList 在列表中的显示顺序。
     * 在 Google Tasks API 中，这个值影响任务列表的排序位置。
     * 初始值设为 1，表示新创建的 TaskList 默认排在较前的位置。
     */
    private int mIndex;

    /**
     * 子任务列表 (mChildren)
     * 
     * 【设计说明】
     * 这是一个 ArrayList<Task>，用于存储属于当前 TaskList 的所有子 Task。
     * TaskList 与 Task 之间是组合关系（Composition），TaskList 负责管理其生命周期。
     * 
     * 【排序机制】
     * 列表中的顺序即为 Task 的显示顺序。每个 Task 通过 setPriorSibling() 方法
     * 指向前一个兄弟节点，形成有序的双向链表，便于 Google Tasks 服务端理解排序关系。
     * 
     * 【线程安全】
     * 注意：ArrayList 不是线程安全的。如果在多线程环境下使用，需要外部同步。
     * 
     * @see #addChildTask(Task) 添加子任务
     * @see #removeChildTask(Task) 移除子任务
     * @see #moveChildTask(Task, int) 移动子任务
     */
    private ArrayList<Task> mChildren;

    /**
     * 构造函数 - 创建新的 TaskList 实例
     * 
     * 【功能说明】
     * 初始化一个新的 TaskList 对象，设置默认的初始状态。
     * 
     * 【初始化内容】
     * 1. 调用父类 Node 的构造函数，初始化基础属性：
     *    - mGid = null (尚未分配 Google Task ID)
     *    - mName = "" (空名称，待后续设置)
     *    - mLastModified = 0 (未修改)
     *    - mDeleted = false (未删除标记)
     * 2. 创建空的子任务列表 mChildren = new ArrayList<Task>()
     * 3. 设置默认排序索引 mIndex = 1
     * 
     * 【使用场景】
     * 当需要在本地创建一个新的文件夹（TaskList）并准备同步到 Google Tasks 时使用。
     * 新创建的 TaskList 需要后续调用 setter 方法设置必要的属性（如名称）。
     */
    public TaskList() {
        super();
        mChildren = new ArrayList<Task>();
        mIndex = 1;
    }

    /**
     * 生成创建 TaskList 的 JSON 动作对象
     * 
     * 【功能说明】
     * 构建一个用于向 Google Tasks 服务端发送的"创建 TaskList"请求的 JSON 对象。
     * 这个 JSON 对象包含了创建新任务列表所需的全部信息。
     * 
     * 【返回的 JSON 格式结构】
     * ```json
     * {
     *   "action_type": "create",           // 动作类型：创建
     *   "action_id": <actionId>,            // 客户端生成的动作ID，用于追踪请求
     *   "index": <mIndex>,                  // 排序索引，影响列表显示顺序
     *   "entity_delta": {
     *     "name": "<getName()>",           // TaskList 名称（带 [MIUI_Notes] 前缀）
     *     "creator_id": "null",            // 创建者ID，设为 null 表示使用默认
     *     "entity_type": "GROUP"           // 实体类型：GROUP 表示这是一个任务列表
     *   }
     * }
     * ```
     * 
     * 【参数说明】
     * @param actionId 客户端生成的唯一动作ID
     *        - 用于标识本次操作，便于追踪和匹配响应
     *        - 通常由调用方按递增顺序生成
     * 
     * 【异常处理】
     * 如果 JSON 序列化过程中发生错误，会：
     * 1. 记录错误日志到 Log.e()
     * 2. 打印异常堆栈信息
     * 3. 抛出 ActionFailureException 异常终止操作
     * 
     * 【与 getUpdateAction() 的区别】
     * - getCreateAction(): 用于首次创建 TaskList，JSON 中包含 entity_type=GROUP
     * - getUpdateAction(): 用于更新已有 TaskList，JSON 中包含 id 和 deleted 字段
     * 
     * @return JSONObject 符合 Google Tasks API 规范的创建动作 JSON 对象
     * @throws ActionFailureException 如果 JSON 构建失败
     * @see #getUpdateAction(int) 生成更新动作的 JSON
     */
    public JSONObject getCreateAction(int actionId) {
        JSONObject js = new JSONObject();

        try {
            // action_type: 设置动作类型为"创建"
            // Google Tasks API 根据此字段决定执行的操作类型
            js.put(GTaskStringUtils.GTASK_JSON_ACTION_TYPE,
                    GTaskStringUtils.GTASK_JSON_ACTION_TYPE_CREATE);

            // action_id: 客户端生成的动作ID
            // 用于在批量操作中追踪每个单独的动作，与响应中的 action_id 对应
            js.put(GTaskStringUtils.GTASK_JSON_ACTION_ID, actionId);

            // index: 排序索引
            // 控制此 TaskList 在 Google Tasks 界面中的显示顺序
            js.put(GTaskStringUtils.GTASK_JSON_INDEX, mIndex);

            // entity_delta: 实体增量，包含创建实体所需的属性
            // 这是一个嵌套的 JSON 对象，定义了要创建的 TaskList 的具体属性
            JSONObject entity = new JSONObject();
            
            // name: TaskList 的显示名称
            // 格式为 "[MIUI_Notes]" + 用户文件夹名称，用于标识来源
            entity.put(GTaskStringUtils.GTASK_JSON_NAME, getName());
            
            // creator_id: 创建者ID
            // 设为 "null" 字符串表示使用当前认证用户的默认设置
            entity.put(GTaskStringUtils.GTASK_JSON_CREATOR_ID, "null");
            
            // entity_type: 实体类型
            // "GROUP" 表示这是一个任务列表（TaskList）
            // 另一个可能的值是 "TASK"，表示单个任务
            entity.put(GTaskStringUtils.GTASK_JSON_ENTITY_TYPE,
                    GTaskStringUtils.GTASK_JSON_TYPE_GROUP);
            
            // 将 entity_delta 添加到主 JSON 对象中
            js.put(GTaskStringUtils.GTASK_JSON_ENTITY_DELTA, entity);

        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("fail to generate tasklist-create jsonobject");
        }

        return js;
    }

    /**
     * 生成更新 TaskList 的 JSON 动作对象
     * 
     * 【功能说明】
     * 构建一个用于向 Google Tasks 服务端发送的"更新 TaskList"请求的 JSON 对象。
     * 与 getCreateAction() 不同，此方法用于修改已存在的 TaskList。
     * 
     * 【返回的 JSON 格式结构】
     * ```json
     * {
     *   "action_type": "update",           // 动作类型：更新
     *   "action_id": <actionId>,           // 客户端生成的动作ID
     *   "id": "<getGid()>",                // TaskList 的 Google Task ID（必填）
     *   "entity_delta": {
     *     "name": "<getName()>",          // 更新后的名称
     *     "deleted": <getDeleted()>       // 删除标记
     *   }
     * }
     * ```
     * 
     * 【与 getCreateAction() 的关键区别】
     * 1. action_type = "update" 而非 "create"
     * 2. 必须包含 id 字段指定要更新的 TaskList
     * 3. entity_delta 只包含需要更新的字段（增量更新）
     * 4. 不再包含 creator_id 和 entity_type（服务端已知）
     * 
     * 【参数说明】
     * @param actionId 客户端生成的唯一动作ID，用于追踪请求
     * 
     * 【异常处理】
     * 与 getCreateAction() 相同，JSON 构建失败时抛出 ActionFailureException。
     * 
     * @return JSONObject 符合 Google Tasks API 规范的更新动作 JSON 对象
     * @throws ActionFailureException 如果 JSON 构建失败
     * @see #getCreateAction(int) 生成创建动作的 JSON
     */
    public JSONObject getUpdateAction(int actionId) {
        JSONObject js = new JSONObject();

        try {
            // action_type: 设置动作类型为"更新"
            js.put(GTaskStringUtils.GTASK_JSON_ACTION_TYPE,
                    GTaskStringUtils.GTASK_JSON_ACTION_TYPE_UPDATE);

            // action_id: 客户端生成的动作ID
            js.put(GTaskStringUtils.GTASK_JSON_ACTION_ID, actionId);

            // id: TaskList 的 Google Task ID（重要！）
            // 这是服务端分配的唯一标识符，用于指定要更新的具体 TaskList
            js.put(GTaskStringUtils.GTASK_JSON_ID, getGid());

            // entity_delta: 实体增量，包含需要更新的属性
            JSONObject entity = new JSONObject();
            
            // name: 更新后的名称
            // 如果名称未改变，也会包含在 delta 中（服务端会忽略无变化的值）
            entity.put(GTaskStringUtils.GTASK_JSON_NAME, getName());
            
            // deleted: 删除标记
            // 当 getDeleted() 返回 true 时，表示将此 TaskList 标记为已删除
            entity.put(GTaskStringUtils.GTASK_JSON_DELETED, getDeleted());
            
            js.put(GTaskStringUtils.GTASK_JSON_ENTITY_DELTA, entity);

        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("fail to generate tasklist-update jsonobject");
        }

        return js;
    }

    /**
     * 根据服务端返回的 JSON 数据设置 TaskList 内容
     * 
     * 【功能说明】
     * 解析从 Google Tasks 服务端获取的 JSON 响应，将其中的数据填充到当前 TaskList 对象中。
     * 这是从服务端同步数据到本地的关键方法。
     * 
     * 【解析的 JSON 字段】
     * 从传入的 JSONObject 中提取并设置以下属性：
     * 
     * | JSON 字段              | 设置方法         | 说明                       |
     * |------------------------|------------------|----------------------------|
     * | id                     | setGid()         | Google Task List ID        |
     * | last_modified         | setLastModified()| 最后修改时间戳（毫秒）      |
     * | name                   | setName()        | TaskList 显示名称          |
     * 
     * 【容错处理】
     * 1. 检查 JSONObject 是否为 null - 如果为 null 则直接返回，不抛异常
     * 2. 使用 js.has() 检查字段是否存在 - 避免因字段缺失导致的异常
     * 3. 所有 JSON 解析异常被捕获并转换为 ActionFailureException
     * 
     * 【数据流向】
     * Google Tasks 服务端  →  HTTP 响应 JSON  →  本方法解析  →  TaskList 属性设置
     * 
     * 【注意事项】
     * - 此方法不会清空之前的内容，只是更新指定的字段
     * - 未在 JSON 中出现的字段将保持原有值不变
     * 
     * @param js 从服务端获取的 TaskList JSON 对象
     * @throws ActionFailureException 如果 JSON 解析失败
     * @see #setContentByLocalJSON(JSONObject) 根据本地 JSON 设置内容
     */
    public void setContentByRemoteJSON(JSONObject js) {
        if (js != null) {
            try {
                // id: Google Task List 的唯一标识符
                // 这是服务端分配的 ID，用于标识此 TaskList
                // 格式类似于 "MTk3MTg2MzA0NDg0NzE2OTQxMTk6MDQ" 这样的字符串
                if (js.has(GTaskStringUtils.GTASK_JSON_ID)) {
                    setGid(js.getString(GTaskStringUtils.GTASK_JSON_ID));
                }

                // last_modified: 最后修改时间戳
                // 这是一个 Unix 时间戳（毫秒），表示服务端数据的最后修改时间
                // 用于同步时的冲突检测和增量更新
                if (js.has(GTaskStringUtils.GTASK_JSON_LAST_MODIFIED)) {
                    setLastModified(js.getLong(GTaskStringUtils.GTASK_JSON_LAST_MODIFIED));
                }

                // name: TaskList 的显示名称
                // 格式为 "[MIUI_Notes]" + 原始文件夹名称
                if (js.has(GTaskStringUtils.GTASK_JSON_NAME)) {
                    setName(js.getString(GTaskStringUtils.GTASK_JSON_NAME));
                }

            } catch (JSONException e) {
                Log.e(TAG, e.toString());
                e.printStackTrace();
                throw new ActionFailureException("fail to get tasklist content from jsonobject");
            }
        }
    }

    /**
     * 根据本地 JSON 数据设置 TaskList 内容
     * 
     * 【功能说明】
     * 从本地存储的 JSON 数据中解析并设置 TaskList 的属性。
     * 这个方法是本地数据到 TaskList 对象的数据转换入口。
     * 
     * 【JSON 结构要求】
     * 输入的 JSON 必须包含 META_HEAD_NOTE ("meta_note") 字段：
     * ```json
     * {
     *   "meta_note": {
     *     "id": <本地文件夹ID>,
     *     "type": <文件夹类型>,
     *     "snippet": "<文件夹名称>"
     *   }
     * }
     * ```
     * 
     * 【文件夹类型映射逻辑】
     * 
     * ┌─────────────────────────────────────────────────────────────────────────────┐
     * │  TYPE_FOLDER (用户文件夹)                                                     │
     * ├─────────────────────────────────────────────────────────────────────────────┤
     * │  - 从 snippet 字段获取用户定义的文件夹名称                                      │
     * │  - 名称前加上 "[MIUI_Notes]" 前缀                                             │
     * │  - 结果示例："[MIUI_Notes]工作"                                               │
     * └─────────────────────────────────────────────────────────────────────────────┘
     * 
     * ┌─────────────────────────────────────────────────────────────────────────────┐
     * │  TYPE_SYSTEM (系统文件夹)                                                    │
     * ├─────────────────────────────────────────────────────────────────────────────┤
     * │  ID_ROOT_FOLDER (0)          → "[MIUI_Notes]Default"                        │
     * │  ID_CALL_RECORD_FOLDER (-2)  → "[MIUI_Notes]Call_Note"                      │
     * │  其他系统ID                   → 记录错误日志                                   │
     * └─────────────────────────────────────────────────────────────────────────────┘
     * 
     * 【参数说明】
     * @param js 本地存储的文件夹 JSON 对象
     *        如果为 null 或不包含 META_HEAD_NOTE 字段，会记录警告日志
     * 
     * 【注意事项】
     * - 此方法不会设置 gid (Google Task ID)，因为 gid 是从服务端获取的
     * - 对于新建的本地文件夹，需要后续调用 getCreateAction() 来同步到服务端
     * 
     * @see #getLocalJSONFromContent() 反向方法：从 TaskList 生成本地 JSON
     */
    public void setContentByLocalJSON(JSONObject js) {
        // 参数校验：如果 JS 对象为空或不包含必要的字段，记录警告并直接返回
        if (js == null || !js.has(GTaskStringUtils.META_HEAD_NOTE)) {
            Log.w(TAG, "setContentByLocalJSON: nothing is avaiable");
        }

        try {
            // 获取 meta_note 节点，该节点包含文件夹的元数据信息
            JSONObject folder = js.getJSONObject(GTaskStringUtils.META_HEAD_NOTE);

            // 判断文件夹类型并设置对应的名称
            if (folder.getInt(NoteColumns.TYPE) == Notes.TYPE_FOLDER) {
                // 【用户创建的文件夹】
                // 从 snippet 字段获取用户输入的文件夹名称
                String name = folder.getString(NoteColumns.SNIPPET);
                // 添加 MIUI 前缀，表示这是从小米便签同步过来的文件夹
                setName(GTaskStringUtils.MIUI_FOLDER_PREFFIX + name);
                
            } else if (folder.getInt(NoteColumns.TYPE) == Notes.TYPE_SYSTEM) {
                // 【系统预置文件夹】
                // 根据系统文件夹的 ID 判断具体类型
                if (folder.getLong(NoteColumns.ID) == Notes.ID_ROOT_FOLDER) {
                    // 根文件夹（默认文件夹）
                    setName(GTaskStringUtils.MIUI_FOLDER_PREFFIX + GTaskStringUtils.FOLDER_DEFAULT);
                } else if (folder.getLong(NoteColumns.ID) == Notes.ID_CALL_RECORD_FOLDER) {
                    // 通话记录文件夹
                    setName(GTaskStringUtils.MIUI_FOLDER_PREFFIX
                            + GTaskStringUtils.FOLDER_CALL_NOTE);
                } else {
                    // 未知的系统文件夹 ID，记录错误
                    Log.e(TAG, "invalid system folder");
                }
            } else {
                // 未知类型，记录错误
                Log.e(TAG, "error type");
            }
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
        }
    }

    /**
     * 将 TaskList 内容转换为本地的 JSON 格式
     * 
     * 【功能说明】
     * 这是 setContentByLocalJSON() 的反向操作。
     * 将当前 TaskList 对象的属性转换为本地的 JSON 结构，用于持久化存储。
     * 
     * 【输出 JSON 结构】
     * ```json
     * {
     *   "meta_note": {
     *     "snippet": "<去掉前缀后的文件夹名称>",
     *     "type": <文件夹类型: TYPE_FOLDER 或 TYPE_SYSTEM>
     *   }
     * }
     * ```
     * 
     * 【名称处理逻辑】
     * 如果 TaskList 名称以 "[MIUI_Notes]" 前缀开头，会自动去掉前缀，
     * 只保留用户可见的文件夹名称部分。
     * 
     * 【类型判断逻辑】
     * 根据去除前缀后的名称判断文件夹类型：
     * - "Default"       → TYPE_SYSTEM (系统根文件夹)
     * - "Call_Note"     → TYPE_SYSTEM (通话记录文件夹)
     * - 其他名称        → TYPE_FOLDER (用户文件夹)
     * 
     * 【返回 JSON 示例】
     * 对于用户文件夹 "工作"：
     * ```json
     * {
     *   "meta_note": {
     *     "snippet": "工作",
     *     "type": 1  // TYPE_FOLDER
     *   }
     * }
     * ```
     * 
     * 对于系统文件夹：
     * ```json
     * {
     *   "meta_note": {
     *     "snippet": "Default",
     *     "type": 2  // TYPE_SYSTEM
     *   }
     * }
     * ```
     * 
     * 【注意】
     * 本方法不保存 Google Task ID (gid)，因为 gid 是服务端生成的标识符，
     * 应该与本地 ID 独立存储。
     * 
     * @return JSONObject 转换后的本地 JSON 对象，失败时返回 null
     * @see #setContentByLocalJSON(JSONObject) 反向方法
     */
    public JSONObject getLocalJSONFromContent() {
        try {
            JSONObject js = new JSONObject();
            JSONObject folder = new JSONObject();

            // 【名称处理】
            // 获取 TaskList 名称，并去除 MIUI 前缀
            String folderName = getName();
            
            // 检查是否以 "[MIUI_Notes]" 开头，如果是则去掉前缀
            // 因为本地存储只需要用户可见的名称部分
            if (getName().startsWith(GTaskStringUtils.MIUI_FOLDER_PREFFIX))
                folderName = folderName.substring(GTaskStringUtils.MIUI_FOLDER_PREFFIX.length(),
                        folderName.length());
            
            // 将处理后的名称存入 snippet 字段
            folder.put(NoteColumns.SNIPPET, folderName);
            
            // 【类型判断】
            // 根据文件夹名称判断是系统文件夹还是用户文件夹
            if (folderName.equals(GTaskStringUtils.FOLDER_DEFAULT)
                    || folderName.equals(GTaskStringUtils.FOLDER_CALL_NOTE))
                // 特殊名称的文件夹被标记为系统文件夹
                folder.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
            else
                // 普通名称的文件夹为用户文件夹
                folder.put(NoteColumns.TYPE, Notes.TYPE_FOLDER);

            // 将 folder 对象放入主 JSON 的 meta_note 字段
            js.put(GTaskStringUtils.META_HEAD_NOTE, folder);

            return js;
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 根据本地数据库记录确定同步动作类型
     * 
     * 【功能说明】
     * 比较本地数据库中的记录与当前 TaskList 对象的状态，
     * 判断需要进行哪种同步操作。这是双向同步的核心决策逻辑。
     * 
     * 【同步状态判断流程图】
     * 
     * ┌─────────────────────────────────────────────────────────────────────────────┐
     * │                        开始判断                                              │
     * └─────────────────────────────────────────────────────────────────────────────┘
     *                                    │
     *                                    ▼
     *                    ┌───────────────────────────────┐
     *                    │  本地是否有未提交的修改？       │
     *                    │  (local_modified == 0?)      │
     *                    └───────────────────────────────┘
     *                          │                    │
     *                        Yes                   No
     *                          │                    │
     *                          ▼                    ▼
     * ┌─────────────────────┐    ┌─────────────────────────────────────────────┐
     * │ 时间戳是否相同？     │    │  gtask_id 是否匹配？                        │
     * │(sync_id == lastModified)│  └─────────────────────────────────────────────┘
     * └─────────────────────┘              │                    │
     *      │          │                  Yes                   No
     *    Yes         No                   │                    │
     *      │          │                   ▼                    ▼
     *      ▼          ▼         ┌─────────────────┐    ┌─────────────┐
     *  SYNC_ACTION_  SYNC_      │  本地修改时间戳  │    │   错误！    │
     *  NONE          ACTION_    │  与服务端相同？  │    │  gtask_id   │
     *                    │    │                 │    │  不匹配     │
     *  (无需同步)    UPDATE_    └─────────────────┘    └─────────────┘
     *                 LOCAL                 │          │
     *                 (应用远程更新)        ▼          ▼
     *                                Yes              No
     *                                  │               │
     *                                  ▼               ▼
     *                           ┌─────────────┐  ┌─────────────┐
     *                           │  仅本地有   │  │  本地修改   │
     *                           │  修改      │  │  (无论远程 │
     *                           │  → 上传   │  │  是否变化) │
     *                           └─────────────┘  └─────────────┘
     *                           SYNC_ACTION_      SYNC_ACTION_
     *                           UPDATE_REMOTE     UPDATE_REMOTE
     * 
     * 【返回值说明】
     * 
     * | 返回常量                    | 值 | 含义                                           |
     * |----------------------------|----|------------------------------------------------|
     * | SYNC_ACTION_NONE           | 0  | 两端数据相同，无需同步                           |
     * | SYNC_ACTION_UPDATE_LOCAL   | 6  | 应用服务端的更新到本地数据库                      |
     * | SYNC_ACTION_UPDATE_REMOTE  | 5  | 将本地修改上传到服务端                            |
     * | SYNC_ACTION_ERROR          | 8  | 发生错误（如 gtask_id 不匹配）                   |
     * 
     * 【冲突处理策略】
     * 对于文件夹的冲突，本方法采用"本地优先"策略：
     * 当本地和远程都有修改时（lastModified 时间戳不同），优先保留本地修改。
     * 这是因为用户可能正在本地编辑，而远程数据可能已过期。
     * 
     * 【数据库游标列要求】
     * @param c 包含本地笔记记录的数据库游标，必须包含以下列：
     *        - SqlNote.LOCAL_MODIFIED_COLUMN: 本地修改标记
     *        - SqlNote.SYNC_ID_COLUMN: 同步时间戳
     *        - SqlNote.GTASK_ID_COLUMN: Google Task ID
     * 
     * @return int 同步动作类型常量
     * @see Node#SYNC_ACTION_NONE 无需同步
     * @see Node#SYNC_ACTION_UPDATE_LOCAL 应用远程更新
     * @see Node#SYNC_ACTION_UPDATE_REMOTE 上传本地更新
     * @see Node#SYNC_ACTION_ERROR 错误
     */
    public int getSyncAction(Cursor c) {
        try {
            // 【第一步】检查本地是否有未提交的修改
            // local_modified = 0 表示本地没有修改，数据与服务端一致
            if (c.getInt(SqlNote.LOCAL_MODIFIED_COLUMN) == 0) {
                // 【本地无修改】检查服务端是否有更新
                
                // 比较同步时间戳与服务端最后修改时间
                if (c.getLong(SqlNote.SYNC_ID_COLUMN) == getLastModified()) {
                    // 时间戳相同 → 两端数据完全一致，无需同步
                    return SYNC_ACTION_NONE;
                } else {
                    // 时间戳不同 → 服务端有新数据，需要更新本地
                    return SYNC_ACTION_UPDATE_LOCAL;
                }
            } else {
                // 【本地有修改】需要验证并决定同步方向
                
                // 【重要】验证 gtask_id 是否匹配
                // 如果本地记录的 gtask_id 与当前对象不一致，说明数据已被篡改或混淆
                if (!c.getString(SqlNote.GTASK_ID_COLUMN).equals(getGid())) {
                    Log.e(TAG, "gtask id doesn't match");
                    return SYNC_ACTION_ERROR;
                }
                
                // 比较时间戳确定同步方向
                if (c.getLong(SqlNote.SYNC_ID_COLUMN) == getLastModified()) {
                    // 时间戳相同 → 只有本地有修改 → 上传到服务端
                    return SYNC_ACTION_UPDATE_REMOTE;
                } else {
                    // 时间戳不同 → 两边都有修改（冲突）→ 采用本地优先策略
                    // 对于文件夹冲突，优先保留本地修改
                    return SYNC_ACTION_UPDATE_REMOTE;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
        }

        return SYNC_ACTION_ERROR;
    }

    /**
     * 获取子任务的数量
     * 
     * 【功能说明】
     * 返回当前 TaskList 中包含的子 Task 的数量。
     * 
     * 【使用场景】
     * - 同步前检查是否有子任务需要处理
     * - UI 显示文件夹中的任务数量
     * - 验证数据完整性
     * 
     * @return int 子任务的数量，可能为 0（空文件夹）
     */
    public int getChildTaskCount() {
        return mChildren.size();
    }

    /**
     * 添加子任务到列表末尾
     * 
     * 【功能说明】
     * 将指定的 Task 添加到当前 TaskList 的子任务列表的末尾位置。
     * 这是添加子任务的主要方法。
     * 
     * 【添加逻辑详解】
     * 1. 空值检查：如果 task 为 null 或已在列表中，直接返回 false
     * 2. 执行添加：将 task 添加到 mChildren 列表的末尾
     * 3. 更新关联：
     *    a. 设置 task 的 priorSibling 为列表中原有的最后一个任务
     *       - 如果列表原本为空，则设为 null
     *    b. 设置 task 的 parent 为当前 TaskList 对象
     * 
     * 【priorSibling 的作用】
     * priorSibling（前置兄弟节点）用于在 Google Tasks 中维护任务的有序排列。
     * 每个任务通过 priorSibling 指向它前面的任务，形成一个链表结构：
     * 
     *   Task1 ──priorSibling──> null
     *   Task2 ──priorSibling──> Task1
     *   Task3 ──priorSibling──> Task2
     *   ...
     * 
     * 这样 Google Tasks 服务端可以根据这个链表重建任务的排序。
     * 
     * 【参数说明】
     * @param task 要添加的子任务对象，不能为 null
     *        - 如果 task 已在列表中，不会重复添加
     * 
     * 【返回值】
     * @return boolean true 表示添加成功，false 表示添加失败（参数无效或已存在）
     * 
     * 【示例】
     * ```
     * TaskList list = new TaskList();
     * Task task1 = new Task();
     * Task task2 = new Task();
     * 
     * list.addChildTask(task1);  // task1.priorSibling = null, task1.parent = list
     * list.addChildTask(task2);  // task2.priorSibling = task1, task2.parent = list
     * ```
     * 
     * @see #addChildTask(Task, int) 在指定位置添加子任务
     * @see #removeChildTask(Task) 移除子任务
     */
    public boolean addChildTask(Task task) {
        boolean ret = false;
        // 【参数校验】task 不能为空，且不能已在列表中（避免重复添加）
        if (task != null && !mChildren.contains(task)) {
            // 【添加到列表末尾】
            ret = mChildren.add(task);
            if (ret) {
                // 【更新关联关系】
                // 设置 priorSibling：指向列表中原有的最后一个任务
                // 如果列表原本只有一个任务，则 priorSibling 为 null
                task.setPriorSibling(mChildren.isEmpty() ? null : mChildren
                        .get(mChildren.size() - 1));
                // 【设置父子关系】
                // 明确 task 属于当前 TaskList
                task.setParent(this);
            }
        }
        return ret;
    }

    /**
     * 在指定位置添加子任务
     * 
     * 【功能说明】
     * 将指定的 Task 添加到当前 TaskList 的子任务列表的指定位置。
     * 可以用于在列表中间插入任务，或在指定位置重新排列。
     * 
     * 【插入逻辑详解】
     * 1. 索引验证：index 必须在 [0, size()] 范围内
     *    - index = 0 表示插入到列表开头
     *    - index = size() 表示插入到列表末尾（等同于 addChildTask(Task)）
     * 2. 重复检查：如果 task 已在列表中，拒绝添加
     * 3. 执行插入：使用 ArrayList.add(index, task) 在指定位置插入
     * 4. 更新 priorSibling 链表：
     *    - 新任务的 priorSibling 指向插入位置的前一个任务
     *    - 插入位置后一个任务的 priorSibling 需要更新指向新任务
     * 
     * 【索引与位置对应关系】
     * 
     *   索引:  [0]    [1]    [2]    [3]    [4]
     *          │      │      │      │      │
     *          ▼      ▼      ▼      ▼      ▼
     *   任务:  A  →   B  →   C  →   D  →   E
     * 
     *   add(task, 2) 后的结果:
     *   索引:  [0]    [1]    [2]    [3]    [4]    [5]
     *          │      │      │      │      │      │
     *          ▼      ▼      ▼      ▼      ▼      ▼
     *   任务:  A  →   B  →  new →   C  →   D  →   E
     * 
     * 【参数说明】
     * @param task 要添加的子任务对象，不能为 null
     * @param index 目标位置索引，从 0 开始
     *        - 有效范围：[0, mChildren.size()]
     *        - index = 0 插入到列表头部
     *        - index = size() 插入到列表末尾
     * 
     * 【返回值】
     * @return boolean true 表示添加成功，false 表示添加失败（索引无效或任务已存在）
     * 
     * 【与 addChildTask(Task) 的区别】
     * - addChildTask(Task): 默认添加到末尾
     * - addChildTask(Task, int): 可以指定任意位置
     * 
     * @see #addChildTask(Task) 添加到末尾
     * @see #moveChildTask(Task, int) 移动现有任务到指定位置
     */
    public boolean addChildTask(Task task, int index) {
        // 【索引范围检查】index 必须在 [0, size()] 之间
        // size() 是有效值，表示插入到列表末尾
        if (index < 0 || index > mChildren.size()) {
            Log.e(TAG, "add child task: invalid index");
            return false;
        }

        // 【重复检查】确保任务不在列表中
        int pos = mChildren.indexOf(task);
        if (task != null && pos == -1) {
            // 【执行插入】
            mChildren.add(index, task);

            // 【更新 priorSibling 链表】
            // 找出插入位置的前后任务
            Task preTask = null;
            Task afterTask = null;
            if (index != 0)
                preTask = mChildren.get(index - 1);
            if (index != mChildren.size() - 1)
                afterTask = mChildren.get(index + 1);

            // 【设置新任务的 priorSibling】
            task.setPriorSibling(preTask);
            
            // 【更新后继任务的 priorSibling】
            // 如果插入位置后面还有任务，需要更新它的 priorSibling 指向新任务
            if (afterTask != null)
                afterTask.setPriorSibling(task);
        }

        return true;
    }

    /**
     * 从 TaskList 中移除子任务
     * 
     * 【功能说明】
     * 将指定的 Task 从当前 TaskList 的子任务列表中移除。
     * 移除后会清理该任务与列表之间的关联关系。
     * 
     * 【移除逻辑详解】
     * 1. 查找任务：使用 indexOf() 在列表中定位任务
     * 2. 执行移除：调用 ArrayList.remove() 删除任务
     * 3. 清理关联：
     *    a. 将被移除任务的 priorSibling 设为 null（解除指向前一个任务的引用）
     *    b. 将被移除任务的 parent 设为 null（解除与 TaskList 的父子关系）
     * 4. 更新链表：如果移除后列表非空，更新原移除位置后续任务的 priorSibling
     * 
     * 【移除后的链表更新示例】
     * 
     *   移除前:
     *   Task1 → Task2 → Task3 → Task4
     *   移除 Task2:
     *   Task1 → Task3 → Task4
     *   更新: Task3 的 priorSibling 从 Task1 变为 null（因为 Task1 后面没有任务了）
     * 
     * 【参数说明】
     * @param task 要移除的子任务对象
     *        - 如果任务不在列表中，返回 false
     *        - 如果传入 null，直接返回 false
     * 
     * 【返回值】
     * @return boolean true 表示移除成功，false 表示移除失败（任务不在列表中）
     * 
     * 【注意事项】
     * - 移除只是从 TaskList 中移除，Task 对象本身仍然存在
     * - 移除后应考虑将 Task 添加到其他 TaskList 或进行其他处理
     * 
     * @see #addChildTask(Task) 添加子任务
     * @see #moveChildTask(Task, int) 移动子任务
     */
    public boolean removeChildTask(Task task) {
        boolean ret = false;
        // 【查找任务】获取任务在列表中的索引位置
        int index = mChildren.indexOf(task);
        if (index != -1) {
            // 【执行移除】
            ret = mChildren.remove(task);

            if (ret) {
                // 【清理关联关系】
                // 1. 解除 priorSibling 引用
                task.setPriorSibling(null);
                // 2. 解除父子关系
                task.setParent(null);

                // 【更新移除位置后续任务的 priorSibling】
                // 如果移除后列表不为空，需要更新原移除位置后续任务的链表指向
                if (index != mChildren.size()) {
                    // 获取移除位置的新任务（如果有）
                    // 它的 priorSibling 需要更新为指向它前面的任务
                    mChildren.get(index).setPriorSibling(
                            index == 0 ? null : mChildren.get(index - 1));
                }
            }
        }
        return ret;
    }

    /**
     * 移动子任务到指定位置
     * 
     * 【功能说明】
     * 将已在列表中的 Task 移动到指定的位置。
     * 这是 TaskList 中 Task 排序操作的核心方法。
     * 
     * 【移动逻辑】
     * 移动操作实际上是"移除 + 插入"的组合：
     * 1. 先调用 removeChildTask(task) 从原位置移除
     * 2. 再调用 addChildTask(task, index) 插入到新位置
     * 
     * 【位置计算说明】
     * - 目标索引是在"移除原任务后"的新列表中的位置
     * - 这意味着如果将任务从位置 3 移动到位置 0，
     *   任务会移动到列表开头，原位置 0-2 的任务会后移一位
     * 
     * 【索引限制】
     * - 目标索引必须在 [0, size()-1] 范围内
     * - 注意：不同于 addChildTask(Task, int)，这里 size() 是无效的
     * 
     * 【参数说明】
     * @param task 要移动的子任务，必须已在列表中
     * @param index 目标位置索引，从 0 开始
     *        - 有效范围：[0, mChildren.size() - 1]
     *        - 如果目标位置等于当前位置，不执行任何操作，返回 true
     * 
     * 【返回值】
     * @return boolean true 表示移动成功，false 表示移动失败（参数无效）
     * 
     * 【移动示例】
     * ```
     * // 初始状态
     * [TaskA, TaskB, TaskC, TaskD, TaskE]
     * 
     * // moveChildTask(TaskC, 0) 后
     * [TaskC, TaskA, TaskB, TaskD, TaskE]
     * 
     * // moveChildTask(TaskA, 4) 后
     * [TaskC, TaskB, TaskD, TaskE, TaskA]
     * ```
     * 
     * @see #addChildTask(Task, int) 在指定位置添加任务
     * @see #removeChildTask(Task) 移除任务
     */
    public boolean moveChildTask(Task task, int index) {

        // 【索引范围检查】目标索引必须在 [0, size()-1] 之间
        if (index < 0 || index >= mChildren.size()) {
            Log.e(TAG, "move child task: invalid index");
            return false;
        }

        // 【任务存在性检查】确保任务在列表中
        int pos = mChildren.indexOf(task);
        if (pos == -1) {
            Log.e(TAG, "move child task: the task should in the list");
            return false;
        }

        // 【优化】如果目标位置就是当前位置，无需移动
        if (pos == index)
            return true;
            
        // 【执行移动】通过移除和插入实现移动
        // removeChildTask 会将任务从原位置移除
        // addChildTask(task, index) 会在新位置插入
        // 由于 ArrayList 的 add(index, element) 特性，
        // 参数中的 index 是在移除后的新列表中的位置
        return (removeChildTask(task) && addChildTask(task, index));
    }

    /**
     * 根据 Google Task ID 查找子任务
     * 
     * 【功能说明】
     * 在当前 TaskList 的子任务列表中，通过 gid (Google Task ID) 查找对应的 Task 对象。
     * 这是根据服务端 ID 查询任务的主要方法。
     * 
     * 【查找方式】
     * 顺序遍历 mChildren 列表，逐个比较 gid 值。
     * 使用 for 循环而非增强 for 循环是为了更清晰地展示遍历过程。
     * 
     * 【参数说明】
     * @param gid 要查找的 Google Task ID
     *        - 不能为 null
     *        - 通常是从服务端获取的全局唯一标识符
     * 
     * 【返回值】
     * @return Task 如果找到匹配的任务，返回该 Task 对象
     *         如果未找到，返回 null
     * 
     * 【性能说明】
     * - 时间复杂度: O(n)，需要遍历整个列表
     * - 空间复杂度: O(1)，仅使用常量额外空间
     * 
     * 【注意事项】
     * - 如果存在多个相同 gid 的任务（理论上不应该），只返回第一个找到的
     * - gid 是全局唯一标识符，理论上不会有重复
     * 
     * @see #getChilTaskByGid(String) 功能相同的方法（使用增强 for 循环）
     * @see #findChildTaskByGid(String) 功能相同的方法（使用普通 for 循环）
     */
    public Task findChildTaskByGid(String gid) {
        // 【顺序遍历】逐个检查每个子任务的 gid
        for (int i = 0; i < mChildren.size(); i++) {
            Task t = mChildren.get(i);
            if (t.getGid().equals(gid)) {
                return t;
            }
        }
        return null;
    }

    /**
     * 获取指定任务在列表中的索引位置
     * 
     * 【功能说明】
     * 返回指定 Task 在 mChildren 列表中的位置索引。
     * 这是 ArrayList.indexOf() 的包装方法。
     * 
     * 【返回值说明】
     * - 如果任务存在于列表中，返回其索引位置（从 0 开始）
     * - 如果任务不在列表中，返回 -1
     * 
     * @param task 要查找位置的 Task 对象
     * @return int 任务的索引位置，未找到返回 -1
     */
    public int getChildTaskIndex(Task task) {
        return mChildren.indexOf(task);
    }

    /**
     * 根据索引获取子任务
     * 
     * 【功能说明】
     * 返回指定索引位置的子任务。
     * 这是 mChildren.get(index) 的包装方法，带有参数验证。
     * 
     * 【参数说明】
     * @param index 要获取的任务索引
     *        - 有效范围：[0, mChildren.size() - 1]
     * 
     * 【返回值】
     * @return Task 指定索引位置的 Task 对象
     *         如果索引无效，返回 null
     * 
     * @see #getChildTaskIndex(Task) 根据任务获取索引的反向操作
     */
    public Task getChildTaskByIndex(int index) {
        // 【索引范围检查】
        if (index < 0 || index >= mChildren.size()) {
            Log.e(TAG, "getTaskByIndex: invalid index");
            return null;
        }
        return mChildren.get(index);
    }

    /**
     * 根据 Google Task ID 获取子任务
     * 
     * 【功能说明】
     * 与 findChildTaskByGid() 功能完全相同，使用增强 for 循环实现。
     * 提供两种实现方式是为了代码的可读性和一致性。
     * 
     * 【实现说明】
     * 使用 Java 5 引入的增强 for 循环（也称为 for-each 循环），
     * 语法更简洁，但无法获取当前元素的索引。
     * 
     * @param gid 要查找的 Google Task ID
     * @return Task 找到的任务对象，未找到返回 null
     * @see #findChildTaskByGid(String) 功能相同的方法（使用普通 for 循环）
     */
    public Task getChilTaskByGid(String gid) {
        // 【增强 for 循环遍历】
        for (Task task : mChildren) {
            if (task.getGid().equals(gid))
                return task;
        }
        return null;
    }

    /**
     * 获取所有子任务的列表引用
     * 
     * 【功能说明】
     * 返回内部维护的 mChildren 列表的直接引用。
     * 调用者可以获得对内部列表的完整访问权限。
     * 
     * 【使用场景】
     * - 需要遍历所有子任务
     * - 需要对子任务列表进行批量操作
     * - 需要传递给其他方法进行进一步处理
     * 
     * 【返回值说明】
     * @return ArrayList<Task> 子任务列表的引用（非副本）
     *         返回的是内部实际使用的 ArrayList 对象，而非副本
     * 
     * 【注意事项】
     * ⚠️ 返回的是直接引用而非副本！
     * 对返回列表的修改会直接影响 TaskList 的内部状态。
     * 调用者可以：
     * - 读取列表内容（安全）
     * - 修改列表内容（不安全，可能破坏内部一致性）
     * - 删除列表内容（不安全）
     * 
     * 【建议】
     * 如无必要，建议使用以下方法进行只读访问：
     * - getChildTaskCount(): 获取任务数量
     * - getChildTaskByIndex(int): 根据索引获取任务
     * - findChildTaskByGid(String): 根据 gid 查找任务
     * 
     * @see #getChildTaskCount() 获取子任务数量
     */
    public ArrayList<Task> getChildTaskList() {
        return this.mChildren;
    }

    /**
     * 设置排序索引
     * 
     * 【功能说明】
     * 设置 TaskList 在 Google Tasks 中的显示排序位置。
     * 
     * 【使用场景】
     * - 同步时设置服务端返回的排序信息
     * - 调整 TaskList 的显示顺序
     * 
     * @param index 排序索引值，影响 TaskList 的排列顺序
     * @see #getIndex() 获取排序索引
     */
    public void setIndex(int index) {
        this.mIndex = index;
    }

    /**
     * 获取排序索引
     * 
     * 【功能说明】
     * 返回 TaskList 的排序索引值。
     * 
     * @return int 当前的排序索引
     * @see #setIndex(int) 设置排序索引
     */
    public int getIndex() {
        return this.mIndex;
    }
}
