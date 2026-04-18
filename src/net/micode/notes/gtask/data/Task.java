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
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.exception.ActionFailureException;
import net.micode.notes.tool.GTaskStringUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


/**
 * Task 类 - Google Task 实体类，对应小米便签应用中的一张笔记
 * 
 * ==================== Task 与 Note 的映射关系 ====================
 * 
 * 在小米便签与 Google Tasks 同步架构中，Task 类扮演着核心角色：
 * 
 * 【对应关系】
 * - Task (gtask.data.Task)  ←→  Note (本地数据库中的笔记)
 * - TaskList (gtask.data.TaskList)  ←→  Folder/目录 (本地文件夹)
 * 
 * 【具体映射】
 * - Task 的名称 (getName/setName)  ←→  笔记的标题/内容
 * - Task 的完成状态 (mCompleted)  ←→  笔记的特殊标记
 * - Task 的 GID (Google Task ID)  ←→  笔记的远程同步ID
 * - TaskList 的 GID  ←→  文件夹的远程同步ID
 * 
 * 【同步流程】
 * 1. 本地笔记 (Note) → 同步为 → 远程 Task (Google Tasks 中的任务)
 * 2. 远程 Task → 同步回 → 本地笔记 (Note)
 * 3. 笔记内容存储在 Task 的 mMetaInfo (JSONObject) 中
 * 
 * ==================== Task 与父节点 TaskList 的关系 ====================
 * 
 * 【层级结构】
 * TaskList (文件夹)
 *    │
 *    ├── Task (笔记1)
 *    ├── Task (笔记2)
 *    │      └── prior_sibling → Task (笔记1)  ← 表示排序
 *    └── Task (笔记3)
 *           └── prior_sibling → Task (笔记2)
 * 
 * 【关键属性】
 * - mParent: 指向所属的 TaskList（文件夹），用于获取父文件夹信息
 * - mPriorSibling: 指向同级前一个 Task，用于维护同文件夹内的排序
 * 
 * 【TaskList 的作用】
 * 1. 作为 Task 的容器，管理一组相关笔记
 * 2. 提供 getChildTaskIndex() 方法，确定 Task 在列表中的位置
 * 3. 提供 getGid() 方法，用于构建创建/更新 Task 的 JSON 请求
 * 
 * ==================== 构造函数与 init() 方法的区别 ====================
 * 
 * 【构造函数 Task()】
 * - 无需显式调用 init()，构造函数已完成所有初始化工作
 * - 初始化内容：
 *   • 调用父类 Node 的构造函数，初始化基础属性（mGid=null, mName="", mLastModified=0, mDeleted=false）
 *   • 设置 mCompleted = false（任务默认未完成）
 *   • 设置 mNotes = null（附加说明为空）
 *   • 设置 mPriorSibling = null（无前序兄弟节点）
 *   • 设置 mParent = null（无父 TaskList）
 *   • 设置 mMetaInfo = null（元信息为空）
 * 
 * 【无 init() 方法】
 * - 本类没有 init() 方法，所有初始化都在构造函数中完成
 * - 后续的数据填充通过 setContentByRemoteJSON() 或 setContentByLocalJSON() 完成
 * 
 * ==================== getJSONObject() 返回的 JSON 格式 ====================
 * 
 * 【getCreateAction() 返回的创建 JSON】
 * 用于在 Google Tasks 服务器上创建新任务，格式如下：
 * 
 * {
 *   "action_type": "create",           // 操作类型：创建
 *   "action_id": <数字>,               // 本次操作的唯一标识符
 *   "index": <数字>,                   // 在父 TaskList 中的排序索引
 *   "entity_delta": {                   // 要创建的实体详情
 *       "name": "<任务名称>",           // 任务标题
 *       "creator_id": "null",          // 创建者ID（固定为"null"）
 *       "entity_type": "TASK",          // 实体类型：任务
 *       "notes": "<附加说明>"           // 任务备注（可选）
 *   },
 *   "parent_id": "<父TaskList的GID>",   // 父文件夹的全局ID
 *   "dest_parent_type": "GROUP",        // 目标父节点类型：文件夹
 *   "list_id": "<TaskList的GID>",      // 所属任务列表的ID
 *   "prior_sibling_id": "<前序Task的GID>" // 前序兄弟任务ID（可选，用于排序）
 * }
 * 
 * 【getUpdateAction() 返回的更新 JSON】
 * 用于更新 Google Tasks 服务器上的现有任务，格式如下：
 * 
 * {
 *   "action_type": "update",           // 操作类型：更新
 *   "action_id": <数字>,               // 本次操作的唯一标识符
 *   "id": "<Task的GID>",               // 要更新的任务ID
 *   "entity_delta": {                   // 要更新的实体字段
 *       "name": "<任务名称>",           // 新的任务标题
 *       "notes": "<附加说明>",          // 新的任务备注（可选）
 *       "deleted": <boolean>           // 删除标记
 *   }
 * }
 * 
 * ==================== setMetaInfo() 和 setContent() 的用途 ====================
 * 
 * 【setMetaInfo(MetaData metaData)】
 * - 功能：从 MetaData 对象中提取并设置任务的元信息
 * - 工作原理：
 *   • MetaData 包含一个 JSON 字符串，存储笔记的完整数据库记录
 *   • 此方法将 JSON 字符串解析为 JSONObject，存入 mMetaInfo
 *   • mMetaInfo 包含笔记的原始数据，用于后续同步冲突判断
 * 
 * - 典型场景：
 *   • 从本地数据库读取笔记时，调用此方法恢复 mMetaInfo
 *   • 用于判断本地笔记与远程 Task 之间的同步关系
 * 
 * 【setNotes(String notes) / getNotes()】
 * - 功能：存储任务的附加说明/备注
 * - 与 mMetaInfo 的区别：
 *   • mNotes: 简单字符串，存储 Task 的附加说明
 *   • mMetaInfo: JSONObject，存储完整的笔记数据库记录（含ID、类型、数据等）
 * 
 * 【setName(String name) / getName()】(继承自 Node)
 * - 功能：设置/获取任务的名称（即笔记的标题/内容摘要）
 * - 注意：Task 的名称对应笔记的主要内容，而非独立字段
 * 
 * ==================== 类的核心职责 ====================
 * 
 * 1. 数据模型：表示 Google Tasks 中的一个任务（对应本地的一张笔记）
 * 2. JSON 序列化：将 Task 数据转换为 Google Tasks API 所需的 JSON 格式
 * 3. JSON 反序列化：从 Google Tasks API 返回的 JSON 中解析 Task 数据
 * 4. 本地数据转换：将本地笔记数据库的 Cursor/JSON 转换为 Task 对象
 * 5. 同步决策：根据本地和远程数据的修改时间戳，决定同步方向和策略
 */
