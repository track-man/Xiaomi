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

import android.appwidget.AppWidgetManager;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.TextNote;
import net.micode.notes.tool.ResourceParser.NoteBgResources;


/**
 * WorkingNote - 工作中的笔记（编辑状态管理器）
 * 
 * 设计意图：
 * WorkingNote 是笔记应用中的"编辑中笔记"状态管理器。它代表了用户当前正在编辑或查看的笔记，
 * 作为一个临时的工作副本（working copy），将笔记数据从数据库加载到内存中，供UI层进行展示和编辑。
 * 
 * 核心设计模式：
 * 1. 工厂模式：通过 createEmptyNote() 和 load() 静态工厂方法创建实例，封装了构造函数
 * 2. 观察者模式：通过 NoteSettingChangedListener 接口，当笔记设置变更时通知监听者（如Activity/Fragment）
 * 3. 脏值标记：通过 isLocalModified() 判断内容是否被修改，避免不必要的数据库写入
 * 
 * 数据流：
 * - 创建/加载：工厂方法创建 WorkingNote 实例，构造时自动从数据库加载数据
 * - 编辑：UI层调用 setter 方法修改属性（内容、颜色、提醒等）
 * - 保存：调用 saveNote() 将修改同步回数据库
 * 
 * 与 Note 类的区别：
 * - Note 类：数据库实体对象的映射，封装了原始数据字段的存取
 * - WorkingNote 类：编辑器视图层的数据模型，管理编辑状态并协调 Note 与数据库的同步
 */
public class WorkingNote {
    // ==========================================================================
    // 成员变量说明
    // ==========================================================================
    
    /** 
     * Note 数据实体对象
     * Note 类封装了笔记的原始数据（通过 setNoteValue/setTextData/setCallData 方法设置），
     * 当调用 saveNote() 时，WorkingNote 会调用 mNote.syncNote() 将数据同步到数据库
     */
    private Note mNote;
    
    /** 笔记在数据库中的唯一标识ID（0表示尚未保存到数据库的新笔记） */
    private long mNoteId;
    
    /** 笔记的文本内容（编辑中的工作副本） */
    private String mContent;
    
    /** 
     * 清单模式（check list mode）
     * 0 或 TextNote.MODE_NORMAL：普通文本模式
     * TextNote.MODE_CHECKLIST：清单/待办事项模式，支持勾选完成状态
     */
    private int mMode;

    /** 闹钟提醒时间戳（毫秒，0表示未设置提醒） */
    private long mAlertDate;

    /** 笔记最后修改时间戳 */
    private long mModifiedDate;

    /** 背景颜色资源ID（对应预定义的背景颜色主题） */
    private int mBgColorId;

    /** 关联的桌面小部件ID（INVALID_APPWIDGET_ID 表示无关联） */
    private int mWidgetId;

    /** 小部件类型（如 TYPE_WIDGET_INVALIDE、TYPE_WIDGET_NAME 等） */
    private int mWidgetType;

    /** 所属文件夹ID（用于组织笔记的层级结构） */
    private long mFolderId;

    /** Android 上下文引用（用于访问 ContentResolver 进行数据库操作） */
    private Context mContext;

    /** 日志标签 */
    private static final String TAG = "WorkingNote";

    /** 软删除标记（true=已标记删除，待同步到数据库） */
    private boolean mIsDeleted;

    /** 
     * 设置变更监听器
     * 当笔记的某些设置（如背景色、提醒时间、清单模式）发生变更时，
     * 会回调此监听器通知 UI 层刷新显示或执行相应操作（如设置闹钟）
     */
    private NoteSettingChangedListener mNoteSettingStatusListener;

    // ==========================================================================
    // 数据库查询投影常量（用于优化 Cursor 列索引访问）
    // ==========================================================================
    
    /** 
     * DATA 表查询投影 - 用于查询笔记的文本内容和通话记录数据
     * 包含：ID、内容、MIME类型、以及各类 DATA 字段
     */
    public static final String[] DATA_PROJECTION = new String[] {
            DataColumns.ID,            // 0: 数据记录ID
            DataColumns.CONTENT,       // 1: 文本内容
            DataColumns.MIME_TYPE,     // 2: 数据类型（NOTE 或 CALL_NOTE）
            DataColumns.DATA1,         // 3: 模式字段（用于文本类型：普通/清单模式）
            DataColumns.DATA2,         // 4: 备用字段
            DataColumns.DATA3,         // 5: 备用字段
            DataColumns.DATA4,         // 6: 备用字段
    };

    /** 
     * NOTE 表查询投影 - 用于查询笔记的元数据信息
     * 包含：父文件夹ID、提醒时间、背景颜色、小部件信息、修改时间
     */
    public static final String[] NOTE_PROJECTION = new String[] {
            NoteColumns.PARENT_ID,      // 0: 父文件夹ID
            NoteColumns.ALERTED_DATE,   // 1: 提醒日期时间戳
            NoteColumns.BG_COLOR_ID,    // 2: 背景颜色ID
            NoteColumns.WIDGET_ID,      // 3: 关联小部件ID
            NoteColumns.WIDGET_TYPE,    // 4: 小部件类型
            NoteColumns.MODIFIED_DATE   // 5: 最后修改时间
    };

