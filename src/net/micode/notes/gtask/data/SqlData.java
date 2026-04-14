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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.NotesDatabaseHelper.TABLE;
import net.micode.notes.gtask.exception.ActionFailureException;

import org.json.JSONException;
import org.json.JSONObject;


/**
 * SqlData 类 - 本地笔记内容数据的 SQL 包装器
 * 
 * 【设计意图】
 * SqlData 是 GTask 同步模块中负责管理"笔记内容数据"的类。
 * 在小米便签的数据模型中，一条笔记(Note)由两部分组成：
 *   1. 笔记元数据 (SqlNote) - 如标题、创建时间、修改时间、背景颜色等
 *   2. 笔记内容数据 (SqlData) - 如正文内容、摘要等实际数据
 * 
 * SqlData 专门负责处理后者，即"data"表中的一条记录。
 * 
 * 【与 SqlNote 的关系】
 * SqlNote 和 SqlData 是"一对多"的关系：
 * - 一条 SqlNote（笔记）可以包含多个 SqlData（内容块）
 * - 这种设计支持笔记的多种内容类型：
 *   - 纯文本内容块（主要类型）
 *   - 列表/待办事项内容块
 *   - 其他扩展类型
 * - 在代码中，SqlNote 持有 ArrayList<SqlData> mDataList 来管理这些内容块
 * - 当保存笔记时，会先保存 SqlNote，然后依次调用每个 SqlData.commit()
 * 
 * 【ContentValues diff 追踪模式】
 * 与 SqlNote 类似，SqlData 也采用 diff（差异）追踪模式：
 * - 成员变量 mDiffDataValues 用于存储"已变更"的字段
 * - 只有当字段的实际值发生变化时，才会被放入 mDiffDataValues
 * - commit() 时只将 mDiffDataValues 中的数据写入数据库
 * - 这种方式实现了增量更新，避免全量更新带来的性能开销
 * - 新建时（mIsCreate=true）所有非默认值的字段都会被记录
 * 
 * 【数据表结构】
 * SqlData 操作的是 Notes 应用的数据表中的 "data" 表，
 * 主要字段包括：
 * - _id: 数据行唯一ID
 * - note_id: 所属笔记的ID（外键）
 * - mime_type: 数据类型（如 "vnd.android.cursor.item/vnd.miui.note"）
 * - content: 实际内容文本（笔记正文）
 * - data1: 扩展字段1（用途根据 mime_type 而定）
 * - data3: 扩展字段3（用途根据 mime_type 而定）
 * 
 * 【JSON 序列化用途】
 * SqlData 的 getContent() 和 setContent() 方法用于：
 * - 将本地数据转换为 JSON 格式，与 GTask（Google Task）服务器同步
 * - JSON 结构便于网络传输和跨平台数据交换
 * - 同步时会将笔记的 SqlData 数据序列化为 JSON，传输后再反序列化
 */
public class SqlData {
    /** 日志标签，用于调试输出 */
    private static final String TAG = SqlData.class.getSimpleName();

    /**
     * 无效ID的标记值
     * 用于区分"尚未分配ID"和"ID为0"的情况
     * 新建 SqlData 时 mDataId 初始化为此值，commit() 成功后会获得真实ID
     */
    private static final int INVALID_ID = -99999;

    /**
     * 查询 data 表时需要读取的列名数组
     * 
     * 【字段说明】
     * - ID: 数据行唯一标识符 (_id)
     * - MIME_TYPE: 数据的 MIME 类型，用于区分不同的内容格式
     * - CONTENT: 数据的主要内容（对于笔记来说就是正文）
     * - DATA1: 扩展字段1（根据 MIME_TYPE 的不同有不同的用途）
     * - DATA3: 扩展字段3（用于存储额外的元数据）
     * 
     * 【为什么只查询这些列？】
     * 这是为了优化性能 - 只读取必要的数据列，减少数据库 I/O 和内存占用
     */
    public static final String[] PROJECTION_DATA = new String[] {
            DataColumns.ID, DataColumns.MIME_TYPE, DataColumns.CONTENT, DataColumns.DATA1,
            DataColumns.DATA3
    };

