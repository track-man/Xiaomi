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

import org.json.JSONObject;

/**
 * Node 抽象基类 - 同步实体的基础抽象
 * 
 * 设计意图:
 * -----------
 * Node 是小米便签同步系统中所有同步实体的抽象基类。它定义了本地数据与远程服务器
 * (如 Google Tasks) 之间进行双向同步所需的基本属性和契约。
 * 
 * 该类采用了"模板方法模式"与"策略模式"的结合：
 * - 模板方法模式：定义了同步操作的骨架（getCreateAction, getUpdateAction 等），
 *   由子类实现具体的数据转换逻辑
 * - 策略模式：不同的 Node 子类（如 Note、Folder）可以有不同的 JSON 序列化策略
 * 
 * 同步模型说明:
 * -------------
 * 在这个同步框架中，每个 Node 代表一个需要在本地和远程之间同步的数据实体。
 * 同步操作遵循以下原则：
 * 1. 每次同步时，系统会比较本地和远程数据的状态差异
 * 2. 根据差异类型，确定需要执行的同步动作（SYNC_ACTION_*）
 * 3. 根据同步动作生成对应的远程 API 调用（创建、更新、删除）
 * 
 * 子类实现提示:
 * -------------
 * 具体的同步实体类（如 TaskNote、Folder）需要继承此类并实现以下抽象方法：
 * - getCreateAction(): 生成创建实体的远程 API 请求
 * - getUpdateAction(): 生成更新实体的远程 API 请求
 * - setContentByRemoteJSON(): 解析远程服务器返回的 JSON 数据
 * - setContentByLocalJSON(): 从本地 JSON 格式恢复数据
 * - getLocalJSONFromContent(): 将本地数据序列化为 JSON 格式
 * - getSyncAction(): 根据游标数据判断需要执行的同步动作
 * 
 * @author The MiCode Open Source Community
 * @see TaskNote
 * @see Folder
 */
public abstract class Node {
    
    // ==================== 同步动作常量定义 ====================
    // 
    // 同步动作（SYNC_ACTION）用于标识本地与远程数据之间需要执行的同步操作类型。
    // 这些常量是同步引擎决策的核心依据，决定了数据将如何被处理和传输。
    //
    // 动作流向约定：
    // - REMOTE 结尾的动作：表示需要向远程服务器发起请求
    // - LOCAL 结尾的动作：表示仅需要更新本地数据库
    // - 无后缀的动作（NONE）：表示无需同步，数据已同步完成
    //
    // 使用场景：
    // 同步引擎在检测到本地和远程数据的状态差异后，会根据以下规则设置 sync_action：
    // 1. 如果本地有新数据但远程没有 → ADD_REMOTE 或 ADD_LOCAL
    // 2. 如果远程有新数据但本地没有 → 由远程拉取并创建本地记录
    // 3. 如果两边都有但内容不一致 → UPDATE_REMOTE 或 UPDATE_LOCAL
    // 4. 如果数据被删除 → DELETE_REMOTE 或 DELETE_LOCAL
    // 5. 如果两边修改时间相同 → NONE（无需同步）
    //
    
    /**
     * 同步动作常量：无操作（空动作）
     * 
     * 含义：
     * 表示该节点已经与远程服务器同步完成，不需要执行任何同步操作。
     * 
     * 使用场景：
     * - 当本地数据与远程数据的最后修改时间相同且内容一致时
     * - 当节点刚被成功同步后，引擎会自动将其标记为 NONE
     * - 在执行完 ADD/UPDATE/DELETE 操作后，状态也会重置为 NONE
     * 
     * 注意：
     * NONE 状态并不意味着数据是"新的"，而是表示"已同步，无需处理"。
     * 新创建的数据初始状态通常是 ADD_LOCAL（等待推送到远程）。
     */
    public static final int SYNC_ACTION_NONE = 0;

