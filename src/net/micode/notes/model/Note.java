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

package net.micode.notes.model;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.TextNote;

import java.util.ArrayList;


/**
 * Note 类：笔记数据模型，管理笔记元数据变更
 * 
 * 【类设计概述】
 * Note 类是小米便签应用中笔记数据模型的核心类，采用"差量更新"(Diff)模式来高效管理笔记的变更。
 * 该类将笔记数据分为两个部分：
 *   1. 笔记元数据（Note Columns）：存储在 notes 表中，包括创建时间、修改时间、所属文件夹等
 *   2. 笔记内容数据（Data Columns）：存储在 notes_data 表中，包括文本内容和通话记录等
 * 
 * 【Diff 追踪模式说明】
 * 本类采用 ContentValues Diff 追踪机制来记录变更：
 * - mNoteDiffValues: 记录笔记元数据的变更（增量数据）
 * - mNoteData: 内部类，管理内容数据的变更
 * 
 * 当调用 setNoteValue() 或 setTextData()/setCallData() 等方法时，实际上是将变更存入
 * ContentValues 中，而不是立即写入数据库。直到调用 syncNote() 方法时，才会将所有
 * 变更批量同步到 ContentProvider。
 * 
 * 这种设计的好处：
 * 1. 减少数据库 IO 操作，提高性能
 * 2. 支持批量操作，原子性更强
 * 3. 便于实现撤销/重做功能
 * 
 * 【Note 类与 NoteData 内部类的关系】
 * 
 *            ┌─────────────────────────────────────────────────────────┐
 *            │                         Note 类                         │
 *            │  ┌───────────────────────────────────────────────────┐  │
 *            │  │              mNoteDiffValues (ContentValues)        │  │
 *            │  │              笔记元数据变更差量                      │  │
 *            │  │  - CREATED_DATE (创建时间)                          │  │
 *            │  │  - MODIFIED_DATE (修改时间)                         │  │
 *            │  │  - TYPE (笔记类型)                                  │  │
 *            │  │  - LOCAL_MODIFIED (本地修改标志)                    │  │
 *            │  │  - PARENT_ID (所属文件夹ID)                         │  │
 *            │  └───────────────────────────────────────────────────┘  │
 *            │                           │                              │
 *            │                           ▼                              │
 *            │  ┌───────────────────────────────────────────────────┐  │
 *            │  │                    NoteData 类                    │  │
 *            │  │              笔记内容数据管理                       │  │
 *            │  │                                                    │  │
 *            │  │  ┌─────────────────┐    ┌─────────────────┐        │  │
 *            │  │  │ mTextDataValues │    │ mCallDataValues │        │  │
 *            │  │  │ 文本内容变更     │    │ 通话记录变更     │        │  │
 *            │  │  └─────────────────┘    └─────────────────┘        │  │
 *            │  └───────────────────────────────────────────────────┘  │
 *            └─────────────────────────────────────────────────────────┘
 * 
 * 数据存储位置：
 * - 笔记元数据 → notes 表 (通过 Notes.CONTENT_NOTE_URI 访问)
 * - 文本内容   → notes_data 表 (MIME_TYPE = TextNote.CONTENT_ITEM_TYPE)
 * - 通话记录   → notes_data 表 (MIME_TYPE = CallNote.CONTENT_ITEM_TYPE)
 * 
 * @see NoteData 内部类
 * @see Notes 字段定义常量
 */
public class Note {
    
    /** 日志标签 */
    private static final String TAG = "Note";
    
    /**
     * mNoteDiffValues：笔记元数据的变更差量
     * 
     * 这是一个"脏数据"容器，用于记录笔记元数据的变更。
     * 当调用 setNoteValue() 方法时，新的键值对会被加入到这个 ContentValues 中。
     * 
     * 工作原理（Diff 追踪模式）：
     * 1. 用户修改笔记元数据 → setNoteValue() 被调用
     * 2. 变更被记录到 mNoteDiffValues（而不是直接写入数据库）
     * 3. 系统自动添加 LOCAL_MODIFIED=1 和 MODIFIED_DATE（记录修改时间）
     * 4. 调用 syncNote() 时，mNoteDiffValues 中的所有变更被批量写入数据库
     * 5. 写入成功后，调用 clear() 清空差量数据
     * 
     * 为什么要这样做？
     * - 避免频繁的数据库写入操作
     * - 支持批量更新，提高事务性能
     * - 便于实现数据回滚和冲突检测
     */
    private ContentValues mNoteDiffValues;
    
    /**
     * mNoteData：笔记内容数据管理器（NoteData 内部类实例）
     * 
     * NoteData 负责管理笔记的实际内容数据：
     * - 文本内容（TextNote）
     * - 通话记录（CallNote）
     * 
     * 注意：内容数据与元数据分开管理，通过 mNoteData 代理操作。
     * 这样设计可以将笔记的结构信息（meta）和内容信息（data）解耦。
     */
    private NoteData mNoteData;