    // DATA 表列索引常量
    private static final int DATA_ID_COLUMN = 0;
    private static final int DATA_CONTENT_COLUMN = 1;
    private static final int DATA_MIME_TYPE_COLUMN = 2;
    private static final int DATA_MODE_COLUMN = 3;

    // NOTE 表列索引常量
    private static final int NOTE_PARENT_ID_COLUMN = 0;
    private static final int NOTE_ALERTED_DATE_COLUMN = 1;
    private static final int NOTE_BG_COLOR_ID_COLUMN = 2;
    private static final int NOTE_WIDGET_ID_COLUMN = 3;
    private static final int NOTE_WIDGET_TYPE_COLUMN = 4;
    private static final int NOTE_MODIFIED_DATE_COLUMN = 5;

    // ==========================================================================
    // 私有构造函数
    // ==========================================================================

    /**
     * 私有构造函数：创建新的空白笔记
     * 
     * 使用场景：
     * - 用户从文件夹列表点击"新建笔记"按钮
     * - 用户从桌面小部件创建新笔记
     * 
     * 初始化逻辑：
     * - 设置默认提醒时间为0（无提醒）
     * - 设置当前系统时间为创建/修改时间
     * - 分配到指定的文件夹
     * - 笔记ID初始化为0（表示尚未保存到数据库）
     * - 标记为未删除状态
     * - 默认模式为普通文本模式
     * - 小部件类型初始化为无效类型
     * 
     * @param context   Android 上下文
     * @param folderId  笔记所属文件夹的ID
     */
    private WorkingNote(Context context, long folderId) {
        mContext = context;
        mAlertDate = 0;                              // 默认无提醒
        mModifiedDate = System.currentTimeMillis();  // 当前时间作为创建时间
        mFolderId = folderId;                        // 分配到指定文件夹
        mNote = new Note();                          // 创建空的 Note 实体
        mNoteId = 0;                                 // 0 表示新笔记，尚未保存
        mIsDeleted = false;                          // 初始状态为未删除
        mMode = 0;                                   // 默认普通文本模式
        mWidgetType = Notes.TYPE_WIDGET_INVALIDE;    // 无效的小部件类型
    }

    /**
     * 私有构造函数：加载已存在的笔记
     * 
     * 使用场景：
     * - 用户从笔记列表选择一个已有笔记进行查看或编辑
     * - 需要将数据库中的笔记数据加载到内存中进行操作
     * 
     * 加载流程：
     * 1. 保存传入的笔记ID和文件夹ID
     * 2. 创建一个空的 Note 实体对象
     * 3. 调用 loadNote() 从数据库加载笔记元数据
     * 4. 调用 loadNoteData() 从数据库加载笔记内容数据
     * 
     * 注意：此构造函数会抛出 IllegalArgumentException 如果笔记ID无效
     * 
     * @param context   Android 上下文
     * @param noteId    要加载的笔记在数据库中的ID（必须 > 0）
     * @param folderId  文件夹ID（此参数被忽略，实际从数据库加载）
     */
    private WorkingNote(Context context, long noteId, long folderId) {
        mContext = context;
        mNoteId = noteId;         // 记录要加载的笔记ID
        mFolderId = folderId;    // 此值会被 loadNote() 中的查询结果覆盖
        mIsDeleted = false;      // 初始状态为未删除
        mNote = new Note();       // 创建空的 Note 实体，后续加载数据填充
        loadNote();              // 从数据库加载笔记数据
    }

    // ==========================================================================
    // 私有数据加载方法
    // ==========================================================================