    /**
     * 同步动作常量：添加（推送到远程）
     * 
     * 含义：
     * 表示本地存在一个新的节点，需要将其创建到远程服务器。
     * 
     * 使用场景：
     * - 用户在本地创建了一条新便签
     * - 该便签在本地有记录（已有 local_id），但在远程没有对应记录（gid = null）
     * - 同步时，系统会调用 getCreateAction() 生成创建请求
     * - 远程创建成功后，服务器会返回该记录的 gid，需要更新到本地
     * 
     * 执行流程：
     * 1. 检测到本地新节点（mGid == null）且 sync_action == ADD_REMOTE
     * 2. 调用 getCreateAction() 生成远程 API 创建请求
     * 3. 发送请求到远程服务器
     * 4. 服务器返回新创建的记录及其 gid
     * 5. 调用 setGid() 更新本地的 gid
     * 6. 将 sync_action 重置为 NONE
     * 
     * 典型示例：
     * - 用户新建便签 → 本地保存 → sync_action = ADD_LOCAL
     * - 同步启动 → 检测到 ADD_LOCAL → 转换为 ADD_REMOTE → 推送到远程
     */
    public static final int SYNC_ACTION_ADD_REMOTE = 1;

    /**
     * 同步动作常量：添加（仅本地）
     * 
     * 含义：
     * 表示本地存在一个新节点，但暂时不需要推送到远程。
     * 
     * 使用场景：
     * - 离线创建的新便签，在网络不可用时先标记为 ADD_LOCAL
     * - 网络恢复后，引擎会将其转换为 ADD_REMOTE 再执行推送
     * - 或者在某些特定场景下，本地创建的数据不需要远程同步
     * 
     * 与 ADD_REMOTE 的区别：
     * - ADD_LOCAL：数据是本地的，最终可能同步也可能不同步
     * - ADD_REMOTE：数据明确需要推送到远程服务器
     */
    public static final int SYNC_ACTION_ADD_LOCAL = 2;

    /**
     * 同步动作常量：删除（从远程删除）
     * 
     * 含义：
     * 表示某个节点已被删除，需要从远程服务器删除对应记录。
     * 
     * 使用场景：
     * - 用户在本地删除了便签（或将便签移到回收站并清空）
     * - 同步时，系统会检测到本地的删除标记（mDeleted = true）
     * - 需要向远程服务器发送删除请求
     * 
     * 执行流程：
     * 1. 用户删除本地便签 → mDeleted = true → sync_action = DELETE_REMOTE
     * 2. 同步时检测到 DELETE_REMOTE 状态
     * 3. 调用远程 API 执行删除操作
     * 4. 删除成功后，从本地数据库中彻底移除该记录
     * 
     * 注意：
     * 一旦 sync_action 被设置为 DELETE_REMOTE，该状态不可更改。
     * 即使后续有其他修改，也不会改变这个删除意图。
     * 这是因为删除是"最终"操作，不应被其他修改覆盖。
     */
    public static final int SYNC_ACTION_DEL_REMOTE = 3;

    /**
     * 同步动作常量：删除（仅本地删除）
     * 
     * 含义：
     * 表示本地节点已被删除，但暂时不需要从远程服务器删除。
     * 
     * 使用场景：
     * - 在离线模式下删除便签，先标记为 DELETE_LOCAL
     * - 网络恢复后将转换为 DELETE_REMOTE 再执行远程删除
     * - 或者本地创建的便签（从未同步到远程）被删除，无需通知服务器
     * 
     * 重要特性：
     * - 如果一个节点在本地创建后从未同步（gid = null），则删除时使用 DELETE_LOCAL
     * - 因为远程根本没有这条记录，不需要发送删除请求
     */
    public static final int SYNC_ACTION_DEL_LOCAL = 4;

    /**
     * 同步动作常量：更新（推送到远程）
     * 
     * 含义：
     * 表示本地节点已有修改，需要将更新同步到远程服务器。
     * 
     * 使用场景：
     * - 用户编辑了便签内容、标题或修改时间发生变化
     * - 系统检测到本地修改时间晚于最后同步时间
     * - 需要将修改推送到远程服务器
     * 
     * 执行流程：
     * 1. 用户修改便签 → 更新 mLastModified 时间戳
     * 2. sync_action 设置为 UPDATE_REMOTE
     * 3. 同步时调用 getUpdateAction() 生成更新请求
     * 4. 发送更新到远程服务器
     * 5. 成功后重置 sync_action 为 NONE
     * 
     * 冲突处理：
     * 如果远程也同时被修改，可能产生冲突。
     * 系统可能会将其标记为 UPDATE_CONFLICT，需要进行冲突解决。
     */
    public static final int SYNC_ACTION_UPDATE_REMOTE = 5;