public class Task extends Node {
    private static final String TAG = Task.class.getSimpleName();

    /** 任务完成状态标记（true=已完成，false=未完成） */
    private boolean mCompleted;

    /** 任务的附加说明/备注文本 */
    private String mNotes;

    /** 
     * 任务的元信息（JSONObject 格式）
     * 
     * mMetaInfo 存储笔记的完整数据库记录结构，用于：
     * 1. 记录笔记的原始数据，便于同步冲突时比较
     * 2. 判断笔记是否已被本地修改
     * 3. 在 getLocalJSONFromContent() 中还原笔记数据
     * 
     * 典型结构：
     * {
     *   "meta_note": {           // 笔记的基本信息
     *       "id": <笔记ID>,
     *       "type": <笔记类型>,
     *       ...其他NoteColumns字段
     *   },
     *   "meta_data": [           // 笔记的数据项列表
     *       {
     *           "mime_type": "vnd.android.cursor.item/vnd.miui.note",
     *           "content": "<笔记正文内容>",
     *           ...其他DataColumns字段
     *       }
     *   ]
     * }
     */
    private JSONObject mMetaInfo;

    /** 
     * 前序兄弟任务节点（用于维护同文件夹内的排序）
     * 
     * Google Tasks 使用 prior_sibling_id 来维护同一列表内任务的相对顺序。
     * mPriorSibling 指向排序在当前 Task 之前的那个 Task。
     * 
     * 例如：文件夹中有 TaskA, TaskB, TaskC 按此顺序排列
     * - TaskA 的 mPriorSibling = null（最前面）
     * - TaskB 的 mPriorSibling = TaskA
     * - TaskC 的 mPriorSibling = TaskB
     */
    private Task mPriorSibling;

    /** 当前 Task 所属的父 TaskList（文件夹）引用 */
    private TaskList mParent;