    /**
     * 从数据库加载笔记元数据
     * 
     * 数据加载流程（第一步）：
     * 1. 通过 ContentResolver 查询 NOTE 表，获取笔记的元信息
     * 2. 从 Cursor 中提取以下字段：
     *    - mFolderId: 所属文件夹
     *    - mBgColorId: 背景颜色
     *    - mWidgetId: 关联小部件ID
     *    - mWidgetType: 小部件类型
     *    - mAlertDate: 提醒时间
     *    - mModifiedDate: 修改时间
     * 3. 如果笔记不存在，抛出 IllegalArgumentException
     * 4. 加载完成后，调用 loadNoteData() 继续加载内容数据
     * 
     * 查询逻辑：
     * - 使用 Notes.CONTENT_NOTE_URI + noteId 构建特定笔记的 URI
     * - 返回单条记录（moveToFirst 验证）
     * 
     * @throws IllegalArgumentException 如果笔记ID不存在于数据库中
     */
    private void loadNote() {
        // 查询 NOTE 表获取笔记元数据
        Cursor cursor = mContext.getContentResolver().query(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, mNoteId), 
                NOTE_PROJECTION, 
                null,  // 无 WHERE 子句条件
                null, 
                null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                // 提取笔记元数据字段
                mFolderId = cursor.getLong(NOTE_PARENT_ID_COLUMN);        // 所属文件夹
                mBgColorId = cursor.getInt(NOTE_BG_COLOR_ID_COLUMN);       // 背景颜色
                mWidgetId = cursor.getInt(NOTE_WIDGET_ID_COLUMN);        // 小部件ID
                mWidgetType = cursor.getInt(NOTE_WIDGET_TYPE_COLUMN);     // 小部件类型
                mAlertDate = cursor.getLong(NOTE_ALERTED_DATE_COLUMN);    // 提醒时间
                mModifiedDate = cursor.getLong(NOTE_MODIFIED_DATE_COLUMN);// 修改时间
            }
            cursor.close();
        } else {
            // Cursor 为空表示查询失败或笔记不存在
            Log.e(TAG, "No note with id:" + mNoteId);
            throw new IllegalArgumentException("Unable to find note with id " + mNoteId);
        }
        
        // 继续加载笔记内容数据
        loadNoteData();
    }

    /**
     * 从数据库加载笔记内容数据
     * 
     * 数据加载流程（第二步）：
     * 1. 通过 ContentResolver 查询 DATA 表，获取笔记的具体内容
     * 2. 查询条件：DataColumns.NOTE_ID = mNoteId
     * 3. 遍历 Cursor，根据 MIME 类型分别处理：
     *    - DataConstants.NOTE: 文本笔记
     *      → 提取内容 (mContent)、模式 (mMode)
     *      → 设置 Note 对象的文本数据ID (mNote.setTextDataId)
     *    - DataConstants.CALL_NOTE: 通话记录笔记
     *      → 设置 Note 对象的通话数据ID (mNote.setCallDataId)
     * 4. 一个笔记可能有多条 DATA 记录（文本内容、通话记录等），用 do-while 遍历
     * 
     * 注意：
     * - TEXT 和 CALL_NOTE 是互斥的，一个笔记只能是其中一种
     * - mNote.setTextDataId/setCallDataId 用于在保存时知道更新哪条记录
     * 
     * @throws IllegalArgumentException 如果笔记没有关联的数据记录
     */
    private void loadNoteData() {
        // 查询 DATA 表获取笔记内容
        Cursor cursor = mContext.getContentResolver().query(
                Notes.CONTENT_DATA_URI, 
                DATA_PROJECTION,
                DataColumns.NOTE_ID + "=?",   // WHERE 条件：关联到指定笔记ID
                new String[] {
                    String.valueOf(mNoteId)    // 参数化查询，防止 SQL 注入
                }, 
                null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    String type = cursor.getString(DATA_MIME_TYPE_COLUMN);
                    if (DataConstants.NOTE.equals(type)) {
                        // 文本笔记类型
                        mContent = cursor.getString(DATA_CONTENT_COLUMN);  // 文本内容
                        mMode = cursor.getInt(DATA_MODE_COLUMN);            // 清单模式
                        mNote.setTextDataId(cursor.getLong(DATA_ID_COLUMN));// 记录数据ID用于后续更新
                    } else if (DataConstants.CALL_NOTE.equals(type)) {
                        // 通话记录笔记类型
                        mNote.setCallDataId(cursor.getLong(DATA_ID_COLUMN));// 记录数据ID用于后续更新
                    } else {
                        // 未知类型，记录日志继续处理
                        Log.d(TAG, "Wrong note type with type:" + type);
                    }
                } while (cursor.moveToNext());
            }
            cursor.close();
        } else {
            // Cursor 为空表示没有找到关联的数据记录
            Log.e(TAG, "No data with id:" + mNoteId);
            throw new IllegalArgumentException("Unable to find note's data with id " + mNoteId);
        }
    }

    // ==========================================================================
    // 公开工厂方法（创建 WorkingNote 实例的两种途径）
    // ==========================================================================

    /**
     * 工厂方法：创建一个新的空白笔记
     * 
     * 使用场景：
     * 1. 用户在笔记列表界面点击"新建"按钮创建空白笔记
     * 2. 用户通过桌面小部件快速创建新笔记
     * 3. 应用启动时，小部件需要显示一个可编辑的新笔记
     * 
     * 与 load() 方法的区别：
     * ┌─────────────────┬─────────────────────────┬─────────────────────────┐
     * │     属性        │    createEmptyNote()    │        load()           │
     * ├─────────────────┼─────────────────────────┼─────────────────────────┤
     * │ 笔记ID          │ 0（新建，未保存）         │ 从数据库加载             │
     * │ 内容            │ 空字符串                 │ 从数据库读取             │
     * │ 颜色/提醒等设置  │ 使用传入的默认值          │ 从数据库读取             │
     * │ 小部件关联       │ 与创建时的小部件绑定       │ 继承原笔记的关联         │
     * │ 数据库操作       │ saveNote()时 INSERT      │ saveNote()时 UPDATE     │
     * │ 构造流程        │ 仅初始化基础字段           │ 调用 loadNote() 全量加载 │
     * └─────────────────┴─────────────────────────┴─────────────────────────┘
     * 
     * 参数说明：
     * @param context           Android 上下文（必需，用于数据库操作）
     * @param folderId          笔记所属文件夹ID（决定笔记的存储位置）
     * @param widgetId          关联的桌面小部件ID（INVALID_APPWIDGET_ID 表示无关联）
     * @param widgetType        小部件类型（用于区分不同样式的小部件）
     * @param defaultBgColorId  默认背景颜色ID（应用预设的颜色主题）
     * 
     * @return WorkingNote 新创建的空白笔记实例
     */
    public static WorkingNote createEmptyNote(Context context, long folderId, int widgetId,
            int widgetType, int defaultBgColorId) {
        WorkingNote note = new WorkingNote(context, folderId);
        note.setBgColorId(defaultBgColorId);    // 设置默认背景颜色
        note.setWidgetId(widgetId);             // 绑定到指定小部件
        note.setWidgetType(widgetType);         // 设置小部件类型
        return note;
    }

    /**
     * 工厂方法：从数据库加载一个已存在的笔记
     * 
     * 使用场景：
     * 1. 用户从笔记列表选择一个笔记进入编辑界面
     * 2. 需要恢复一个已保存的笔记进行查看或继续编辑
     * 3. 小部件需要显示已保存的笔记内容
     * 
     * 与 createEmptyNote() 方法的区别：
     * 参见 createEmptyNote() 方法的对比表格
     * 
     * 加载流程：
     * 1. 调用私有构造函数 WorkingNote(context, id, 0)
     * 2. 构造函数内部调用 loadNote() → loadNoteData()
     * 3. 数据从数据库 NOTE 表和 DATA 表查询加载
     * 4. 加载完成后，所有字段都已填充，可供编辑
     * 
     * @param context   Android 上下文
     * @param id        要加载的笔记在数据库中的ID（必须 > 0）
     * 
     * @return WorkingNote 加载完成的笔记实例
     * 
     * @throws IllegalArgumentException 如果指定的笔记ID不存在
     */
    public static WorkingNote load(Context context, long id) {
        return new WorkingNote(context, id, 0);
    }

    // ==========================================================================
    // 公开保存和状态检查方法
    // ==========================================================================

    /**
     * 将工作副本保存到数据库
     * 
     * 保存逻辑流程：
     * 
     *                    ┌─────────────────────┐
     *                    │    调用 saveNote()   │
     *                    └──────────┬──────────┘
     *                               │
     *                    ┌──────────▼──────────┐
     *                    │  isWorthSaving()     │
     *                    │  是否值得保存？       │
     *                    └──────────┬──────────┘
     *                      是 ↙       ↘ 否
     *                     ┌───┐    ┌──────────────┐
     *                     │ 1 │    │ 返回 false    │
     *                     └───┘    │ 不执行保存    │
     *                      │       └──────────────┘
     *          ┌──────────▼──────────┐
     *          │  existInDatabase()  │
     *          │  数据库中已存在？    │
     *          └──────────┬──────────┘
     *            是 ↙      ↘ 否
     *           ┌───┐    ┌────────────────────┐
     *           │ 2 │    │ 3. 获取新笔记ID     │
     *           └───┘    │ (Note.getNewNoteId)│
     *            │       └─────────┬──────────┘
     *            │        成功 ↙    ↘ 失败
     *            │       ┌───┐   ┌──────────────┐
     *            │       │ 4 │   │ 返回 false    │
     *            │       └───┘   └──────────────┘
     *            │        │
     *            │       ┌▼────────────────────▼┐
     *            │       │ 4. syncNote() 同步数据│
     *            │       │ 到数据库               │
     *            │       └──────────┬───────────┘
     *            │                  │
     *            │       ┌──────────▼──────────┐
     *            │       │ 检查小部件变更       │
     *            │       │ (widgetId != -1 且   │
     *            │       │  listener != null)   │
     *            │       └──────────┬───────────┘
     *            │                  │
     *            │                  ▼
     *            │       onWidgetChanged() 回调
     *            │                  │
     *            │                  ▼
     *            │              返回 true
     *            │                  │
     *            └──────────────────┴─────► 完成
     * 
     * isWorthSaving() 判断条件详解：
     * - mIsDeleted == true              → 已标记删除，不保存
     * - !existInDatabase() && 空内容     → 新笔记且内容为空，不保存（避免创建空白笔记）
     * - existInDatabase() && !isModified → 已存在且未修改，不保存（避免重复写入）
     * 
     * @return boolean 
     *   - true:  保存成功（或不需要保存但逻辑正确）
     *   - false: 保存失败（如获取新ID失败）或不值得保存
     */
    public synchronized boolean saveNote() {
        if (isWorthSaving()) {
            if (!existInDatabase()) {
                // 步骤1-2：新建笔记，获取数据库ID
                if ((mNoteId = Note.getNewNoteId(mContext, mFolderId)) == 0) {
                    Log.e(TAG, "Create new note fail with id:" + mNoteId);
                    return false;
                }
            }

            // 步骤3：将数据同步到数据库
            mNote.syncNote(mContext, mNoteId);

            /**
             * 步骤4：如果笔记关联了桌面小部件，通知小部件更新显示内容
             * 条件：必须同时满足
             *   - mWidgetId != INVALID_APPWIDGET_ID（有有效的小部件ID）
             *   - mWidgetType != TYPE_WIDGET_INVALIDE（小部件类型有效）
             *   - mNoteSettingStatusListener != null（监听器已设置）
             */
            if (mWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                    && mWidgetType != Notes.TYPE_WIDGET_INVALIDE
                    && mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onWidgetChanged();
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * 检查笔记是否已存在于数据库中
     * 
     * 判断依据：
     * - mNoteId > 0：笔记已保存，获得了数据库分配的ID
     * - mNoteId == 0：新笔记，尚未保存到数据库
     * 
     * 使用场景：
     * 1. saveNote() 中判断应该执行 INSERT 还是 UPDATE
     * 2. UI 层判断笔记是否为新建
     * 
     * @return boolean 
     *   - true:  笔记已存在于数据库（mNoteId > 0）
     *   - false: 新笔记，尚未保存（mNoteId == 0）
     */
    public boolean existInDatabase() {
        return mNoteId > 0;
    }

    /**
     * 判断当前状态是否值得保存到数据库
     * 
     * 判断逻辑（任一条件满足则不值得保存）：
     * 
     * ┌────────────────────────────────────────────────────────────┐
     * │ 条件1: mIsDeleted == true                                  │
     * │ 含义: 笔记已被标记删除                                      │
     * │ 处理: 不保存，标记删除状态会在下次同步时反映到数据库           │
     * └────────────────────────────────────────────────────────────┘
     * 
     * ┌────────────────────────────────────────────────────────────┐
     * │ 条件2: !existInDatabase() && TextUtils.isEmpty(mContent)  │
     * │ 含义: 新笔记（mNoteId==0）且内容为空                        │
     * │ 处理: 不保存，避免在数据库中创建空白笔记                      │
     * │ 目的: 防止用户误触创建空白笔记占用空间                        │
     * └────────────────────────────────────────────────────────────┘
     * 
     * ┌────────────────────────────────────────────────────────────┐
     * │ 条件3: existInDatabase() && !mNote.isLocalModified()      │
     * │ 含义: 已存在的笔记，但内容未被修改                           │
     * │ 处理: 不保存，避免不必要的数据库写入操作                      │
     * │ 目的: 优化性能，减少 I/O 操作                               │
     * └────────────────────────────────────────────────────────────┘
     * 
     * @return boolean 
     *   - true:  值得保存（需要同步到数据库）
     *   - false: 不值得保存（跳过保存操作）
     */
    private boolean isWorthSaving() {
        if (mIsDeleted || (!existInDatabase() && TextUtils.isEmpty(mContent))
                || (existInDatabase() && !mNote.isLocalModified())) {
            return false;
        } else {
            return true;
        }
    }

    // ==========================================================================
    // 设置变更监听器相关方法
    // ==========================================================================

    /**
     * 设置笔记设置变更监听器
     * 
     * NoteSettingChangedListener 接口用途：
     * WorkingNote 作为数据模型层，不应该直接操作 UI。当笔记的某些设置（如背景色、提醒时间）
     * 发生变更时，需要通知上层（Activity/Fragment）进行相应的 UI 更新或系统操作。
     * 
     * 典型使用模式：
     * ```java
     * WorkingNote note = WorkingNote.load(context, noteId);
     * note.setOnSettingStatusChangedListener(new NoteSettingChangedListener() {
     *     @Override
     *     public void onBackgroundColorChanged() {
     *         // UI 层刷新背景色
     *         updateNoteBackground();
     *     }
     *     
     *     @Override
     *     public void onClockAlertChanged(long date, boolean set) {
     *         // 设置或取消系统闹钟
     *         if (set) scheduleAlarm(date);
     *         else cancelAlarm();
     *     }
     *     
         *     @Override
     *     public void onWidgetChanged() {
     *         // 更新桌面小部件显示
     *         widgetProvider.updateWidget();
     *     }
     *     
     *     @Override
     *     public void onCheckListModeChanged(int oldMode, int newMode) {
     *         // 切换清单模式，重新渲染列表
     *         refreshNoteContent();
     *     }
     * });
     * ```
     * 
     * @param l  设置变更监听器实例（传入 null 可移除监听器）
     */
    public void setOnSettingStatusChangedListener(NoteSettingChangedListener l) {
        mNoteSettingStatusListener = l;
    }

    // ==========================================================================
    // 属性设置方法（setter）- 带变更通知机制
    // ==========================================================================

    /**
     * 设置闹钟提醒时间
     * 
     * 变更逻辑：
     * 1. 比较新时间和当前时间是否不同
     * 2. 如果不同，更新 mAlertDate 并同步到 Note 实体
     * 3. 如果设置了监听器，回调 onClockAlertChanged()
     *    - 通知上层设置或取消系统闹钟
     *    - 传入 date 和 set 参数，供上层决定如何处理闹钟
     * 
     * @param date  提醒时间戳（毫秒，0 表示取消提醒）
     * @param set   是否设置提醒（true=设置闹钟，false=取消闹钟）
     *              - set=true, date>0：添加新提醒
     *              - set=false：移除现有提醒
     */
    public void setAlertDate(long date, boolean set) {
        if (date != mAlertDate) {
            mAlertDate = date;
            // 同步到 Note 实体的 ALERTED_DATE 字段
            mNote.setNoteValue(NoteColumns.ALERTED_DATE, String.valueOf(mAlertDate));
        }
        // 无论时间是否变化，都通知监听器（因为 set 参数可能变化）
        if (mNoteSettingStatusListener != null) {
            mNoteSettingStatusListener.onClockAlertChanged(date, set);
        }
    }

    /**
     * 标记笔记为已删除状态
     * 
     * 软删除机制：
     * - 不是立即从数据库删除，而是设置 mIsDeleted = true
     * - saveNote() 时通过 isWorthSaving() 检查到已删除状态，会跳过保存
     * - 实际删除由 NotesContentProvider 的后台同步服务处理
     * 
     * 小部件处理：
     * - 如果笔记关联了桌面小部件，删除时会回调 onWidgetChanged()
     * - 让小部件更新显示（如显示"笔记已删除"或恢复默认内容）
     * 
     * @param mark 
     *   - true:  标记为已删除
     *   - false: 取消删除标记（恢复笔记）
     */
    public void markDeleted(boolean mark) {
        mIsDeleted = mark;
        // 如果有关联的小部件，通知其更新
        if (mWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                && mWidgetType != Notes.TYPE_WIDGET_INVALIDE && mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onWidgetChanged();
        }
    }

    /**
     * 设置笔记背景颜色
     * 
     * 变更逻辑：
     * 1. 比较新颜色ID和当前颜色ID是否不同
     * 2. 如果不同：
     *    - 更新 mBgColorId
     *    - 如果有监听器，回调 onBackgroundColorChanged()
     *    - 同步到 Note 实体的 BG_COLOR_ID 字段
     * 
     * 使用场景：
     * - 用户在编辑界面点击颜色选择器更改笔记背景
     * - 系统预设多种颜色主题供用户选择
     * 
     * @param id  背景颜色资源ID（对应 NoteBgResources 中的预定义颜色）
     */
    public void setBgColorId(int id) {
        if (id != mBgColorId) {
            mBgColorId = id;
            if (mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onBackgroundColorChanged();
            }
            mNote.setNoteValue(NoteColumns.BG_COLOR_ID, String.valueOf(id));
        }
    }

    /**
     * 设置清单模式（普通文本 / 待办清单）
     * 
     * 变更逻辑：
     * 1. 比较新模式和当前模式是否不同
     * 2. 如果不同：
     *    - 如果有监听器，回调 onCheckListModeChanged(oldMode, newMode)
     *    - 更新 mMode
     *    - 同步到 Note 实体的文本数据 MODE 字段
     * 
     * 清单模式说明：
     * - mMode = TextNote.MODE_NORMAL (0)：普通文本模式，每行是普通文本
     * - mMode = TextNote.MODE_CHECKLIST (1)：清单模式，每行是带复选框的待办项
     * 
     * 使用场景：
     * - 用户点击工具栏切换笔记类型
     * - 系统需要区分普通笔记和待办清单
     * 
     * @param mode  清单模式（0=普通模式，1=清单模式）
     */
    public void setCheckListMode(int mode) {
        if (mMode != mode) {
            if (mNoteSettingStatusListener != null) {
                // 传递新旧模式值，让 UI 层可以处理过渡动画等
                mNoteSettingStatusListener.onCheckListModeChanged(mMode, mode);
            }
            mMode = mode;
            mNote.setTextData(TextNote.MODE, String.valueOf(mMode));
        }
    }

    /**
     * 设置小部件类型
     * 
     * 用于区分不同样式/尺寸的桌面小部件。
     * 不同类型的小部件可能显示不同的布局或功能。
     * 
     * @param type  小部件类型常量（如 TYPE_WIDGET_INVALIDE, TYPE_WIDGET_NAME 等）
     */
    public void setWidgetType(int type) {
        if (type != mWidgetType) {
            mWidgetType = type;
            mNote.setNoteValue(NoteColumns.WIDGET_TYPE, String.valueOf(mWidgetType));
        }
    }

    /**
     * 设置关联的桌面小部件ID
     * 
     * 当笔记与桌面小部件绑定时：
     * - 小部件会显示该笔记的内容
     * - 用户可以直接在小部件中编辑笔记
     * - 笔记保存时需要通知小部件更新
     * 
     * @param id  小部件ID（INVALID_APPWIDGET_ID 表示无关联）
     */
    public void setWidgetId(int id) {
        if (id != mWidgetId) {
            mWidgetId = id;
            mNote.setNoteValue(NoteColumns.WIDGET_ID, String.valueOf(mWidgetId));
        }
    }

    /**
     * 设置笔记的文本内容
     * 
     * 变更逻辑：
     * 1. 使用 TextUtils.equals() 比较新旧内容（安全处理 null）
     * 2. 如果内容确实变化：
     *    - 更新 mContent
     *    - 同步到 Note 实体的 CONTENT 字段
     * 
     * 注意：
     * - 此方法只设置普通文本内容
     * - 不处理清单项的勾选状态（由单独的逻辑处理）
     * 
     * @param text  新的笔记文本内容（可为 null 或空字符串）
     */
    public void setWorkingText(String text) {
        if (!TextUtils.equals(mContent, text)) {
            mContent = text;
            mNote.setTextData(DataColumns.CONTENT, mContent);
        }
    }

    /**
     * 将当前笔记转换为通话记录笔记
     * 
     * 使用场景：
     * - 用户从通话记录直接创建笔记
     * - 系统自动记录通话内容并创建笔记
     * 
     * 转换操作：
     * 1. 在 Note 实体的通话数据中设置：
     *    - CALL_DATE: 通话时间
     *    - PHONE_NUMBER: 来电号码
     * 2. 将笔记移动到通话记录专属文件夹 (ID_CALL_RECORD_FOLDER)
     * 
     * @param phoneNumber  来电号码（用于显示"来自XXX的通话"）
     * @param callDate     通话时间戳（毫秒）
     */
    public void convertToCallNote(String phoneNumber, long callDate) {
        mNote.setCallData(CallNote.CALL_DATE, String.valueOf(callDate));
        mNote.setCallData(CallNote.PHONE_NUMBER, phoneNumber);
        mNote.setNoteValue(NoteColumns.PARENT_ID, String.valueOf(Notes.ID_CALL_RECORD_FOLDER));
    }

    // ==========================================================================
    // 属性查询方法（getter）
    // ==========================================================================

    /**
     * 检查笔记是否设置了闹钟提醒
     * 
     * @return boolean 
     *   - true:  已设置提醒（mAlertDate > 0）
     *   - false: 未设置提醒（mAlertDate == 0）
     */
    public boolean hasClockAlert() {
        return (mAlertDate > 0 ? true : false);
    }

    /**
     * 获取笔记的文本内容
     * 
     * @return String 笔记内容（可能为 null 或空字符串）
     */
    public String getContent() {
        return mContent;
    }

    /**
     * 获取闹钟提醒时间戳
     * 
     * @return long 提醒时间戳（毫秒），0 表示未设置提醒
     */
    public long getAlertDate() {
        return mAlertDate;
    }

    /**
     * 获取笔记最后修改时间戳
     * 
     * @return long 修改时间戳（毫秒，Unix 时间）
     */
    public long getModifiedDate() {
        return mModifiedDate;
    }

    /**
     * 获取背景颜色Drawable资源ID
     * 
     * 与 getBgColorId() 的区别：
     * - getBgColorId(): 返回颜色ID（整数）
     * - getBgColorResId(): 返回颜色对应的 Drawable 资源ID（用于设置背景）
     * 
     * 内部调用 NoteBgResources.getNoteBgResource() 进行 ID 转换
     * 
     * @return int Drawable 资源ID（用于 setBackgroundResource()）
     */
    public int getBgColorResId() {
        return NoteBgResources.getNoteBgResource(mBgColorId);
    }

    /**
     * 获取背景颜色ID
     * 
     * @return int 背景颜色ID（整数，对应预定义的颜色主题）
     */
    public int getBgColorId() {
        return mBgColorId;
    }

    /**
     * 获取标题栏背景Drawable资源ID
     * 
     * 用于为笔记标题栏设置与背景协调的颜色。
     * 内部调用 NoteBgResources.getNoteTitleBgResource() 获取标题专用背景。
     * 
     * @return int 标题背景 Drawable 资源ID
     */
    public int getTitleBgResId() {
        return NoteBgResources.getNoteTitleBgResource(mBgColorId);
    }

    /**
     * 获取清单模式
     * 
     * @return int 清单模式（0=普通文本模式，1=清单模式）
     */
    public int getCheckListMode() {
        return mMode;
    }

    /**
     * 获取笔记数据库ID
     * 
     * @return long 笔记ID（0 表示新笔记尚未保存）
     */
    public long getNoteId() {
        return mNoteId;
    }

    /**
     * 获取所属文件夹ID
     * 
     * @return long 文件夹ID
     */
    public long getFolderId() {
        return mFolderId;
    }

    /**
     * 获取关联的小部件ID
     * 
     * @return int 小部件ID（INVALID_APPWIDGET_ID 表示无关联）
     */
    public int getWidgetId() {
        return mWidgetId;
    }

    /**
     * 获取小部件类型
     * 
     * @return int 小部件类型常量
     */
    public int getWidgetType() {
        return mWidgetType;
    }

    // ==========================================================================
    // 内部接口：设置变更监听器
    // ==========================================================================

    /**
     * NoteSettingChangedListener - 笔记设置变更监听器接口
     * 
     * 设计目的：
     * WorkingNote 作为数据模型层，与 UI 层解耦。当笔记的视觉设置或提醒设置发生变更时，
     * 需要通知上层进行相应的 UI 更新或系统操作（设置闹钟、更新小部件等）。
     * 
     * 回调时机：
     * ┌─────────────────────────────────────────────────────────────────────┐
     │  onBackgroundColorChanged()                                         │
     │  - 触发时机：setBgColorId() 检测到颜色变化时                          │
     │  - 用途：UI 层刷新笔记的背景颜色                                       │
     └─────────────────────────────────────────────────────────────────────┘
     * ┌─────────────────────────────────────────────────────────────────────┐
     │  onClockAlertChanged(long date, boolean set)                         │
     │  - 触发时机：setAlertDate() 被调用时                                  │
     │  - 用途：设置或取消系统闹钟日程                                        │
     │  - 参数说明：                                                        │
     │    - date: 提醒时间戳（毫秒），0 表示取消                             │
     │    - set: true=设置闹钟，false=取消闹钟                               │
     └─────────────────────────────────────────────────────────────────────┘
     * ┌─────────────────────────────────────────────────────────────────────┐
     │  onWidgetChanged()                                                   │
     │  - 触发时机：                                                        │
     │    1. saveNote() 成功保存关联小部件的笔记                             │
     │    2. markDeleted() 将关联小部件的笔记标记删除                        │
     │  - 用途：通知桌面小部件刷新显示内容                                    │
     └─────────────────────────────────────────────────────────────────────┘
     * ┌─────────────────────────────────────────────────────────────────────┐
     │  onCheckListModeChanged(int oldMode, int newMode)                     │
     │  - 触发时机：setCheckListMode() 检测到模式变化时                       │
     │  - 用途：UI 层处理清单模式的切换                                      │
     │  - 参数说明：                                                        │
     │    - oldMode: 切换前的模式                                           │
     │    - newMode: 切换后的模式                                            │
     │  - 使用建议：可用于实现模式切换的过渡动画或确认对话框                  │
     └─────────────────────────────────────────────────────────────────────┘
     */
    public interface NoteSettingChangedListener {
        /**
         * 当笔记背景颜色发生变更时调用
         * 
         * 使用场景：
         * - UI 层需要刷新笔记的背景 Drawable
         * - 可能需要同时更新关联小部件的背景
         */
        void onBackgroundColorChanged();

        /**
         * 当用户设置或取消闹钟提醒时调用
         * 
         * @param date  提醒时间戳（毫秒），0 表示取消提醒
         * @param set   true=设置闹钟提醒，false=取消闹钟提醒
         * 
         * 使用场景：
         * - 设置：使用 AlarmManager 设置定时闹钟
         * - 取消：使用 AlarmManager 取消已设置的闹钟
         */
        void onClockAlertChanged(long date, boolean set);

        /**
         * 当关联的小部件需要更新时调用
         * 
         * 触发场景：
         * 1. 笔记保存成功且关联了有效的小部件
         * 2. 关联小部件的笔记被标记删除
         * 
         * 使用场景：
         * - 调用 AppWidgetManager.updateAppWidget() 更新小部件
         * - 通知小部件刷新显示的笔记内容
         */
        void onWidgetChanged();

        /**
         * 当用户在普通模式和清单模式之间切换时调用
         * 
         * @param oldMode 切换前的模式（0=普通模式，1=清单模式）
         * @param newMode 切换后的模式（0=普通模式，1=清单模式）
         * 
         * 使用场景：
         * - UI 层重新渲染笔记内容视图
         * - 实现模式切换的过渡动画
         * - 显示模式切换确认对话框（如"切换后清单勾选状态会丢失"）
         */
        void onCheckListModeChanged(int oldMode, int newMode);
    }
}