    /**
     * 同步动作常量：更新（仅本地更新）
     * 
     * 含义：
     * 表示本地节点有修改，但暂时不需要推送到远程。
     * 
     * 使用场景：
     * - 离线模式下修改便签，先标记为 UPDATE_LOCAL
     * - 网络恢复后转换为 UPDATE_REMOTE 再执行推送
     * - 或从远程拉取的更新，仅更新本地数据库
     * 
     * 与 UPDATE_REMOTE 的区别：
     * - UPDATE_LOCAL：表示"已修改但未同步"
     * - UPDATE_REMOTE：表示"即将推送到远程"
     */
    public static final int SYNC_ACTION_UPDATE_LOCAL = 6;
    
    /**
     * 同步动作常量：更新冲突
     * 
     * 含义：
     * 表示本地和远程同时被修改，产生了冲突。
     * 
     * 使用场景：
     * - 用户的便签在本地被修改
     * - 同时，远程服务器上该便签也被其他设备或用户修改
     * - 同步时检测到两边都有更新，需要解决冲突
     * 
     * 冲突解决策略（由具体实现决定）：
     * - "本地优先"：保留本地的修改，覆盖远程
     * - "远程优先"：保留远程的修改，放弃本地修改
     * - "合并"：将两边的修改合并（如标题取最新的，内容拼接等）
     * - "用户选择"：弹出对话框让用户决定保留哪个版本
     */
    public static final int SYNC_ACTION_UPDATE_CONFLICT = 7;
    
    /**
     * 同步动作常量：同步错误
     * 
     * 含义：
     * 表示在同步过程中发生了错误，需要人工干预或重试。
     * 
     * 使用场景：
     * - 网络连接失败
     * - 远程服务器返回错误码
     * - 数据格式解析错误
     * - 权限不足（账号被登出等）
     * 
     * 错误处理：
     * - 系统可以记录错误日志
     * - 通知用户同步失败
     * - 提供重试选项
     * - 或将数据标记为需要修复
     */
    public static final int SYNC_ACTION_ERROR = 8;

    // ==================== 成员变量定义 ====================
    //
    // 这些是所有同步实体共有的基本属性，定义了实体的标识和状态信息。
    //

    /**
     * 全局唯一标识符（Google Task ID）
     * 
     * 用途：
     * mGid 是远程服务器（如 Google Tasks）分配给每个节点的唯一标识符。
     * 它用于在远程端精确定位和操作特定的记录。
     * 
     * 生命周期：
     * - 本地创建时：mGid = null（新节点尚未同步，远程没有对应记录）
     * - 同步创建后：mGid = "远程返回的 gid"（本地节点获得远程标识）
     * - 始终存在：一旦分配，gid 通常不会改变
     * 
     * 使用场景：
     * - 远程更新时，需要通过 gid 找到要更新的记录
     * - 远程删除时，需要通过 gid 找到要删除的记录
     * - 本地查询远程对应记录时，使用 gid 进行匹配
     * 
     * 格式示例：
     * Google Tasks 的 gid 通常是类似 "MDE4OTA3NDM3MDkyNjQ3MzQxMjU6MTI0MDg" 的字符串
     * 
     * @see #getGid()
     * @see #setGid(String)
     */
    private String mGid;

    /**
     * 节点名称（标题）
     * 
     * 用途：
     * 存储节点的可读名称或标题，用于在 UI 中显示和识别节点。
     * 
     * 对于便签（Note）：通常是便签的第一行内容或用户设置的标题
     * 对于文件夹（Folder）：是文件夹的名称
     * 
     * 默认值：
     * 初始化为空字符串 ""
     * 
     * @see #getName()
     * @see #setName(String)
     */
    private String mName;

    /**
     * 最后修改时间戳
     * 
     * 用途：
     * 记录节点最后一次被修改的时间，用于判断数据是否发生变化以及冲突检测。
     * 
     * 格式：
     * Unix 时间戳（毫秒），表示从 1970-01-01 00:00:00 UTC 开始的毫秒数
     * 
     * 使用场景：
     * - 同步决策：比较本地和远程的 mLastModified，判断谁更新
     * - 冲突检测：如果两边都修改过，且修改时间不同步，则可能存在冲突
     * - 排序：按最后修改时间排序便签列表
     * 
     * 注意：
     * 这个时间戳应该是"业务时间"而非"系统时间"。
     * 即应该使用用户实际修改内容的时间，而不是服务器收到请求的时间。
     * 但实际实现中，很多系统直接使用服务器时间作为权威时间。
     * 
     * @see #getLastModified()
     * @see #setLastModified(long)
     */
    private long mLastModified;