    /** Cursor 中 ID 列的索引位置（用于快速从 Cursor 中获取数据） */
    public static final int DATA_ID_COLUMN = 0;

    /** Cursor 中 MIME_TYPE 列的索引位置 */
    public static final int DATA_MIME_TYPE_COLUMN = 1;

    /** Cursor 中 CONTENT 列的索引位置 */
    public static final int DATA_CONTENT_COLUMN = 2;

    /** Cursor 中 DATA1 列的索引位置 */
    public static final int DATA_CONTENT_DATA_1_COLUMN = 3;

    /** Cursor 中 DATA3 列的索引位置 */
    public static final int DATA_CONTENT_DATA_3_COLUMN = 4;

    /** Android 的 ContentResolver，用于与 ContentProvider 交互（查询/插入/更新/删除） */
    private ContentResolver mContentResolver;

    /**
     * 是否为新建数据的标记
     * - true: 通过无参构造函数 SqlData(Context) 创建，表示新数据，需要执行 INSERT
     * - false: 通过 Cursor 构造函数 SqlData(Context, Cursor) 加载，表示已存在的数据，需要执行 UPDATE
     * 
     * 这个标记决定了 commit() 方法的行为：
     * - mIsCreate=true 时：执行插入操作（INSERT）
     * - mIsCreate=false 时：执行更新操作（UPDATE）
     */
    private boolean mIsCreate;

    /** 数据在 data 表中的唯一ID（_id），新建时为 INVALID_ID，commit 后获得真实ID */
    private long mDataId;

    /** 数据的 MIME 类型，标识数据的格式和用途，默认为 DataConstants.NOTE */
    private String mDataMimeType;

    /**
     * 数据的主要内容
     * 对于普通笔记类型，这是笔记的正文内容
     * 可以是富文本、纯文本或根据 MIME_TYPE 决定的其他格式
     */
    private String mDataContent;

    /**
     * 扩展数据字段1
     * 具体用途取决于 mime_type：
     * - 对于某些类型可能存储额外标识
     * - 对于待办事项可能存储完成状态
     */
    private long mDataContentData1;

    /**
     * 扩展数据字段3
     * 具体用途取决于 mime_type：
     * - 可以存储路径、URL 或其他字符串数据
     * - 对于笔记类型通常为空字符串
     */
    private String mDataContentData3;

    /**
     * 【核心设计】ContentValues diff 追踪器
     * 
     * diff 追踪模式的工作原理：
     * 1. 当调用 setContent() 方法从 JSON 设置数据时，只有当新值与旧值不同时，才将字段放入 mDiffDataValues
     * 2. commit() 时只将 mDiffDataValues 中的字段写入数据库，实现增量更新
     * 3. 新建 SqlData 时（mIsCreate=true），所有非默认值的字段都会被放入
     * 4. 这种方式避免了全量更新，只执行必要的 SQL UPDATE 操作，提升性能
     * 
     * 【与 SqlNote 的 mDiffNoteValues 的关系】
     * - SqlNote 的 mDiffNoteValues 存储笔记元数据的变更
     * - SqlData 的 mDiffDataValues 存储内容数据的变更
     * - 两者在各自的 commit() 中独立使用
     */
    private ContentValues mDiffDataValues;

    /**
     * 构造函数 - 创建新的 SqlData 对象
     * 
     * 【使用场景】
     * 当需要新建一条笔记内容数据时使用此构造函数。
     * 例如：从 GTask 服务器同步新的笔记内容时。
     * 
     * 【初始化状态】
     * - mIsCreate = true：标记为新建状态，commit() 时将执行 INSERT
     * - mDataId = INVALID_ID：尚未分配真实ID
     * - mDataMimeType = DataConstants.NOTE：默认 MIME 类型为笔记
     * - mDataContent = ""：空内容
     * - mDataContentData1 = 0：扩展字段1默认为0
     * - mDataContentData3 = ""：扩展字段3默认为空字符串
     * - mDiffDataValues = new ContentValues()：空的 diff 追踪器
     * 
     * @param context Android 上下文，用于获取 ContentResolver
     */
    public SqlData(Context context) {
        mContentResolver = context.getContentResolver();
        mIsCreate = true;
        mDataId = INVALID_ID;
        mDataMimeType = DataConstants.NOTE;
        mDataContent = "";
        mDataContentData1 = 0;
        mDataContentData3 = "";
        mDiffDataValues = new ContentValues();
    }