    /**
     * 创建新笔记ID：在数据库中创建新笔记记录
     * 
     * 【功能说明】
     * 这是一个静态同步方法，用于在数据库中创建一个全新的笔记记录，
     * 并返回新创建的笔记ID。这个方法主要在以下场景使用：
     * 1. 用户点击"新建笔记"按钮时
     * 2. 从通话记录创建笔记时
     * 3. 其他需要创建新笔记的场景
     * 
     * 【创建过程】
     * 1. 构建 ContentValues，包含笔记的初始元数据
     * 2. 调用 ContentResolver.insert() 插入数据库
     * 3. 从返回的 URI 中解析出新笔记的 ID
     * 
     * 【参数说明】
     * @param context   上下文对象，用于访问 ContentResolver
     * @param folderId   新笔记所属的文件夹ID
     * 
     * 【返回值】
     * @return 新创建的笔记ID（大于0），如果创建失败则返回0
     * 
     * 【初始数据设置】
     * 创建新笔记时，系统会自动设置以下字段：
     * - CREATED_DATE：笔记创建时间（当前系统时间）
     * - MODIFIED_DATE：笔记修改时间（初始与创建时间相同）
     * - TYPE：笔记类型（TYPE_NOTE，表示普通笔记）
     * - LOCAL_MODIFIED：本地修改标志（设为1，表示有新笔记）
     * - PARENT_ID：所属文件夹ID
     * 
     * 【注意事项】
     * - 此方法是同步方法，可能会有数据库IO延迟
     * - 使用 synchronized 保证多线程安全
     * - 抛出的异常：
     *   - IllegalStateException：如果返回的 noteId 为 -1（错误值）
     */
    public static synchronized long getNewNoteId(Context context, long folderId) {
        // 步骤1：创建 ContentValues，准备插入数据库的数据
        ContentValues values = new ContentValues();
        
        // 获取当前系统时间（毫秒级时间戳）
        long createdTime = System.currentTimeMillis();
        
        // 步骤2：设置笔记的初始元数据
        // 设置创建时间
        values.put(NoteColumns.CREATED_DATE, createdTime);
        // 设置修改时间（初始与创建时间相同）
        values.put(NoteColumns.MODIFIED_DATE, createdTime);
        // 设置笔记类型为普通笔记
        values.put(NoteColumns.TYPE, Notes.TYPE_NOTE);
        // 设置本地修改标志为1（新笔记）
        values.put(NoteColumns.LOCAL_MODIFIED, 1);
        // 设置所属文件夹ID
        values.put(NoteColumns.PARENT_ID, folderId);
        
        // 步骤3：执行数据库插入操作
        // Notes.CONTENT_NOTE_URI 指向 notes 表
        Uri uri = context.getContentResolver().insert(Notes.CONTENT_NOTE_URI, values);

        // 步骤4：从返回的 URI 中解析笔记ID
        // URI 格式示例：content://net.micode.notes/notes/123
        // getPathSegments().get(1) 获取 URI 路径中的第二个段（即笔记ID "123"）
        long noteId = 0;
        try {
            noteId = Long.valueOf(uri.getPathSegments().get(1));
        } catch (NumberFormatException e) {
            // 解析失败，记录错误日志
            Log.e(TAG, "Get note id error :" + e.toString());
            noteId = 0;
        }
        
        // 步骤5：验证笔记ID的有效性
        // noteId 为 -1 表示插入失败
        if (noteId == -1) {
            throw new IllegalStateException("Wrong note id:" + noteId);
        }
        
        return noteId;
    }

    /**
     * Note 类的构造函数
     * 
     * 【功能说明】
     * 初始化 Note 对象，创建空的差量数据容器。
     * 
     * 【初始化内容】
     * 1. 创建空的 mNoteDiffValues：用于存储笔记元数据的变更
     * 2. 创建 NoteData 实例：用于管理笔记内容数据
     * 
     * 【设计意图】
     * 构造函数不接收任何参数，允许灵活地创建 Note 对象。
     * 数据可以通过后续的 setter 方法添加。
     */
    public Note() {
        // 创建空的笔记元数据差量容器
        mNoteDiffValues = new ContentValues();
        // 创建笔记内容数据管理器
        mNoteData = new NoteData();
    }