    /**
     * 删除标记
     * 
     * 用途：
     * 标记节点是否已被删除。这是一个"软删除"标记，允许在同步前临时标记删除，
     * 而不是立即从数据库中移除记录。
     * 
     * 软删除的好处：
     * - 可以支持"撤销删除"功能
     * - 可以在同步前保留删除意图
     * - 便于记录删除操作的历史
     * 
     * 工作流程：
     * 1. 用户删除便签 → mDeleted = true
     * 2. 同步时检测到 mDeleted = true → 设置 sync_action = DELETE_REMOTE
     * 3. 执行远程删除
     * 4. 删除成功后，从本地数据库彻底移除记录
     * 
     * @see #getDeleted()
     * @see #setDeleted(boolean)
     */
    private boolean mDeleted;

    // ==================== 构造函数 ====================

    /**
     * 默认构造函数
     * 
     * 初始化 Node 的基本属性为默认值：
     * - mGid: null（新节点尚未获得远程标识）
     * - mName: ""（空字符串）
     * - mLastModified: 0（Unix 纪元时间，通常表示"从未修改"或"新建"）
     * - mDeleted: false（默认未删除状态）
     * 
     * 注意：
     * 这是一个 protected 构造函数的变体，实际使用中应该由子类调用。
     * 子类可以在调用 super() 后，初始化自己特有的属性。
     */
    public Node() {
        mGid = null;
        mName = "";
        mLastModified = 0;
        mDeleted = false;
    }

    // ==================== 抽象方法定义 ====================
    //
    // 以下抽象方法定义了同步操作的契约。每个具体的 Node 子类
    // （如 TaskNote、Folder）都需要实现这些方法，以定义：
    // 1. 如何将本地数据转换为远程 API 请求（getCreateAction, getUpdateAction）
    // 2. 如何解析远程返回的数据（setContentByRemoteJSON）
    // 3. 如何进行本地数据的序列化/反序列化（setContentByLocalJSON, getLocalJSONFromContent）
    // 4. 如何根据当前状态判断同步动作（getSyncAction）
    //

    /**
     * 生成创建实体的远程 API 请求
     * 
     * 方法契约：
     * 子类必须实现此方法，根据当前节点的状态生成一个用于在远程服务器上
     * 创建新记录的 JSON 请求体。
     * 
     * 参数说明：
     * @param actionId  远程 API 调用的标识符，用于区分不同的创建操作类型。
     *                  这个 ID 通常由远程 API 定义，用于标识操作的具体意图。
     * 
     * 返回值：
     * 返回一个包含创建请求所有必要数据的 JSONObject。
     * 这个对象会被序列化为 HTTP 请求体发送到远程服务器。
     * 
     * 实现要点：
     * 子类需要将当前节点的所有必要数据（名称、内容、父节点 ID 等）
     * 按远程 API 要求的格式放入 JSONObject 中。
     * 
     * 注意事项：
     * - 不要在请求中包含 gid，gid 由服务器生成
     * - 必须包含父节点 ID，以便在正确的位置创建记录
     * - 需要包含 mLastModified 时间戳
     * 
     * @return JSONObject 包含创建请求数据的 JSON 对象
     * 
     * @see #getUpdateAction(int)
     */
    public abstract JSONObject getCreateAction(int actionId);

    /**
     * 生成更新实体的远程 API 请求
     * 
     * 方法契约：
     * 子类必须实现此方法，生成一个用于更新远程服务器上现有记录的 JSON 请求体。
     * 
     * 与 getCreateAction 的区别：
     * - getCreateAction: 创建新记录（远程没有对应数据）
     * - getUpdateAction: 更新现有记录（远程已有对应数据，需要通过 gid 定位）
     * 
     * 参数说明：
     * @param actionId  远程 API 调用的标识符
     * 
     * 返回值：
     * 返回一个包含更新请求所有必要数据的 JSONObject。
     * 
     * 实现要点：
     * - 必须包含 gid，用于服务器定位要更新的记录
     * - 包含所有可能被修改的字段（即使值没变也要包含，以满足 API 要求）
     * - 包含更新后的 mLastModified 时间戳
     * 
     * @return JSONObject 包含更新请求数据的 JSON 对象
     * 
     * @see #getCreateAction(int)
     */
    public abstract JSONObject getUpdateAction(int actionId);