    /**
     * 构造函数 - 初始化一个新的 Task 对象
     * 
     * 【设计说明】
     * 与某些使用构造函数+init()分离模式的类不同，Task 类将所有初始化逻辑
     * 集中在构造函数中完成。这是一种简化设计，因为 Task 对象的创建和初始化
     * 通常是原子操作，不需要分开处理。
     * 
     * 【初始化状态】
     * - 继承自 Node: mGid=null, mName="", mLastModified=0, mDeleted=false
     * - Task 特有: mCompleted=false, mNotes=null, mPriorSibling=null, 
     *             mParent=null, mMetaInfo=null
     */
    public Task() {
        super();
        mCompleted = false;
        mNotes = null;
        mPriorSibling = null;
        mParent = null;
        mMetaInfo = null;
    }

    /**
     * 生成创建任务的 JSON 请求对象
     * 
     * 【用途】
     * 当需要将本地笔记同步到 Google Tasks 服务器时，调用此方法生成
     * 创建新 Task 的 JSON 请求体。
     * 
     * 【返回的 JSON 结构】
     * 此方法生成的 JSON 包含创建任务所需的所有字段，包括：
     * - action_type: 固定为 "create"
     * - action_id: 操作的唯一标识
     * - index: 在父文件夹中的排序位置
     * - entity_delta: 任务的实体详情（名称、创建者、类型、备注）
     * - parent_id: 所属父文件夹的 GID
     * - dest_parent_type: 目标父节点类型（固定为 "GROUP"）
     * - list_id: 所属任务列表的 GID
     * - prior_sibling_id: 前序兄弟任务的 GID（用于排序，可选）
     * 
     * 【使用场景】
     * - 本地新建了一篇笔记，需要同步到 Google Tasks
     * - 用户选择将某笔记推送到云端
     * 
     * @param actionId 操作ID，用于标识本次同步操作
     * @return JSONObject 包含创建任务所需的完整 JSON 数据
     * @throws ActionFailureException 如果 JSON 构建失败
     */
    public JSONObject getCreateAction(int actionId) {
        JSONObject js = new JSONObject();

        try {
            // action_type: 操作类型，此处固定为"create"表示创建新任务
            js.put(GTaskStringUtils.GTASK_JSON_ACTION_TYPE,
                    GTaskStringUtils.GTASK_JSON_ACTION_TYPE_CREATE);

            // action_id: 本次操作的唯一标识符，用于服务器响应时匹配
            js.put(GTaskStringUtils.GTASK_JSON_ACTION_ID, actionId);

            // index: 任务在父 TaskList 中的排序索引
            // 调用父 TaskList 的 getChildTaskIndex() 获取当前位置
            js.put(GTaskStringUtils.GTASK_JSON_INDEX, mParent.getChildTaskIndex(this));

            // entity_delta: 包含要创建的实体详情
            JSONObject entity = new JSONObject();
            // name: 任务的名称（对应笔记的标题或内容）
            entity.put(GTaskStringUtils.GTASK_JSON_NAME, getName());
            // creator_id: 创建者ID，Google Tasks 中固定为"null"
            entity.put(GTaskStringUtils.GTASK_JSON_CREATOR_ID, "null");
            // entity_type: 实体类型，Task 固定为"TASK"
            entity.put(GTaskStringUtils.GTASK_JSON_ENTITY_TYPE,
                    GTaskStringUtils.GTASK_JSON_TYPE_TASK);
            // notes: 任务的附加说明（可选，如果存在则添加）
            if (getNotes() != null) {
                entity.put(GTaskStringUtils.GTASK_JSON_NOTES, getNotes());
            }
            js.put(GTaskStringUtils.GTASK_JSON_ENTITY_DELTA, entity);

            // parent_id: 父文件夹的全局ID
            // 用于告诉服务器将此任务创建在哪个文件夹下
            js.put(GTaskStringUtils.GTASK_JSON_PARENT_ID, mParent.getGid());

            // dest_parent_type: 目标父节点类型
            // "GROUP" 表示父节点是一个任务列表（文件夹）
            js.put(GTaskStringUtils.GTASK_JSON_DEST_PARENT_TYPE,
                    GTaskStringUtils.GTASK_JSON_TYPE_GROUP);

            // list_id: 所属任务列表的ID
            // 与 parent_id 通常相同，因为 TaskList 就是文件夹
            js.put(GTaskStringUtils.GTASK_JSON_LIST_ID, mParent.getGid());

            // prior_sibling_id: 前序兄弟任务的GID
            // 用于维护同一文件夹内任务的排序，可选字段
            if (mPriorSibling != null) {
                js.put(GTaskStringUtils.GTASK_JSON_PRIOR_SIBLING_ID, mPriorSibling.getGid());
            }

        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("fail to generate task-create jsonobject");
        }

        return js;
    }