    /**
     * 设置笔记元数据值（差量更新）
     * 
     * 【功能说明】
     * 将指定的键值对添加到笔记元数据的变更差量中。
     * 这是 Diff 追踪模式的核心方法之一。
     * 
     * 【调用此方法的效果】
     * 1. 将指定的 key-value 添加到 mNoteDiffValues
     * 2. 自动设置 LOCAL_MODIFIED = 1（标记为本地已修改）
     * 3. 自动更新 MODIFIED_DATE（修改时间）
     * 
     * 【参数说明】
     * @param key    笔记元数据字段名（如 NoteColumns.TITLE、NoteColumns.PARENT_ID 等）
     * @param value  字段值（字符串类型）
     * 
     * 【使用示例】
     * // 设置笔记标题
     * note.setNoteValue(NoteColumns.TITLE, "我的笔记标题");
     * // 移动笔记到另一个文件夹
     * note.setNoteValue(NoteColumns.PARENT_ID, String.valueOf(newFolderId));
     * 
     * 【注意事项】
     * - 此方法不会立即写入数据库，只是记录变更
     * - 调用 syncNote() 后，变更才会真正写入数据库
     * - 多次调用相同 key 会覆盖之前的值
     */
    public void setNoteValue(String key, String value) {
        // 将指定的键值对添加到变更差量中
        mNoteDiffValues.put(key, value);
        // 标记笔记已被本地修改
        mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
        // 更新修改时间戳
        mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
    }

    /**
     * 设置笔记文本内容数据
     * 
     * 【功能说明】
     * 将文本内容数据添加到 NoteData 中进行管理。
     * 这是设置笔记正文内容的主要方法。
     * 
     * 【参数说明】
     * @param key    数据字段名（如 TextNote.CONTENT 等）
     * @param value  字段值（文本内容）
     * 
     * 【调用链】
     * setTextData() → NoteData.setTextData() → 添加到 mTextDataValues
     * 
     * @see NoteData#setTextData(String, String)
     */
    public void setTextData(String key, String value) {
        mNoteData.setTextData(key, value);
    }

    /**
     * 设置文本数据记录的ID
     * 
     * 【功能说明】
     * 设置当前笔记关联的文本数据记录在 notes_data 表中的 ID。
     * 这个ID用于在更新操作时定位要修改的记录。
     * 
     * 【参数说明】
     * @param id  文本数据记录的ID（必须大于0）
     * 
     * 【使用场景】
     * - 当从数据库加载现有笔记时，需要设置对应的数据ID
     * - 这个ID用于构建更新操作的 URI
     * 
     * @see NoteData#setTextDataId(long)
     */
    public void setTextDataId(long id) {
        mNoteData.setTextDataId(id);
    }

    /**
     * 获取文本数据记录的ID
     * 
     * 【功能说明】
     * 返回当前笔记关联的文本数据记录ID。
     * 
     * 【返回值】
     * @return 文本数据记录的ID，如果尚未设置则为0
     */
    public long getTextDataId() {
        return mNoteData.mTextDataId;
    }

    /**
     * 设置通话数据记录的ID
     * 
     * 【功能说明】
     * 设置当前笔记关联的通话记录数据在 notes_data 表中的 ID。
     * 
     * 【参数说明】
     * @param id  通话数据记录的ID（必须大于0）
     * 
     * @see NoteData#setCallDataId(long)
     */
    public void setCallDataId(long id) {
        mNoteData.setCallDataId(id);
    }

    /**
     * 设置通话记录数据
     * 
     * 【功能说明】
     * 将通话记录相关数据添加到 NoteData 中进行管理。
     * 通话记录包括来电/去电号码、通话时长等信息。
     * 
     * 【参数说明】
     * @param key    数据字段名（如 CallNote.CALL_NUMBER、CallNote.CALL_DATE 等）
     * @param value  字段值
     * 
     * @see NoteData#setCallData(String, String)
     */
    public void setCallData(String key, String value) {
        mNoteData.setCallData(key, value);
    }

    /**
     * 检查笔记是否有本地未同步的修改
     * 
     * 【功能说明】
     * 判断当前笔记对象是否有任何未同步到数据库的变更。
     * 这包括元数据变更和内容数据变更。
     * 
     * 【返回值】
     * @return true 表示有本地修改尚未同步，false 表示没有修改
     * 
     * 【判断逻辑】
     * - mNoteDiffValues.size() > 0：笔记元数据有变更
     * - mNoteData.isLocalModified()：内容数据有变更
     * 两者满足其一就返回 true
     * 
     * 【使用场景】
     * - syncNote() 方法会先调用此方法判断是否需要同步
     * - UI 层可能需要据此显示"未保存"状态
     */
    public boolean isLocalModified() {
        // 检查元数据差量是否有变更，或内容数据是否有变更
        return mNoteDiffValues.size() > 0 || mNoteData.isLocalModified();
    }