    /**
     * 从远程 JSON 数据设置节点内容
     * 
     * 方法契约：
     * 解析远程服务器返回的 JSON 数据，并将数据设置到当前节点对象中。
     * 
     * 使用场景：
     * 当从远程服务器拉取数据时（无论是首次同步还是增量同步），
     * 需要将接收到的 JSON 数据解析并填充到本地的 Node 对象中。
     * 
     * 参数说明：
     * @param js  远程服务器返回的 JSONObject，包含节点的完整数据
     * 
     * 需要解析的典型字段：
     * - id: 远程 ID，用于设置 mGid
     * - title: 节点名称，用于设置 mName
     * - updated: 最后修改时间，用于设置 mLastModified
     * - deleted: 删除标记（如果支持）
     * - 子类特有字段：如便签内容、标签等
     * 
     * 注意事项：
     * - 应该验证 JSON 数据的有效性
     * - 应该处理字段缺失的异常情况
     * - 解析后的数据应该与远程保持一致
     * 
     * @see #setContentByLocalJSON(JSONObject)
     */
    public abstract void setContentByRemoteJSON(JSONObject js);

    /**
     * 从本地 JSON 数据设置节点内容
     * 
     * 方法契约：
     * 解析本地存储的 JSON 数据（通常来自 SQLite 数据库的 JSON 字段），
     * 并将数据设置到当前节点对象中。
     * 
     * 与 setContentByRemoteJSON 的区别：
     * - setContentByRemoteJSON: 解析来自远程服务器的数据
     * - setContentByLocalJSON: 解析本地数据库存储的数据
     * 
     * 使用场景：
     * 从本地数据库读取节点数据并恢复到 Node 对象时调用。
     * 本地 JSON 格式可能与远程 JSON 格式不同，是应用自定义的存储格式。
     * 
     * 参数说明：
     * @param js  本地数据库中的 JSONObject
     * 
     * 注意事项：
     * - 本地 JSON 格式由应用自行定义，可能包含远程数据不关心的字段
     * - 可能包含一些临时状态信息（如同步动作）
     * - 应该只解析"内容"相关字段，不应影响 mGid、mName 等基本属性
     * 
     * @see #setContentByRemoteJSON(JSONObject)
     * @see #getLocalJSONFromContent()
     */
    public abstract void setContentByLocalJSON(JSONObject js);

    /**
     * 将节点内容序列化为本地 JSON 格式
     * 
     * 方法契约：
     * 将当前节点的内容数据序列化为一个 JSONObject，用于存储到本地数据库。
     * 
     * 使用场景：
     * - 将 Node 对象保存到本地 SQLite 数据库时
     * - 需要将对象的各个字段打包成一个 JSON 字符串存储
     * 
     * 返回值：
     * 返回一个包含节点所有内容字段的 JSONObject。
     * 这个对象会被转换为 JSON 字符串存入数据库的 TEXT 类型字段。
     * 
     * 与 getCreateAction/getUpdateAction 的区别：
     * - get*Action: 生成发送到远程服务器的请求（远程 API 格式）
     * - getLocalJSONFromContent: 生成存储到本地数据库的数据（本地格式）
     * 
     * 应该包含的字段：
     * - 子类的所有内容字段（如便签的正文内容）
     * - 不需要包含 gid、name、lastModified 等已在父类定义的字段
     *   （这些字段通常单独存储在数据库的对应列中）
     * 
     * @return JSONObject 包含节点内容数据的 JSON 对象
     * 
     * @see #setContentByLocalJSON(JSONObject)
     */
    public abstract JSONObject getLocalJSONFromContent();