    /**
     * 构造函数 - 从数据库 Cursor 加载现有的 SqlData 对象
     * 
     * 【使用场景】
     * 当需要编辑/修改现有笔记内容时使用此构造函数。
     * 例如：用户打开一条现有笔记进行编辑。
     * 
     * 【加载流程】
     * 1. 从传入的 Cursor 中读取各列数据
     * 2. 调用 loadFromCursor() 将数据加载到成员变量
     * 3. 设置 mIsCreate = false，标记为已存在的数据
     * 
     * 【参数说明】
     * @param context Android 上下文
     * @param c 包含 data 表记录的数据库 Cursor，必须已定位到有效的数据行
     */
    public SqlData(Context context, Cursor c) {
        mContentResolver = context.getContentResolver();
        mIsCreate = false;
        loadFromCursor(c);
        mDiffDataValues = new ContentValues();
    }

    /**
     * 从 Cursor 中加载数据到成员变量
     * 
     * 【执行流程】
     * 1. 从 Cursor 的 DATA_ID_COLUMN（0）位置读取 _id 字段
     * 2. 从 Cursor 的 DATA_MIME_TYPE_COLUMN（1）位置读取 mime_type 字段
     * 3. 从 Cursor 的 DATA_CONTENT_COLUMN（2）位置读取 content 字段
     * 4. 从 Cursor 的 DATA_CONTENT_DATA_1_COLUMN（3）位置读取 data1 字段
     * 5. 从 Cursor 的 DATA_CONTENT_DATA_3_COLUMN（4）位置读取 data3 字段
     * 
     * 【注意事项】
     * - Cursor 必须已定位到有效的数据行（通常调用 moveToNext() 之后）
     * - 此方法不关闭 Cursor，由调用方负责管理
     * 
     * @param c 已定位到数据行的数据库 Cursor
     */
    private void loadFromCursor(Cursor c) {
        mDataId = c.getLong(DATA_ID_COLUMN);
        mDataMimeType = c.getString(DATA_MIME_TYPE_COLUMN);
        mDataContent = c.getString(DATA_CONTENT_COLUMN);
        mDataContentData1 = c.getLong(DATA_CONTENT_DATA_1_COLUMN);
        mDataContentData3 = c.getString(DATA_CONTENT_DATA_3_COLUMN);
    }