    /**
     * syncNote()：将笔记变更同步到数据库
     * 
     * 【功能说明】
     * 这是整个 Diff 追踪模式的核心同步方法。
     * 将自创建或上次同步以来的所有变更批量写入数据库。
     * 
     * ========================================================================
     * 【完整同步流程】
     * ========================================================================
     * 
     * ┌─────────────────────────────────────────────────────────────────┐
     * │                      syncNote() 调用流程                          │
     * └─────────────────────────────────────────────────────────────────┘
     * 
     *   开始
     *     │
     *     ▼
     * ┌───────────────────┐
     * │ 1. 参数校验        │ ──► noteId <= 0 ? ──► 抛出 IllegalArgumentException
     * └───────────────────┘
     *     │
     *     ▼
     * ┌───────────────────┐
     * │ 2. 检查变更状态    │ ──► isLocalModified() == false ? ──► 直接返回 true
     * └───────────────────┘
     *     │
     *     ▼
     * ┌───────────────────────────────────────────────────────────────────┐
     * │ 3. 同步笔记元数据                                                  │
     * │                                                                   │
     * │   ContentResolver.update()                                         │
     * │       │                                                            │
     * │       ▼                                                            │
     * │   Notes.CONTENT_NOTE_URI + noteId                                 │
     * │       │                                                            │
     * │       ▼                                                            │
     * │   使用 mNoteDiffValues 中的值更新 notes 表                         │
     * │       │                                                            │
     * │       ▼                                                            │
     * │   mNoteDiffValues.clear()  ◄── 重要：清除差量数据                   │
     * └───────────────────────────────────────────────────────────────────┘
     *     │
     *     ▼
     * ┌───────────────────────────────────────────────────────────────────┐
     * │ 4. 同步笔记内容数据（如果已修改）                                    │
     * │                                                                   │
     * │   mNoteData.isLocalModified() ?                                   │
     * │       │                                                            │
     * │       ├── YES ──► mNoteData.pushIntoContentResolver()             │
     * │       │         │                                                 │
     * │       │         ▼                                                 │
     * │       │         批量插入/更新 notes_data 表                         │
     * │       │         │                                                 │
     * │       │         ▼                                                 │
     * │       │         成功 ──► 返回 noteUri                              │
     * │       │         失败 ──► 返回 null ──► syncNote() 返回 false        │
     * │       │                                                            │
     * │       └── NO ──► 跳过此步骤                                        │
     * └───────────────────────────────────────────────────────────────────┘
     *     │
     *     ▼
     *   返回 true（同步成功）
     * 
     * ========================================================================
     * 【事务特性】
     * ========================================================================
     * 
     * 本方法在同步内容数据时使用 applyBatch() 批量操作，具有以下特性：
     * 
     * 1. 原子性：
     *    - applyBatch() 是一个原子操作
     *    - 如果更新失败，整个批量操作会被回滚
     *    - 这确保了笔记元数据和内容数据的一致性
     * 
     * 2. 批量性：
     *    - 多个 ContentProviderOperation 可以一次性执行
     *    - 减少数据库连接次数，提高性能
     * 
     * 3. 完整性保证：
     *    - 即使笔记元数据更新失败，内容数据的更新操作仍会执行（见代码注释）
     *    - 这是为了"数据安全"考虑：确保内容数据不会丢失
     * 
     * ========================================================================
     * 【参数说明】
     * ========================================================================
     * 
     * @param context   上下文对象，用于访问 ContentResolver
     * @param noteId    要同步的笔记ID（必须大于0）
     * 
     * 【返回值】
     * @return true 表示同步成功，false 表示同步失败
     * 
     * 【可能失败的情况】
     * 1. 参数无效（noteId <= 0）
     * 2. 内容数据插入/更新失败（ContentProvider 操作异常）
     * 
     * 【使用示例】
     * // 修改笔记后保存
     * Note note = new Note();
     * note.setNoteValue(NoteColumns.TITLE, "新标题");
     * note.setTextData(TextNote.CONTENT, "新内容");
     * boolean success = note.syncNote(context, noteId);
     * if (!success) {
     *     // 处理同步失败
     * }
     */
    public boolean syncNote(Context context, long noteId) {
        // ====== 步骤1：参数校验 ======
        // 确保 noteId 是有效的正数
        if (noteId <= 0) {
            throw new IllegalArgumentException("Wrong note id:" + noteId);
        }

        // ====== 步骤2：检查是否有变更需要同步 ======
        // 如果没有任何修改，直接返回成功（无需执行任何操作）
        if (!isLocalModified()) {
            return true;
        }

        // ====== 步骤3：同步笔记元数据 ======
        /**
         * 【数据安全说明】
         * 理论上，一旦数据发生变化，笔记的 LOCAL_MODIFIED 和 MODIFIED_DATE 字段应该被更新。
         * 但为了数据安全考虑，即使笔记元数据更新失败（理论上不应该发生），
         * 我们仍然会继续执行内容数据的更新操作。
         * 这样可以确保笔记的实际内容不会丢失。
         */
        if (context.getContentResolver().update(
                // 构建更新目标的 URI：Notes.CONTENT_NOTE_URI + noteId
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId), 
                // 要更新的字段和值（来自变更差量）
                mNoteDiffValues, 
                // WHERE 子句（null 表示更新所有记录，即这一条）
                null,
                // WHERE 子句的参数（此处无参数）
                null) == 0) {
            // 更新返回 0 表示没有更新任何记录（理论上不应该发生）
            Log.e(TAG, "Update note error, should not happen");
            // 不要在这里返回，继续执行内容数据的同步
        }
        