    /**
     * 生成更新任务的 JSON 请求对象
     * 
     * 【用途】
     * 当需要将本地笔记的修改同步到 Google Tasks 服务器时，调用此方法生成
     * 更新现有 Task 的 JSON 请求体。
     * 
     * 【与 getCreateAction() 的区别】
     * - getCreateAction(): 创建新任务，包含创建所需的完整信息
     * - getUpdateAction(): 更新现有任务，只需包含变更的字段
     * 
     * 【返回的 JSON 结构】
     * - action_type: 固定为 "update"
     * - action_id: 操作的唯一标识
     * - id: 要更新的任务ID（通过 getGid() 获取）
     * - entity_delta: 包含要更新的字段（名称、备注、删除标记等）
     * 
     * 【使用场景】
     * - 用户修改了本地笔记内容，需要同步到云端
     * - 用户标记笔记为已完成/未完成
     * - 用户删除了一篇已同步的笔记
     * 
     * @param actionId 操作ID，用于标识本次同步操作
     * @return JSONObject 包含更新任务所需的完整 JSON 数据
     * @throws ActionFailureException 如果 JSON 构建失败
     */
    public JSONObject getUpdateAction(int actionId) {
        JSONObject js = new JSONObject();

        try {
            // action_type: 操作类型，此处固定为"update"表示更新任务
            js.put(GTaskStringUtils.GTASK_JSON_ACTION_TYPE,
                    GTaskStringUtils.GTASK_JSON_ACTION_TYPE_UPDATE);

            // action_id: 本次操作的唯一标识符
            js.put(GTaskStringUtils.GTASK_JSON_ACTION_ID, actionId);

            // id: 要更新的任务的全局ID
            // 这是 Google Tasks 分配的唯一标识符
            js.put(GTaskStringUtils.GTASK_JSON_ID, getGid());

            // entity_delta: 包含要更新的字段
            JSONObject entity = new JSONObject();
            // name: 更新后的任务名称
            entity.put(GTaskStringUtils.GTASK_JSON_NAME, getName());
            // notes: 更新后的附加说明（可选）
            if (getNotes() != null) {
                entity.put(GTaskStringUtils.GTASK_JSON_NOTES, getNotes());
            }
            // deleted: 删除标记（从父类 Node 继承）
            entity.put(GTaskStringUtils.GTASK_JSON_DELETED, getDeleted());
            js.put(GTaskStringUtils.GTASK_JSON_ENTITY_DELTA, entity);

        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            throw new ActionFailureException("fail to generate task-update jsonobject");
        }

        return js;
    }

    /**
     * 从远程 Google Tasks JSON 响应中解析并设置任务内容
     * 
     * 【用途】
     * 当从 Google Tasks 服务器获取任务数据时，使用此方法解析 JSON 响应，
     * 将远程数据填充到当前 Task 对象中。
     * 
     * 【解析的 JSON 字段】
     * - id: 任务的全局唯一标识符 (GID)
     * - last_modified: 最后修改时间戳
     * - name: 任务名称
     * - notes: 任务附加说明
     * - deleted: 删除标记
     * - completed: 完成状态
     * 
     * 【设计说明】
     * 此方法只解析并设置 Task 自身的核心属性，不涉及：
     * - mParent (父 TaskList)：需要额外逻辑设置
     * - mPriorSibling (前序兄弟)：需要额外逻辑设置
     * - mMetaInfo (元信息)：通过 setMetaInfo() 单独设置
     * 
     * 【使用场景】
     * 1. 从服务器拉取任务列表时，解析每个任务的 JSON 数据
     * 2. 同步完成后，更新本地 Task 对象的远程状态
     * 
     * @param js 从 Google Tasks API 返回的 JSON 对象
     * @throws ActionFailureException 如果 JSON 解析失败
     */
    public void setContentByRemoteJSON(JSONObject js) {
        if (js != null) {
            try {
                // id: 解析任务的全局唯一标识符
                // 这是 Google Tasks 为每个任务分配的唯一ID
                if (js.has(GTaskStringUtils.GTASK_JSON_ID)) {
                    setGid(js.getString(GTaskStringUtils.GTASK_JSON_ID));
                }

                // last_modified: 解析最后修改时间戳
                // 这是一个长整型时间戳，用于同步冲突判断
                if (js.has(GTaskStringUtils.GTASK_JSON_LAST_MODIFIED)) {
                    setLastModified(js.getLong(GTaskStringUtils.GTASK_JSON_LAST_MODIFIED));
                }

                // name: 解析任务名称
                // 这对应笔记的标题或内容摘要
                if (js.has(GTaskStringUtils.GTASK_JSON_NAME)) {
                    setName(js.getString(GTaskStringUtils.GTASK_JSON_NAME));
                }

                // notes: 解析任务的附加说明
                // 这是任务的额外描述文本
                if (js.has(GTaskStringUtils.GTASK_JSON_NOTES)) {
                    setNotes(js.getString(GTaskStringUtils.GTASK_JSON_NOTES));
                }

                // deleted: 解析删除标记
                // true 表示该任务已被删除
                if (js.has(GTaskStringUtils.GTASK_JSON_DELETED)) {
                    setDeleted(js.getBoolean(GTaskStringUtils.GTASK_JSON_DELETED));
                }

                // completed: 解析完成状态
                // true 表示任务已完成
                if (js.has(GTaskStringUtils.GTASK_JSON_COMPLETED)) {
                    setCompleted(js.getBoolean(GTaskStringUtils.GTASK_JSON_COMPLETED));
                }
            } catch (JSONException e) {
                Log.e(TAG, e.toString());
                e.printStackTrace();
                throw new ActionFailureException("fail to get task content from jsonobject");
            }
        }
    }