    /**
     * 从 JSON 对象设置内容数据
     * 
     * 【功能说明】
     * 将传入的 JSON 对象中的数据解析并设置到当前 SqlData 对象中。
     * 这是从 GTask 同步数据到本地的主要入口。
     * 
     * 【JSON 数据格式】
     * 期望的 JSON 结构示例：
     * {
     *   "_id": 12345,
     *   "mime_type": "vnd.android.cursor.item/vnd.miui.note",
     *   "content": "这是笔记的正文内容",
     *   "data1": 0,
     *   "data3": ""
     * }
     * 
     * 【diff 追踪逻辑】
     * 对于每个字段的处理流程：
     * 1. 先从 JSON 中获取值（如果字段不存在则使用默认值）
     * 2. 判断是否需要记录变更：
     *    - 如果是新建对象（mIsCreate=true），则无条件记录
     *    - 如果是已存在的对象（mIsCreate=false），则比较新旧值，只有值不同时才记录
     * 3. 将需要变更的字段放入 mDiffDataValues
     * 4. 更新对应的成员变量
     * 
     * 【字段处理详解】
     * - ID: 数据行ID，如果与当前不同则记录到 diff
     * - MIME_TYPE: 数据类型，如果与当前不同则记录到 diff
     * - CONTENT: 主要内容，如果与当前不同则记录到 diff
     * - DATA1: 扩展字段1（长整型），如果与当前不同则记录到 diff
     * - DATA3: 扩展字段3（字符串），如果与当前不同则记录到 diff
     * 
     * 【关于 ID 字段的特殊处理】
     * 对于新建对象（mIsCreate=true），如果设置的 ID 仍然是 INVALID_ID，
     * 则不会将此字段放入 mDiffDataValues，因为新数据不应该带有预设ID。
     * 真正的 ID 会在 commit() 插入数据库后由数据库自动生成。
     * 
     * @param js 包含数据字段的 JSON 对象
     * @throws JSONException 如果 JSON 解析失败
     */
    public void setContent(JSONObject js) throws JSONException {
        // ---------- ID 字段处理 ----------
        // 从 JSON 获取 ID，如果不存在则使用 INVALID_ID 作为默认值
        long dataId = js.has(DataColumns.ID) ? js.getLong(DataColumns.ID) : INVALID_ID;
        // 判断是否需要记录变更：
        // - 新建对象：需要记录（因为还没有数据库ID）
        // - 已有对象：只有当 ID 发生变化时才记录
        if (mIsCreate || mDataId != dataId) {
            mDiffDataValues.put(DataColumns.ID, dataId);
        }
        mDataId = dataId;

        // ---------- MIME_TYPE 字段处理 ----------
        // 从 JSON 获取 MIME 类型，如果不存在则默认为 NOTE 类型
        String dataMimeType = js.has(DataColumns.MIME_TYPE) ? js.getString(DataColumns.MIME_TYPE)
                : DataConstants.NOTE;
        // 只有值发生变化时才记录到 diff（对于新建对象总是记录）
        if (mIsCreate || !mDataMimeType.equals(dataMimeType)) {
            mDiffDataValues.put(DataColumns.MIME_TYPE, dataMimeType);
        }
        mDataMimeType = dataMimeType;

        // ---------- CONTENT 字段处理 ----------
        // 从 JSON 获取主要内容，如果不存在则默认为空字符串
        String dataContent = js.has(DataColumns.CONTENT) ? js.getString(DataColumns.CONTENT) : "";
        // 只有内容发生变化时才记录到 diff
        if (mIsCreate || !mDataContent.equals(dataContent)) {
            mDiffDataValues.put(DataColumns.CONTENT, dataContent);
        }
        mDataContent = dataContent;

        // ---------- DATA1 字段处理 ----------
        // 从 JSON 获取扩展字段1，如果不存在则默认为 0
        long dataContentData1 = js.has(DataColumns.DATA1) ? js.getLong(DataColumns.DATA1) : 0;
        // 只有值发生变化时才记录到 diff
        if (mIsCreate || mDataContentData1 != dataContentData1) {
            mDiffDataValues.put(DataColumns.DATA1, dataContentData1);
        }
        mDataContentData1 = dataContentData1;

        // ---------- DATA3 字段处理 ----------
        // 从 JSON 获取扩展字段3，如果不存在则默认为空字符串
        String dataContentData3 = js.has(DataColumns.DATA3) ? js.getString(DataColumns.DATA3) : "";
        // 只有值发生变化时才记录到 diff
        if (mIsCreate || !mDataContentData3.equals(dataContentData3)) {
            mDiffDataValues.put(DataColumns.DATA3, dataContentData3);
        }
        mDataContentData3 = dataContentData3;
    }