    /**
     * 根据游标数据判断同步动作
     * 
     * 方法契约：
     * 根据传入的数据库游标中包含的节点信息，判断当前节点应该执行哪种同步操作。
     * 这个方法是同步引擎决策逻辑的核心，由具体子类根据自身业务规则实现。
     * 
     * 参数说明：
     * @param c  包含节点数据的数据库 Cursor。
     *          Cursor 应该已经定位到当前节点对应的行。
     * 
     * 返回值：
     * 返回应该执行的同步动作类型（SYNC_ACTION_* 常量之一）。
     * 
     * 决策逻辑（一般规则）：
     * 1. 检查 sync_action 字段（如果存在）：
     *    - 如果是 DELETE_*，直接返回（删除不可逆）
     *    - 如果是 NONE/ERROR/CONFLICT，按以下规则重新判断
     * 
     * 2. 比较本地和远程的状态：
     *    - 本地有、远程没有 → ADD_REMOTE
     *    - 本地无、远程有 → DELETE_LOCAL（本地数据已不存在）
     *    - 都有但不一致 → UPDATE_REMOTE
     *    - 完全一致 → NONE
     * 
     * 3. 特殊检查：
     *    - mDeleted = true → DELETE_REMOTE 或 DELETE_LOCAL
     *    - mGid = null → 新节点，需要创建
     * 
     * 注意事项：
     * - 这个方法应该幂等：多次调用只要输入不变，结果应该一致
     * - 应该考虑时间戳的容差（比如 1 秒内的差异视为同时修改）
     * - 冲突检测应优先于普通更新判断
     * 
     * @return int 同步动作类型
     */
    public abstract int getSyncAction(Cursor c);

    // ==================== Getter 和 Setter 方法 ====================
    //
    // 提供对成员变量的访问和修改能力。
    // 大部分方法没有太多逻辑，只是简单的赋值和返回值。
    // 这里提供详细注释的是需要特别说明的方法。
    //

    /**
     * 设置全局唯一标识符
     * 
     * 将远程服务器分配的 gid 设置到当前节点。
     * 通常在同步创建成功后调用，将远程返回的 id 记录到本地。
     * 
     * @param gid  远程服务器分配的唯一标识符
     * 
     * @see #getGid()
     */
    public void setGid(String gid) {
        this.mGid = gid;
    }

    /**
     * 设置节点名称
     * 
     * 更新节点的名称（标题）字段。
     * 
     * @param name  新的节点名称
     * 
     * @see #getName()
     */
    public void setName(String name) {
        this.mName = name;
    }

    /**
     * 设置最后修改时间
     * 
     * 更新节点的最后修改时间戳。
     * 通常在节点内容发生变化时自动更新。
     * 
     * @param lastModified  新的修改时间戳（Unix 毫秒）
     * 
     * @see #getLastModified()
     */
    public void setLastModified(long lastModified) {
        this.mLastModified = lastModified;
    }

    /**
     * 设置删除标记
     * 
     * 将节点标记为已删除或未删除状态。
     * 这是一个软删除标记，不会在调用时立即删除数据。
     * 
     * 设置为 true 后，同步引擎会：
     * 1. 将 sync_action 设置为 DELETE_REMOTE 或 DELETE_LOCAL
     * 2. 在同步成功后从数据库中彻底移除记录
     * 
     * @param deleted  true 表示已删除，false 表示未删除
     * 
     * @see #getDeleted()
     * @see #SYNC_ACTION_DEL_REMOTE
     * @see #SYNC_ACTION_DEL_LOCAL
     */
    public void setDeleted(boolean deleted) {
        this.mDeleted = deleted;
    }

    /**
     * 获取全局唯一标识符
     * 
     * 返回当前节点的远程唯一标识符。
     * 
     * 返回值说明：
     * - null: 节点尚未同步到远程（新节点）
     * - 非 null: 节点已在远程创建，有有效的 gid
     * 
     * @return String 远程唯一标识符，可能为 null
     * 
     * @see #setGid(String)
     */
    public String getGid() {
        return this.mGid;
    }

    /**
     * 获取节点名称
     * 
     * 返回当前节点的名称（标题）。
     * 
     * @return String 节点名称，不会为 null（默认为空字符串）
     * 
     * @see #setName(String)
     */
    public String getName() {
        return this.mName;
    }

    /**
     * 获取最后修改时间
     * 
     * 返回节点最后一次被修改的时间戳。
     * 
     * @return long 最后修改时间（Unix 毫秒），0 表示未知或从未修改
     * 
     * @see #setLastModified(long)
     */
    public long getLastModified() {
        return this.mLastModified;
    }

    /**
     * 获取删除标记
     * 
     * 返回节点是否被标记为删除。
     * 
     * @return boolean true 表示已删除，false 表示未删除
     * 
     * @see #setDeleted(boolean)
     */
    public boolean getDeleted() {
        return this.mDeleted;
    }

}