        // 【重要】清除变更差量：表示这些变更已经被同步到数据库
        // 这是 Diff 模式的关键操作：同步后清空差量
        mNoteDiffValues.clear();

        // ====== 步骤4：同步笔记内容数据 ======
        // 检查内容数据是否有变更，如果有则同步
        if (mNoteData.isLocalModified()
                && (mNoteData.pushIntoContentResolver(context, noteId) == null)) {
            // 内容数据同步失败，返回 false
            return false;
        }

        // 所有同步操作都成功完成
        return true;
    }

    /**
     * NoteData 内部类：管理笔记的内容数据（文本内容和通话记录）
     * 
     * ========================================================================
     * 【类设计说明】
     * ========================================================================
     * 
     * NoteData 是 Note 类的内部类，负责管理笔记的实际内容数据。
     * 在 MiCode 笔记的数据模型中，笔记的内容和元数据是分开存储的：
     * 
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │                        notes 表（笔记元数据）                         │
     * ├─────────────────────────────────────────────────────────────────────┤
     * │  _id          │ 笔记唯一标识符                                        │
     * │  created_date │ 创建时间                                             │
     * │  modified_date│ 修改时间                                             │
     * │  type         │ 笔记类型                                             │
     * │  local_modified│ 本地修改标志                                         │
     * │  parent_id    │ 所属文件夹ID                                          │
     * └─────────────────────────────────────────────────────────────────────┘
     *                              │
     *                              │ 1:N 关系
     *                              ▼
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │                      notes_data 表（内容数据）                        │
     * ├─────────────────────────────────────────────────────────────────────┤
     * │  _id          │ 数据记录唯一标识符                                    │
     * │  note_id      │ 关联的笔记ID（外键）                                  │
     * │  mime_type    │ 数据类型（TextNote / CallNote）                       │
     * │  content      │ 文本内容（当 mime_type 为 TEXT 时）                   │
     * │  call_number  │ 电话号码（当 mime_type 为 CALL 时）                   │
     * │  ...          │ 其他字段根据数据类型不同而不同                          │
     * └─────────────────────────────────────────────────────────────────────┘
     * 
     * ========================================================================
     * 【为什么需要 NoteData】
     * ========================================================================
     * 
     * 1. 数据类型分离：
     *    - 一条笔记可以包含多种类型的内容（文本、通话记录等）
     *    - 每种内容类型有不同的字段结构
     *    - NoteData 通过独立的 ContentValues 管理不同类型的数据
     * 
     * 2. 批量操作支持：
     *    - pushIntoContentResolver() 方法可以将多个操作打包执行
     *    - 提高数据库操作效率
     * 
     * 3. 差量更新：
     *    - 类似于 Note 类，NoteData 也使用 Diff 模式
     *    - mTextDataValues 和 mCallDataValues 分别存储各类型数据的变更
     * 
     * ========================================================================
     * 【数据同步流程】
     * ========================================================================
     * 
     *    用户操作                  数据存储                  数据库同步
     *        │                        │                        │
     *        ▼                        ▼                        ▼
     *  setTextData() ─────► mTextDataValues ────► syncNote() ──────► notes_data
     *  setCallData() ─────► mCallDataValues  ────►           ──────► notes_data
     * 
     */
    private class NoteData {
        
        /** 日志标签 */
        private static final String TAG = "NoteData";
        
        /**
         * mTextDataId：文本数据记录的ID
         * 
         * 这个ID用于标识当前笔记关联的文本数据记录在 notes_data 表中的位置。
         * - 如果为 0，表示这是一条新的文本数据，需要执行 INSERT 操作
         * - 如果大于 0，表示这是已存在的文本数据，需要执行 UPDATE 操作
         */
        private long mTextDataId;
        
        /**
         * mTextDataValues：文本数据的变更差量
         * 
         * 存储文本内容相关的字段变更，如笔记正文内容。
         * 使用 ContentValues 可以方便地构建 ContentProvider 操作。
         */
        private ContentValues mTextDataValues;
        
        /**
         * mCallDataId：通话记录数据的ID
         * 
         * 标识当前笔记关联的通话记录在 notes_data 表中的位置。
         * - 如果为 0，表示这是一条新的通话记录，需要执行 INSERT 操作
         * - 如果大于 0，表示这是已存在的通话记录，需要执行 UPDATE 操作
         */
        private long mCallDataId;
        
        /**
         * mCallDataValues：通话记录数据的变更差量
         * 
         * 存储通话记录相关的字段变更，如来电/去电号码、通话时长等。
         */
        private ContentValues mCallDataValues;

        /**
         * NoteData 构造函数
         * 
         * 初始化所有成员变量，创建空的 ContentValues 容器。
         */
        public NoteData() {
            // 创建空的文本数据变更容器
            mTextDataValues = new ContentValues();
            // 创建空的通话数据变更容器
            mCallDataValues = new ContentValues();
            // 初始化文本数据ID为0（表示无关联数据）
            mTextDataId = 0;
            // 初始化通话数据ID为0（表示无关联数据）
            mCallDataId = 0;
        }

        /**
         * 检查内容数据是否有本地修改
         * 
         * 【功能说明】
         * 判断当前内容数据是否有任何未同步的变更。
         * 
         * 【返回值】
         * @return true 表示有本地修改，false 表示没有修改
         */
        boolean isLocalModified() {
            // 检查文本数据或通话数据是否有变更
            return mTextDataValues.size() > 0 || mCallDataValues.size() > 0;
        }

        /**
         * 设置文本数据记录的ID
         * 
         * 【功能说明】
         * 将当前笔记关联的文本数据记录ID设置为指定值。
         * 这个ID用于在后续的更新操作中定位记录。
         * 
         * 【参数说明】
         * @param id  文本数据记录ID（必须大于0）
         * 
         * 【参数校验】
         * - 如果 id <= 0，抛出 IllegalArgumentException
         * - 这是防止误用的安全检查
         * 
         * 【ID 的含义】
         * - 0：表示尚未关联到数据库中的记录（新建数据）
         * - > 0：表示关联到数据库中的已有记录（更新数据）
         */
        void setTextDataId(long id) {
            if(id <= 0) {
                throw new IllegalArgumentException("Text data id should larger than 0");
            }
            mTextDataId = id;
        }

        /**
         * 设置通话记录数据记录的ID
         * 
         * 【功能说明】
         * 将当前笔记关联的通话记录ID设置为指定值。
         * 
         * 【参数说明】
         * @param id  通话记录数据ID（必须大于0）
         * 
         * @see #setTextDataId(long)
         */
        void setCallDataId(long id) {
            if (id <= 0) {
                throw new IllegalArgumentException("Call data id should larger than 0");
            }
            mCallDataId = id;
        }

        /**
         * 设置通话记录数据
         * 
         * 【功能说明】
         * 将通话记录相关的字段值添加到 mCallDataValues 中。
         * 
         * 【调用效果】
         * 1. 将 key-value 添加到 mCallDataValues
         * 2. 自动更新父类 mNoteDiffValues 中的修改标志和时间戳
         * 
         * 【参数说明】
         * @param key    通话数据字段名（如 CallNote.CALL_NUMBER 等）
         * @param value  字段值
         * 
         * 【注意事项】
         * 修改通话数据时，父类的 LOCAL_MODIFIED 和 MODIFIED_DATE 也会被更新。
         * 这是因为修改内容数据本质上也是修改笔记。
         */
        void setCallData(String key, String value) {
            // 将数据添加到通话数据变更容器
            mCallDataValues.put(key, value);
            // 同时更新父类的变更差量（标记为已修改）
            mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
            mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
        }

        /**
         * 设置文本内容数据
         * 
         * 【功能说明】
         * 将文本内容相关的字段值添加到 mTextDataValues 中。
         * 
         * 【调用效果】
         * 1. 将 key-value 添加到 mTextDataValues
         * 2. 自动更新父类 mNoteDiffValues 中的修改标志和时间戳
         * 
         * 【参数说明】
         * @param key    文本数据字段名（如 TextNote.CONTENT 等）
         * @param value  字段值（文本内容）
         * 
         * @see #setCallData(String, String)
         */
        void setTextData(String key, String value) {
            // 将数据添加到文本数据变更容器
            mTextDataValues.put(key, value);
            // 同时更新父类的变更差量（标记为已修改）
            mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
            mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
        }

        /**
         * pushIntoContentResolver()：将内容数据批量同步到 ContentProvider
         * 
         * ========================================================================
         * 【方法功能说明】
         * ========================================================================
         * 
         * 这是 NoteData 类的核心方法，负责将文本数据和通话记录数据
         * 同步到数据库的 notes_data 表中。
         * 
         * ========================================================================
         * 【批量操作的逻辑流程】
         * ========================================================================
         * 
         * ┌───────────────────────────────────────────────────────────────────────┐
         * │                      pushIntoContentResolver() 执行流程                  │
         * └───────────────────────────────────────────────────────────────────────┘
         * 
         *   开始
         *     │
         *     ▼
         * ┌───────────────────────────┐
         * │ 1. 参数校验               │ ──► noteId <= 0 ? ──► 抛出异常
         * └───────────────────────────┘
         *     │
         *     ▼
         * ┌───────────────────────────┐
         * │ 2. 创建操作列表            │
         * │ operationList = new ArrayList<>()
         * └───────────────────────────┘
         *     │
         *     ▼
         * ┌───────────────────────────────────────────────────────────────────────┐
         * │ 3. 处理文本数据（如果有变更）                                            │
         * │                                                                       │
         * │   mTextDataValues.size() > 0 ?                                         │
         * │       │                                                                │
         * │       ├── YES ──► 添加 NOTE_ID                                        │
         * │       │         │                                                     │
         * │       │         ▼                                                     │
         * │       │     mTextDataId == 0 ?                                         │
         * │       │         │                                                     │
         * │       │         ├── YES ──► INSERT 操作                               │
         * │       │         │         │                                           │
         * │       │         │         ▼                                           │
         * │       │         │     添加 MIME_TYPE                                  │
         * │       │         │         │                                           │
         * │       │         │         ▼                                           │
         * │       │         │     ContentResolver.insert()                        │
         * │       │         │         │                                           │
         * │       │         │         ▼                                           │
         * │       │         │     解析返回的ID，更新 mTextDataId                    │
         * │       │         │                                                     │
         * │       │         └── NO ──► UPDATE 操作                                │
         * │       │                   │                                           │
         * │       │                   ▼                                           │
         * │       │               添加到 operationList                           │
         * │       │                                                                 │
         * │       └── NO ──► 跳过文本数据处理                                      │
         * │                                                                       │
         * └───────────────────────────────────────────────────────────────────────┘
         *     │
         *     ▼
         * ┌───────────────────────────────────────────────────────────────────────┐
         * │ 4. 处理通话数据（如果有变更）                                            │
         * │   （逻辑与文本数据处理相同）                                            │
         * └───────────────────────────────────────────────────────────────────────┘
         *     │
         *     ▼
         * ┌───────────────────────────────────────────────────────────────────────┐
         * │ 5. 执行批量操作                                                        │
         * │                                                                       │
         * │   operationList.size() > 0 ?                                         │
         * │       │                                                                │
         * │       ├── YES ──► applyBatch()                                        │
         * │       │         │                                                     │
         * │       │         ▼                                                     │
         * │       │     返回 ContentProviderResult[]                               │
         * │       │         │                                                     │
         * │       │         ▼                                                     │
         * │       │     成功 ──► 返回 noteUri                                     │
         * │       │         │                                                     │
         * │       │     失败 ──► 返回 null                                        │
         * │       │                                                                 │
         * │       └── NO ──► 返回 null                                            │
         * │                                                                       │
         * └───────────────────────────────────────────────────────────────────────┘
         * 
         * ========================================================================
         * 【INSERT vs UPDATE 的判断逻辑】
         * ========================================================================
         * 
         * 每个数据类型的处理都遵循以下逻辑：
         * 
         * 1. 判断是否有数据需要处理：
         *    - mXxxDataValues.size() > 0 表示有变更需要同步
         * 
         * 2. 判断是插入还是更新：
         *    - mXxxDataId == 0：表示这是一个新数据，需要 INSERT
         *    - mXxxDataId > 0：表示这是已有数据，需要 UPDATE
         * 
         * 3. 新数据处理（INSERT）：
         *    - 添加 NOTE_ID 字段（关联到父笔记）
         *    - 添加 MIME_TYPE 字段（标识数据类型）
         *    - 直接调用 insert() 方法插入
         *    - 从返回的 URI 中解析新记录的 ID
         *    - 更新 mXxxDataId 以便后续引用
         * 
         * 4. 已有数据处理（UPDATE）：
         *    - 将 UPDATE 操作添加到批量操作列表
         *    - 注意：更新操作不会立即执行，会和通话数据的更新一起批量执行
         * 
         * ========================================================================
         * 【批量操作的优势】
         * ========================================================================
         * 
         * 使用 applyBatch() 而非单独执行每个操作的优势：
         * 
         * 1. 性能优化：
         *    - 减少数据库连接次数
         *    - 批量提交减少事务开销
         * 
         * 2. 原子性保证：
         *    - 所有操作在一个事务中执行
         *    - 任一操作失败，整个批次回滚
         * 
         * 3. 效率提升：
         *    - 当同时有文本数据和通话数据需要更新时，
         *      两者可以一次批量提交
         * 
         * ========================================================================
         * 【参数说明】
         * ========================================================================
         * 
         * @param context   上下文对象，用于访问 ContentResolver
         * @param noteId    要关联的笔记ID
         * 
         * 【返回值】
         * @return 成功返回 noteUri（格式：Notes.CONTENT_NOTE_URI + noteId）
         * @return 失败或无操作返回 null
         * 
         * 【可能抛出异常】
         * 1. IllegalArgumentException：noteId 无效
         * 2. NumberFormatException：URI 解析失败
         * 3. RemoteException：ContentProvider 通信失败
         * 4. OperationApplicationException：批量操作执行失败
         */
        Uri pushIntoContentResolver(Context context, long noteId) {
            /**
             * 【参数校验】
             * 确保传入的 noteId 是有效的正数
             */
            if (noteId <= 0) {
                throw new IllegalArgumentException("Wrong note id:" + noteId);
            }

            // 创建 ContentProvider 操作列表，用于批量执行
            ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();
            ContentProviderOperation.Builder builder = null;

            // ==================================================================
            // 处理文本数据（如果有变更）
            // ==================================================================
            if(mTextDataValues.size() > 0) {
                // 将当前笔记ID关联到文本数据
                mTextDataValues.put(DataColumns.NOTE_ID, noteId);
                
                if (mTextDataId == 0) {
                    // 【INSERT 路径】这是一个新的文本数据记录
                    
                    // 设置 MIME_TYPE，标识这是文本类型的数据
                    mTextDataValues.put(DataColumns.MIME_TYPE, TextNote.CONTENT_ITEM_TYPE);
                    
                    // 执行插入操作
                    Uri uri = context.getContentResolver().insert(Notes.CONTENT_DATA_URI,
                            mTextDataValues);
                    
                    try {
                        // 从返回的 URI 中解析新插入记录的 ID
                        // URI 格式示例：content://net.micode.notes/data/456
                        // getPathSegments().get(1) 获取 "456"
                        setTextDataId(Long.valueOf(uri.getPathSegments().get(1)));
                    } catch (NumberFormatException e) {
                        // 插入失败，记录错误日志
                        Log.e(TAG, "Insert new text data fail with noteId" + noteId);
                        // 清空变更数据
                        mTextDataValues.clear();
                        // 返回 null 表示失败
                        return null;
                    }
                } else {
                    // 【UPDATE 路径】这是一个已存在的文本数据记录，需要更新
                    
                    // 创建更新操作构建器
                    builder = ContentProviderOperation.newUpdate(
                            // 更新目标 URI
                            ContentUris.withAppendedId(Notes.CONTENT_DATA_URI, mTextDataId));
                    
                    // 设置要更新的值
                    builder.withValues(mTextDataValues);
                    
                    // 添加到批量操作列表
                    operationList.add(builder.build());
                }
                
                // 【重要】清空文本数据变更容器
                // 这是 Diff 模式的关键操作：同步后清空差量
                mTextDataValues.clear();
            }

            // ==================================================================
            // 处理通话数据（如果有变更）
            // ==================================================================
            if(mCallDataValues.size() > 0) {
                // 将当前笔记ID关联到通话数据
                mCallDataValues.put(DataColumns.NOTE_ID, noteId);
                
                if (mCallDataId == 0) {
                    // 【INSERT 路径】这是一个新的通话记录数据
                    
                    // 设置 MIME_TYPE，标识这是通话记录类型
                    mCallDataValues.put(DataColumns.MIME_TYPE, CallNote.CONTENT_ITEM_TYPE);
                    
                    // 执行插入操作
                    Uri uri = context.getContentResolver().insert(Notes.CONTENT_DATA_URI,
                            mCallDataValues);
                    
                    try {
                        // 从返回的 URI 中解析新插入记录的 ID
                        setCallDataId(Long.valueOf(uri.getPathSegments().get(1)));
                    } catch (NumberFormatException e) {
                        // 插入失败，记录错误日志
                        Log.e(TAG, "Insert new call data fail with noteId" + noteId);
                        // 清空变更数据
                        mCallDataValues.clear();
                        // 返回 null 表示失败
                        return null;
                    }
                } else {
                    // 【UPDATE 路径】这是一个已存在的通话记录，需要更新
                    
                    // 创建更新操作构建器
                    builder = ContentProviderOperation.newUpdate(
                            // 更新目标 URI
                            ContentUris.withAppendedId(Notes.CONTENT_DATA_URI, mCallDataId));
                    
                    // 设置要更新的值
                    builder.withValues(mCallDataValues);
                    
                    // 添加到批量操作列表
                    operationList.add(builder.build());
                }
                
                // 【重要】清空通话数据变更容器
                mCallDataValues.clear();
            }

            // ==================================================================
            // 执行批量操作
            // ==================================================================
            if (operationList.size() > 0) {
                // 如果有待执行的更新操作，执行批量提交
                try {
                    // applyBatch() 是原子操作：所有操作要么全部成功，要么全部失败
                    ContentProviderResult[] results = context.getContentResolver().applyBatch(
                            Notes.AUTHORITY, operationList);
                    
                    // 检查执行结果
                    return (results == null || results.length == 0 || results[0] == null) ? null
                            // 返回新笔记的 URI
                            : ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId);
                            
                } catch (RemoteException e) {
                    // ContentProvider 进程已崩溃或被杀
                    Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
                    return null;
                } catch (OperationApplicationException e) {
                    // 批量操作中某个操作失败
                    Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
                    return null;
                }
            }
            
            // 没有需要执行的操作
            return null;
        }
    }
}