    /**
     * 获取当前内容数据的 JSON 表示
     * 
     * 【功能说明】
     * 将当前 SqlData 对象的内存数据序列化为 JSON 对象。
     * 这是将本地数据转换为 GTask 同步格式的主要出口。
     * 
     * 【JSON 输出格式】
     * 返回的 JSON 结构示例：
     * {
     *   "_id": 12345,
     *   "mime_type": "vnd.android.cursor.item/vnd.miui.note",
     *   "content": "这是笔记的正文内容",
     *   "data1": 0,
     *   "data3": ""
     * }
     * 
     * 【使用场景】
     * - 在与 GTask 服务器同步时，将本地数据序列化为 JSON 发送
     * - 在创建新笔记时获取默认内容结构
     * - 用于数据导出或备份
     * 
     * 【返回值说明】
     * - 如果当前是新建对象（mIsCreate=true），返回 null 并记录错误日志
     *   原因：新建对象尚未写入数据库，没有有效的 ID
     * - 如果是已有对象，返回包含所有字段的 JSONObject
     * 
     * 【注意事项】
     * 此方法直接读取内存中的成员变量，不会触发数据库操作。
     * 如果需要从数据库重新加载数据，应该先调用 loadFromCursor()。
     * 
     * @return 包含当前数据的 JSONObject，如果尚未创建则返回 null
     * @throws JSONException 如果构建 JSON 时发生错误（通常不会发生）
     */
    public JSONObject getContent() throws JSONException {
        // 检查是否为新建对象
        if (mIsCreate) {
            // 新建对象没有数据库 ID，无法生成有效的 JSON 表示
            Log.e(TAG, "it seems that we haven't created this in database yet");
            return null;
        }
        
        // 创建新的 JSON 对象
        JSONObject js = new JSONObject();
        
        // 将各个字段放入 JSON 对象
        js.put(DataColumns.ID, mDataId);              // 数据行ID
        js.put(DataColumns.MIME_TYPE, mDataMimeType); // MIME 类型
        js.put(DataColumns.CONTENT, mDataContent);   // 主要内容
        js.put(DataColumns.DATA1, mDataContentData1);  // 扩展字段1
        js.put(DataColumns.DATA3, mDataContentData3); // 扩展字段3
        
        return js;
    }