    /**
     * 从本地笔记数据库的 JSON 表示中解析并设置任务内容
     * 
     * 【用途】
     * 当从本地数据库恢复笔记数据时，使用此方法解析存储在 SQLite 中的
     * JSON 数据，将其转换为 Task 对象的状态。
     * 
     * 【期望的 JSON 结构】
     * {
     *   "meta_note": {           // 笔记基本信息
     *       "type": <笔记类型>,   // 必须是 Notes.TYPE_NOTE
     *       ...其他字段
     *   },
     *   "meta_data": [           // 笔记数据项列表
     *       {
     *           "mime_type": "vnd.android.cursor.item/vnd.miui.note",
     *           "content": "<笔记正文>",
     *           ...其他字段
     *       }
     *   ]
     * }
     * 
     * 【解析逻辑】
     * 1. 首先验证 JSON 结构包含必要的 "meta_note" 和 "meta_data" 字段
     * 2. 检查笔记类型是否为 TYPE_NOTE（普通笔记）
     * 3. 遍历 dataArray，找到 mime_type 为 NOTE 的数据项
     * 4. 将该数据项的 content 字段设置为 Task 的名称
     * 
     * 【与 setContentByRemoteJSON() 的区别】
     * - setContentByRemoteJSON(): 从 Google Tasks API 解析
     *   → 解析字段：id, last_modified, name, notes, deleted, completed
     * - setContentByLocalJSON(): 从本地数据库 JSON 解析
     *   → 解析字段：content → name (笔记正文映射为任务名称)
     * 
     * 【使用场景】
     * - 从本地数据库加载笔记创建 Task 对象时
     * - 本地笔记迁移到同步模块时
     * 
     * @param js 本地数据库中存储的笔记 JSON 表示
     */
    public void setContentByLocalJSON(JSONObject js) {
        // 检查 JSON 是否有效且包含必要字段
        if (js == null || !js.has(GTaskStringUtils.META_HEAD_NOTE)
                || !js.has(GTaskStringUtils.META_HEAD_DATA)) {
            Log.w(TAG, "setContentByLocalJSON: nothing is avaiable");
        }

        try {
            // 获取笔记基本信息对象
            JSONObject note = js.getJSONObject(GTaskStringUtils.META_HEAD_NOTE);
            // 获取笔记数据项数组
            JSONArray dataArray = js.getJSONArray(GTaskStringUtils.META_HEAD_DATA);

            // 验证笔记类型是否为 TYPE_NOTE
            // TYPE_NOTE = 普通便签类型
            // TYPE_FOLDER = 文件夹类型（由 TaskList 处理）
            // TYPE_SYSTEM = 系统文件夹类型
            if (note.getInt(NoteColumns.TYPE) != Notes.TYPE_NOTE) {
                Log.e(TAG, "invalid type");
                return;
            }

            // 遍历数据项数组，查找笔记正文
            for (int i = 0; i < dataArray.length(); i++) {
                JSONObject data = dataArray.getJSONObject(i);
                // 查找 mime_type 为 NOTE 的数据项（包含实际笔记内容）
                if (TextUtils.equals(data.getString(DataColumns.MIME_TYPE), DataConstants.NOTE)) {
                    // 将笔记内容设置为 Task 的名称
                    setName(data.getString(DataColumns.CONTENT));
                    break;
                }
            }

        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
        }
    }

    /**
     * 将任务内容转换为本地的 JSON 格式
     * 
     * 【用途】
     * 将 Task 对象的当前状态转换为本地的 JSON 格式，用于：
     * 1. 保存到本地 SQLite 数据库
     * 2. 在同步冲突时进行比较
     * 
     * 【返回的 JSON 结构】
     * {
     *   "meta_note": {           // 笔记基本信息
     *       "type": Notes.TYPE_NOTE,  // 固定为普通笔记类型
     *   },
     *   "meta_data": [           // 笔记数据项列表
     *       {
     *           "content": "<任务名称（笔记正文）>",
     *       }
     *   ]
     * }
     * 
     * 【两种处理逻辑】
     * 
     * 【情况1：新创建的远程任务（mMetaInfo == null）】
     * - 没有本地元信息，说明这是从 Google Tasks 同步过来的新任务
     * - 需要构建全新的 JSON 结构
     * - 检查任务名称是否为空，为空则返回 null
     * 
     * 【情况2：已同步的任务（mMetaInfo != null）】
     * - 已有本地元信息，说明这是本地笔记同步后再次导出
     * - 在原有 mMetaInfo 基础上更新笔记内容
     * - 遍历 dataArray，找到笔记数据项，更新其 content 字段
     * - 保留其他元信息（如笔记ID、创建时间等）
     * 
     * 【使用场景】
     * - 同步完成后，将远程任务保存到本地数据库
     * - 生成用于存储在 SQLite 中的 JSON 数据
     * 
     * @return JSONObject 本地 JSON 格式的任务数据，失败返回 null
     */
    public JSONObject getLocalJSONFromContent() {
        String name = getName();
        try {
            if (mMetaInfo == null) {
                // 情况1：这是从远程新创建的任务，没有本地元信息
                // 需要构建全新的 JSON 结构
                
                if (name == null) {
                    // 任务名称为空，这可能是一个无效的笔记
                    Log.w(TAG, "the note seems to be an empty one");
                    return null;
                }

                // 构建完整的 JSON 结构
                JSONObject js = new JSONObject();
                JSONObject note = new JSONObject();
                JSONArray dataArray = new JSONArray();
                JSONObject data = new JSONObject();
                
                // 设置笔记正文内容
                data.put(DataColumns.CONTENT, name);
                dataArray.put(data);
                js.put(GTaskStringUtils.META_HEAD_DATA, dataArray);
                
                // 设置笔记类型为普通便签
                note.put(NoteColumns.TYPE, Notes.TYPE_NOTE);
                js.put(GTaskStringUtils.META_HEAD_NOTE, note);
                
                return js;
            } else {
                // 情况2：这是已同步的任务，需要更新现有元信息
                // 从 mMetaInfo 中提取现有的结构和数据
                
                JSONObject note = mMetaInfo.getJSONObject(GTaskStringUtils.META_HEAD_NOTE);
                JSONArray dataArray = mMetaInfo.getJSONArray(GTaskStringUtils.META_HEAD_DATA);

                // 遍历数据项，找到并更新笔记内容
                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject data = dataArray.getJSONObject(i);
                    // 查找笔记数据项
                    if (TextUtils.equals(data.getString(DataColumns.MIME_TYPE), DataConstants.NOTE)) {
                        // 更新笔记正文
                        data.put(DataColumns.CONTENT, getName());
                        break;
                    }
                }

                // 确保笔记类型正确
                note.put(NoteColumns.TYPE, Notes.TYPE_NOTE);
                // 返回更新后的 mMetaInfo（保持引用不变）
                return mMetaInfo;
            }
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 设置任务的元信息
     * 
     * 【功能说明】
     * 此方法接收一个 MetaData 对象，从中提取笔记的完整数据库记录（JSON 字符串），
     * 并将其解析为 JSONObject 存储到 mMetaInfo 中。
     * 
     * 【MetaData 与 Task 的关系】
     * - MetaData 是 Task 的子类
     * - MetaData 用于存储 Task 与本地笔记之间的映射关系
     * - MetaData 的 getNotes() 返回包含完整笔记数据的 JSON 字符串
     * 
     * 【典型场景】
     * 1. 从本地数据库加载笔记时，先读取 MetaData
     * 2. 调用 setMetaInfo(metaData) 将元信息传递给对应的 Task
     * 3. Task 通过 mMetaInfo 获取笔记的原始数据，用于后续同步决策
     * 
     * 【数据流向】
     * 本地数据库 → MetaData.getNotes() → JSON 字符串 
     *     ↓
     * setMetaInfo() 解析
     *     ↓
     * mMetaInfo (JSONObject)
     *     ↓
     * 用于 getSyncAction() 判断同步策略
     * 
     * @param metaData 包含笔记元信息的 MetaData 对象
     */
    public void setMetaInfo(MetaData metaData) {
        if (metaData != null && metaData.getNotes() != null) {
            try {
                // 将 MetaData 中的 JSON 字符串解析为 JSONObject
                mMetaInfo = new JSONObject(metaData.getNotes());
            } catch (JSONException e) {
                Log.w(TAG, e.toString());
                // 解析失败时置为空，后续处理会走其他逻辑
                mMetaInfo = null;
            }
        }
    }

    /**
     * 判断同步操作的方向和类型
     * 
     * 【功能说明】
     * 根据本地笔记（Cursor）和远程任务（mMetaInfo）的状态比较，
     * 决定应该执行哪种同步操作。
     * 
     * 【同步决策逻辑】
     * 
     * 1. 首先检查 mMetaInfo 是否有效
     *    - 如果 mMetaInfo 为空或缺少 meta_note：元信息可能已删除
     *      → 返回 SYNC_ACTION_UPDATE_REMOTE（尝试更新远程）
     * 
     * 2. 检查笔记 ID 是否匹配
     *    - 如果本地笔记 ID 与元信息中的 ID 不匹配
     *      → 返回 SYNC_ACTION_UPDATE_LOCAL（以本地为准）
     * 
     * 3. 比较修改时间戳（local_modified）
     *    - 如果本地未修改（local_modified = 0）：
     *      a. 比较 last_modified：
     *         - 相等：双方都无修改 → 返回 SYNC_ACTION_NONE
     *         - 不等：远程有更新 → 返回 SYNC_ACTION_UPDATE_LOCAL
     * 
     *    - 如果本地已修改（local_modified = 1）：
     *      a. 验证 gtask_id 是否匹配
     *         - 不匹配：返回 SYNC_ACTION_ERROR（数据不一致）
     *      b. 比较 last_modified：
     *         - 相等：只有本地修改 → 返回 SYNC_ACTION_UPDATE_REMOTE
     *         - 不等：双方都修改 → 返回 SYNC_ACTION_UPDATE_CONFLICT
     * 
     * 【返回值说明】
     * - SYNC_ACTION_NONE: 无需同步
     * - SYNC_ACTION_UPDATE_LOCAL: 以本地数据为准，更新本地
     * - SYNC_ACTION_UPDATE_REMOTE: 以本地数据为准，更新远程
     * - SYNC_ACTION_UPDATE_CONFLICT: 存在冲突，需要解决
     * - SYNC_ACTION_ERROR: 发生错误
     * 
     * @param c 本地笔记数据库的 Cursor
     * @return 同步操作类型常量
     */
    public int getSyncAction(Cursor c) {
        try {
            JSONObject noteInfo = null;
            // 尝试从 mMetaInfo 中获取笔记信息
            if (mMetaInfo != null && mMetaInfo.has(GTaskStringUtils.META_HEAD_NOTE)) {
                noteInfo = mMetaInfo.getJSONObject(GTaskStringUtils.META_HEAD_NOTE);
            }

            // 检查元信息是否有效
            if (noteInfo == null) {
                Log.w(TAG, "it seems that note meta has been deleted");
                return SYNC_ACTION_UPDATE_REMOTE;
            }

            // 检查笔记 ID 是否存在
            if (!noteInfo.has(NoteColumns.ID)) {
                Log.w(TAG, "remote note id seems to be deleted");
                return SYNC_ACTION_UPDATE_LOCAL;
            }

            // 验证笔记 ID 是否匹配
            // local_modified 是本地修改标记，0=未修改，1=已修改
            if (c.getLong(SqlNote.ID_COLUMN) != noteInfo.getLong(NoteColumns.ID)) {
                Log.w(TAG, "note id doesn't match");
                return SYNC_ACTION_UPDATE_LOCAL;
            }

            // 根据本地修改状态决定同步方向
            if (c.getInt(SqlNote.LOCAL_MODIFIED_COLUMN) == 0) {
                // 【情况1】本地未修改
                
                // 比较同步时间戳
                if (c.getLong(SqlNote.SYNC_ID_COLUMN) == getLastModified()) {
                    // 远程未修改，双方一致，无需同步
                    return SYNC_ACTION_NONE;
                } else {
                    // 远程有更新，应用到本地
                    return SYNC_ACTION_UPDATE_LOCAL;
                }
            } else {
                // 【情况2】本地已修改
                
                // 首先验证 gtask_id 是否一致
                if (!c.getString(SqlNote.GTASK_ID_COLUMN).equals(getGid())) {
                    Log.e(TAG, "gtask id doesn't match");
                    return SYNC_ACTION_ERROR;
                }
                
                // 比较时间戳
                if (c.getLong(SqlNote.SYNC_ID_COLUMN) == getLastModified()) {
                    // 只有本地修改，需要推送到远程
                    return SYNC_ACTION_UPDATE_REMOTE;
                } else {
                    // 双方都修改了，存在冲突
                    return SYNC_ACTION_UPDATE_CONFLICT;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
        }

        return SYNC_ACTION_ERROR;
    }

    /**
     * 判断 Task 是否值得保存
     * 
     * 【用途】
     * 在同步过程中，需要判断一个 Task 对象是否包含有效数据，
     * 决定是否应该将其保存到本地数据库或推送到远程服务器。
     * 
     * 【判断条件】（满足任一即可）
     * 1. mMetaInfo 不为空（已有元信息，说明是同步过的任务）
     * 2. 任务名称不为空且不为空白字符串
     * 3. 附加说明不为空且不为空白字符串
     * 
     * 【设计目的】
     * - 过滤掉空笔记，避免创建无意义的同步任务
     * - 避免浪费存储空间和网络带宽
     * 
     * @return true=值得保存，false=不值得保存
     */
    public boolean isWorthSaving() {
        return mMetaInfo != null || (getName() != null && getName().trim().length() > 0)
                || (getNotes() != null && getNotes().trim().length() > 0);
    }

    /**
     * 设置任务的完成状态
     * @param completed true=已完成，false=未完成
     */
    public void setCompleted(boolean completed) {
        this.mCompleted = completed;
    }

    /**
     * 设置任务的附加说明/备注
     * 
     * 【与 setName() 的区别】
     * - setName(): 设置任务名称，对应笔记的标题/正文
     * - setNotes(): 设置附加说明，是任务的额外描述
     * 
     * @param notes 附加说明文本
     */
    public void setNotes(String notes) {
        this.mNotes = notes;
    }

    /**
     * 设置前序兄弟任务
     * 
     * 【用途】
     * 用于维护同一 TaskList（文件夹）内任务的排序。
     * 
     * 【排序机制】
     * Google Tasks 使用 prior_sibling_id 机制维护顺序：
     * - 每个任务记录它前面那个任务的 ID
     * - 第一个任务的 prior_sibling_id 为 null
     * 
     * 【示例】
     * 假设文件夹顺序为：A → B → C
     * - A.setPriorSibling(null)
     * - B.setPriorSibling(A)
     * - C.setPriorSibling(B)
     * 
     * @param priorSibling 前序兄弟任务（排在当前任务之前的那个任务）
     */
    public void setPriorSibling(Task priorSibling) {
        this.mPriorSibling = priorSibling;
    }

    /**
     * 设置父 TaskList（文件夹）
     * 
     * 【用途】
     * 指定当前任务所属的文件夹，用于：
     * 1. 确定任务的组织结构
     * 2. 获取父文件夹的 GID（用于构建 JSON 请求）
     * 3. 获取任务在文件夹中的位置索引
     * 
     * @param parent 父 TaskList 对象
     */
    public void setParent(TaskList parent) {
        this.mParent = parent;
    }

    /**
     * 获取任务完成状态
     * @return true=已完成，false=未完成
     */
    public boolean getCompleted() {
        return this.mCompleted;
    }

    /**
     * 获取任务的附加说明/备注
     * @return 附加说明文本
     */
    public String getNotes() {
        return this.mNotes;
    }

    /**
     * 获取前序兄弟任务
     * @return 前序兄弟 Task 对象，如果是最前面的任务则返回 null
     */
    public Task getPriorSibling() {
        return this.mPriorSibling;
    }

    /**
     * 获取父 TaskList
     * @return 父 TaskList 对象，如果未设置则返回 null
     */
    public TaskList getParent() {
        return this.mParent;
    }

}