    /**
     * 提交数据到数据库
     * 
     * 【功能说明】
     * 将当前 SqlData 对象的数据写入 ContentProvider。
     * 这是将内存数据持久化到 SQLite 数据库的唯一入口。
     * 
     * 【执行流程】
     * 根据 mIsCreate 标记，分为两种操作：
     * 
     * 【流程A：新建数据（mIsCreate=true）】
     * 1. 如果 ID 仍为 INVALID_ID，则从 mDiffDataValues 中移除 ID 字段
     *    （因为新建数据不应该预设 ID，数据库会自己生成）
     * 2. 将所属笔记的 noteId 添加到 mDiffDataValues
     * 3. 调用 mContentResolver.insert() 执行插入操作
     * 4. 从返回的 Uri 中解析出新记录的数据库 ID
     * 5. 如果解析失败，抛出 ActionFailureException
     * 
     * 【流程B：更新数据（mIsCreate=false）】
     * 1. 检查 mDiffDataValues 是否有变更内容（size > 0）
     * 2. 如果有变更，根据 validateVersion 参数选择更新方式：
     *    - validateVersion=false：直接更新，不做版本检查
     *    - validateVersion=true：带版本检查的更新（乐观锁）
     *      WHERE 条件：note_id = ? AND version <= ?
     *      这样可以防止在同步过程中被其他操作覆盖
     * 3. 如果更新结果为 0，记录警告日志（可能在同步期间被用户修改了）
     * 
     * 【参数说明】
     * @param noteId 所属笔记的 ID，这是必需的字段，用于建立 data 与 note 的关联
     * @param validateVersion 是否进行版本验证
     *        - false：直接更新，用于普通编辑操作
     *        - true：检查版本号，用于同步场景，防止并发冲突
     * @param version 当前笔记的版本号（仅在 validateVersion=true 时使用）
     *        用于乐观锁检查，确保没有其他操作在此期间修改了数据
     * 
     * 【关于 noteId 参数的重要性】
     * noteId 是将 data 记录与 note 记录关联起来的外键。
     * 在 data 表中，每个 SqlData 都必须有一个对应的 note_id。
     * 这个 ID 由调用方（通常是 SqlNote.commit()）传入。
     * 
     * 【关于版本验证（validateVersion）】
     * 这是乐观锁的实现方式：
     * - 假设大多数时候不会有并发修改
     * - 在更新时检查版本号是否符合预期
     * - 如果版本号不匹配，说明数据在此期间被其他操作修改了
     * - 这种情况下不执行更新，避免覆盖其他操作的修改
     * 
     * 【mDiffDataValues 清空时机】
     * commit() 执行完成后：
     * 1. 调用 mDiffDataValues.clear() 清空差异记录
     * 2. 设置 mIsCreate = false
     * 这样下次再调用 setContent() 时，会正确地追踪新的变更
     */
    public void commit(long noteId, boolean validateVersion, long version) {

        // ========== 分支A：新建数据（INSERT） ==========
        if (mIsCreate) {
            // 对于新建数据，如果 ID 仍然是 INVALID_ID，说明还没有设置有效 ID
            // 此时应该从 diff 中移除这个字段，让数据库自动生成 ID
            if (mDataId == INVALID_ID && mDiffDataValues.containsKey(DataColumns.ID)) {
                mDiffDataValues.remove(DataColumns.ID);
            }

            // 【重要】将所属笔记的 ID 添加到数据中
            // 这是建立 data 表记录与 note 表记录关联的关键
            mDiffDataValues.put(DataColumns.NOTE_ID, noteId);
            
            // 执行插入操作
            Uri uri = mContentResolver.insert(Notes.CONTENT_DATA_URI, mDiffDataValues);
            
            // 从返回的 Uri 中解析出新建记录的数据库 ID
            // Uri 格式示例：content://net.micode.notes.provider/data/12345
            // getPathSegments() 返回：["data", "12345"]
            // 我们需要的是第二个段落（索引1）—— 即新记录的 ID
            try {
                mDataId = Long.valueOf(uri.getPathSegments().get(1));
            } catch (NumberFormatException e) {
                // 如果无法解析 ID，说明插入操作可能失败了
                Log.e(TAG, "Get note id error :" + e.toString());
                throw new ActionFailureException("create note failed");
            }
        } 
        // ========== 分支B：更新数据（UPDATE） ==========
        else {
            // 只有当有变更内容时才执行更新
            if (mDiffDataValues.size() > 0) {
                int result = 0;  // 受影响的行数
                
                if (!validateVersion) {
                    // 【无版本验证的更新】
                    // 直接根据 dataId 更新对应记录
                    // ContentUris.withAppendedId() 用于构建带 ID 的 Uri
                    result = mContentResolver.update(ContentUris.withAppendedId(
                            Notes.CONTENT_DATA_URI, mDataId), mDiffDataValues, null, null);
                } else {
                    // 【带版本验证的更新】
                    // WHERE 条件：_id = ? AND note_id IN (SELECT _id FROM note WHERE version = ?)
                    // 这样可以确保只有当笔记版本匹配时才执行更新
                    result = mContentResolver.update(ContentUris.withAppendedId(
                            Notes.CONTENT_DATA_URI, mDataId), mDiffDataValues,
                            " ? in (SELECT " + NoteColumns.ID + " FROM " + TABLE.NOTE
                                    + " WHERE " + NoteColumns.VERSION + "=?)", new String[] {
                                    String.valueOf(noteId), String.valueOf(version)
                            });
                }
                
                // 检查更新结果
                if (result == 0) {
                    // 更新行数为 0，可能的原因：
                    // 1. 数据不存在（不太可能，因为我们是从数据库加载的）
                    // 2. 在同步期间，用户修改了笔记，导致版本号不匹配
                    Log.w(TAG, "there is no update. maybe user updates note when syncing");
                }
            }
        }

        // 【重要】提交完成后的清理工作
        // 1. 清空 diff 追踪器，准备记录下一轮变更
        mDiffDataValues.clear();
        // 2. 将创建标记设为 false，之后再修改数据时将执行 UPDATE 而非 INSERT
        mIsCreate = false;
    }

    /**
     * 获取数据行 ID
     * 
     * 【返回值说明】
     * - 新建对象（未 commit）：返回 INVALID_ID（-99999）
     * - 已提交对象：返回数据库中的真实 _id
     * 
     * 【使用场景】
     * - 在构建 ContentUri 时需要 ID
     * - 在调试时查看数据状态
     * - 在 SqlNote 中查找对应的 SqlData
     * 
     * @return 数据行的唯一标识符
     */
    public long getId() {
        return mDataId;
    }
}
