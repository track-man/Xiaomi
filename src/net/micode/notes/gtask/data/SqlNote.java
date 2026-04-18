/*
 * 小米便签 - 开源版本
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * 你可以在符合 Apache License 2.0 许可证的情况下使用此软件。
 * 你可以在以下网址获取许可证副本：
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * 除非适用法律要求或书面同意，根据许可证分发的软件是按"原样"提供的，
 * 不附带任何明示或暗示的保证或条件。
 * 有关许可证下特定语言的权限和限制，请参阅许可证。
 */

/**
 * SqlNote 类 - 本地笔记 SQL 数据包装器
 * 
 * 【设计意图】
 * SqlNote 是本地笔记数据与 ContentProvider 之间的桥梁类，属于 GTask 同步模块的一部分。
 * 它的主要职责是：
 * 1. 将笔记数据包装为可写入 SQLite 数据库的 ContentValues
 * 2. 从 ContentProvider 加载现有笔记数据到内存对象
 * 3. 支持 JSON 格式的数据导入导出，用于 GTask（Google Task）同步
 * 4. 使用 ContentValues diff 追踪模式，只记录实际变更的字段
 * 
 * 【与 model.Note 的关系】
 * - model.Note 是纯内存对象，用于应用内部
 * - SqlNote 是持久化包装器，用于与 ContentProvider 交互
 * - SqlNote 内部同样采用 diff 追踪模式，只记录变更字段以优化写入性能
 * 
 * 【ContentValues diff 追踪模式说明】
 * 与 model.Note 类似，SqlNote 使用 mDiffNoteValues 来追踪哪些字段被修改：
 * - 每次设置字段时，只有当值确实发生变化时才放入 mDiffNoteValues
 * - commit() 时只将 mDiffNoteValues 中的字段写入数据库
 * - 这种方式避免了全量更新，只执行增量更新
 * - 新建笔记时（mIsCreate=true），所有字段都会被放入 mDiffNoteValues
 * 
 * 【构造函数说明】
 * - SqlNote(Context): 创建新的笔记，所有字段使用默认值
 * - SqlNote(Context, Cursor): 从数据库 Cursor 加载现有笔记
 * - SqlNote(Context, long id): 根据 ID 从数据库加载现有笔记
 * 
 * 【JSON 内容格式】
 * getContent() 返回的 JSON 结构：
 * {
 *   "note": { ...笔记字段... },
 *   "data": [ ...SqlData数组，笔记的文本内容块... ]
 * }
 */
package net.micode.notes.gtask.data;

import android.appwidget.AppWidgetManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.exception.ActionFailureException;
import net.micode.notes.tool.GTaskStringUtils;
import net.micode.notes.tool.ResourceParser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;


/**
 * SqlNote 类 - 本地笔记的 SQL 包装器
 * 
 * 该类是 GTask 同步模块的核心组件，负责：
 * - 本地笔记数据的 CRUD 操作
 * - ContentValues diff 追踪（只记录变更字段）
 * - JSON 格式数据的序列化与反序列化
 * - 笔记与 SqlData（内容块）的管理
 */
public class SqlNote {
    /** 日志标签，用于调试输出 */
    private static final String TAG = SqlNote.class.getSimpleName();

    /** 
     * 无效ID的标记值
     * 用于区分"尚未分配ID"和"ID为0"的情况
     * 新建笔记时 mId 初始化为此值，commit() 成功后会获得真实ID
     */
    private static final int INVALID_ID = -99999;

    /**
     * 查询笔记时需要读取的列名数组
     * 
     * 【字段说明】
     * - ID: 笔记唯一标识符
     * - ALERTED_DATE: 闹钟提醒时间戳
     * - BG_COLOR_ID: 背景颜色ID
     * - CREATED_DATE: 创建时间
     * - HAS_ATTACHMENT: 是否含有附件（0=无，1=有）
     * - MODIFIED_DATE: 最后修改时间
     * - NOTES_COUNT: 子笔记数量（文件夹类型笔记使用）
     * - PARENT_ID: 父笔记/文件夹ID（用于构建笔记树结构）
     * - SNIPPET: 摘要文本（用于列表显示）
     * - TYPE: 笔记类型（TYPE_NOTE/TYPE_FOLDER/TYPE_SYSTEM）
     * - WIDGET_ID: 关联的小部件ID
     * - WIDGET_TYPE: 小部件类型
     * - SYNC_ID: 同步ID（用于区分本地和同步的笔记）
     * - LOCAL_MODIFIED: 本地是否已修改（用于同步冲突检测）
     * - ORIGIN_PARENT_ID: 原父文件夹ID（移动操作时记录原始位置）
     * - GTASK_ID: Google Task ID（同步用）
     * - VERSION: 版本号（用于乐观锁，支持并发修改检测）
     */
    public static final String[] PROJECTION_NOTE = new String[] {
            NoteColumns.ID, NoteColumns.ALERTED_DATE, NoteColumns.BG_COLOR_ID,
            NoteColumns.CREATED_DATE, NoteColumns.HAS_ATTACHMENT, NoteColumns.MODIFIED_DATE,
            NoteColumns.NOTES_COUNT, NoteColumns.PARENT_ID, NoteColumns.SNIPPET, NoteColumns.TYPE,
            NoteColumns.WIDGET_ID, NoteColumns.WIDGET_TYPE, NoteColumns.SYNC_ID,
            NoteColumns.LOCAL_MODIFIED, NoteColumns.ORIGIN_PARENT_ID, NoteColumns.GTASK_ID,
            NoteColumns.VERSION
    };

    /** Cursor 中 ID 列的索引位置 */
    public static final int ID_COLUMN = 0;

    /** Cursor 中 ALERTED_DATE 列的索引位置 */
    public static final int ALERTED_DATE_COLUMN = 1;

    /** Cursor 中 BG_COLOR_ID 列的索引位置 */
    public static final int BG_COLOR_ID_COLUMN = 2;

    /** Cursor 中 CREATED_DATE 列的索引位置 */
    public static final int CREATED_DATE_COLUMN = 3;

    /** Cursor 中 HAS_ATTACHMENT 列的索引位置 */
    public static final int HAS_ATTACHMENT_COLUMN = 4;

    /** Cursor 中 MODIFIED_DATE 列的索引位置 */
    public static final int MODIFIED_DATE_COLUMN = 5;

    /** Cursor 中 NOTES_COUNT 列的索引位置 */
    public static final int NOTES_COUNT_COLUMN = 6;

    /** Cursor 中 PARENT_ID 列的索引位置 */
    public static final int PARENT_ID_COLUMN = 7;

    /** Cursor 中 SNIPPET 列的索引位置 */
    public static final int SNIPPET_COLUMN = 8;

    /** Cursor 中 TYPE 列的索引位置 */
    public static final int TYPE_COLUMN = 9;

    /** Cursor 中 WIDGET_ID 列的索引位置 */
    public static final int WIDGET_ID_COLUMN = 10;

    /** Cursor 中 WIDGET_TYPE 列的索引位置 */
    public static final int WIDGET_TYPE_COLUMN = 11;

    /** Cursor 中 SYNC_ID 列的索引位置 */
    public static final int SYNC_ID_COLUMN = 12;

    /** Cursor 中 LOCAL_MODIFIED 列的索引位置 */
    public static final int LOCAL_MODIFIED_COLUMN = 13;

    /** Cursor 中 ORIGIN_PARENT_ID 列的索引位置 */
    public static final int ORIGIN_PARENT_ID_COLUMN = 14;

    /** Cursor 中 GTASK_ID 列的索引位置 */
    public static final int GTASK_ID_COLUMN = 15;

    /** Cursor 中 VERSION 列的索引位置 */
    public static final int VERSION_COLUMN = 16;

    /** Android 上下文，用于获取系统服务和资源 */
    private Context mContext;

    /** 内容解析器，用于与 ContentProvider 交互（查询/插入/更新/删除） */
    private ContentResolver mContentResolver;

    /** 
     * 是否为新建笔记的标记
     * - true: 通过无参构造函数创建，表示新笔记，需要执行 INSERT
     * - false: 通过 Cursor 或 ID 加载，表示已存在的笔记，需要执行 UPDATE
     */
    private boolean mIsCreate;

    /** 笔记在数据库中的唯一ID（_id），新建时为 INVALID_ID，commit 后获得真实ID */
    private long mId;

    /** 闹钟提醒日期时间戳（毫秒），0 表示无提醒 */
    private long mAlertDate;

    /** 笔记背景颜色ID */
    private int mBgColorId;

    /** 笔记创建时间戳（毫秒） */
    private long mCreatedDate;

    /** 是否包含附件标记（0=无附件，1=有附件） */
    private int mHasAttachment;

    /** 最后修改时间戳（毫秒） */
    private long mModifiedDate;

    /** 父笔记/文件夹的ID（用于构建树形结构），0 表示根级别 */
    private long mParentId;

    /** 笔记摘要文本（用于列表显示，通常是正文的前N个字符） */
    private String mSnippet;

    /** 笔记类型：TYPE_NOTE（普通笔记）、TYPE_FOLDER（文件夹）、TYPE_SYSTEM（系统文件夹） */
    private int mType;

    /** 关联桌面小部件的ID，INVALID_APPWIDGET_ID 表示无关联 */
    private int mWidgetId;

    /** 桌面小部件的类型（如单条笔记、文件夹等） */
    private int mWidgetType;

    /** 原父文件夹ID（用于移动操作，可追溯笔记原来的位置） */
    private long mOriginParent;

    /** 版本号（乐观锁机制，用于检测并发修改冲突） */
    private long mVersion;

    /**
     * 【核心设计】ContentValues diff 追踪器
     * 
     * diff 追踪模式的工作原理：
     * 1. 当调用 setter 方法修改字段时，只有当新值与旧值不同时，才将字段放入 mDiffNoteValues
     * 2. commit() 时只将 mDiffNoteValues 中的字段写入数据库，实现增量更新
     * 3. 新建笔记时（mIsCreate=true），所有非默认值的字段都会被放入
     * 4. 这种方式避免了全量更新，只执行必要的 SQL UPDATE 操作
     * 
     * 【与 model.Note 的区别】
     * - model.Note 使用 mModifiedFields 来追踪变更字段
     * - SqlNote 使用 mDiffNoteValues 来存储变更值（键值对形式，可直接用于 ContentValues）
     * - 两者都实现了类似的 diff 追踪思想，但具体实现略有不同
     */
    private ContentValues mDiffNoteValues;

    /** 
     * 笔记内容数据列表（SqlData 对象数组）
     * 
     * 一条笔记可以包含多个 SqlData（内容块），每个 SqlData 代表：
     * - 纯文本内容块（TYPE_TEXT）
     * - 列表/待办事项内容块（TYPE_LIST）
     * - 其他类型的扩展内容
     * 
     * 注意：只有 TYPE_NOTE 类型的笔记才会有 mDataList
     */
    private ArrayList<SqlData> mDataList;

    /**
     * 【构造函数】创建新的空白笔记
     * 
     * 【使用场景】
     * 当需要创建一个全新的笔记时使用此构造函数。
     * 
     * 【初始化行为】
     * - mIsCreate = true：标记为新建状态，后续 commit() 会执行 INSERT
     * - mId = INVALID_ID：尚未分配真实ID，commit() 成功后会更新为真实ID
     * - 所有字段使用默认值：
     *   - 创建/修改时间 = 当前时间
     *   - 类型 = TYPE_NOTE（普通笔记）
     *   - 背景色 = 默认背景色
     *   - 其他数值字段 = 0
     *   - 字符串字段 = 空字符串
     * - mDiffNoteValues 和 mDataList 初始化为空
     * 
     * 【后续操作】
     * 1. 调用 setContent(JSONObject) 设置笔记内容
     * 2. 调用 commit(boolean) 写入数据库
     */
    public SqlNote(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mIsCreate = true;  // 标记为新建状态
        mId = INVALID_ID;  // 尚未分配真实ID
        mAlertDate = 0;  // 无提醒
        mBgColorId = ResourceParser.getDefaultBgId(context);  // 使用默认背景色
        mCreatedDate = System.currentTimeMillis();  // 创建时间
        mHasAttachment = 0;  // 默认无附件
        mModifiedDate = System.currentTimeMillis();  // 修改时间
        mParentId = 0;  // 根级别
        mSnippet = "";  // 空摘要
        mType = Notes.TYPE_NOTE;  // 普通笔记
        mWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;  // 无关联小部件
        mWidgetType = Notes.TYPE_WIDGET_INVALIDE;  // 无效小部件类型
        mOriginParent = 0;  // 无原始父文件夹
        mVersion = 0;  // 初始版本
        mDiffNoteValues = new ContentValues();  // 空 diff 记录
        mDataList = new ArrayList<SqlData>();  // 空内容块列表
    }

    /**
     * 【构造函数】从 Cursor 加载现有笔记
     * 
     * 【使用场景】
     * 当需要从数据库加载一条已存在的笔记时使用。
     * 
     * 【初始化行为】
     * - mIsCreate = false：标记为已存在状态，后续 commit() 会执行 UPDATE
     * - 调用 loadFromCursor(Cursor) 从游标读取笔记字段
     * - 如果是 TYPE_NOTE 类型，调用 loadDataContent() 加载内容块列表
     * - mDiffNoteValues 初始化为空（diff 追踪起始状态）
     * 
     * 【参数说明】
     * @param context Android 上下文
     * @param c 已定位到目标记录的 Cursor 对象
     */
    public SqlNote(Context context, Cursor c) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mIsCreate = false;  // 标记为已存在状态
        loadFromCursor(c);  // 从游标加载笔记数据
        mDataList = new ArrayList<SqlData>();
        // 只有普通笔记才需要加载内容块列表
        if (mType == Notes.TYPE_NOTE)
            loadDataContent();
        mDiffNoteValues = new ContentValues();  // 空 diff 记录
    }

    /**
     * 【构造函数】根据 ID 从数据库加载现有笔记
     * 
     * 【使用场景】
     * 当已知笔记ID，需要加载完整笔记数据时使用。
     * 这是最常用的加载方式，通常用于同步操作时获取本地笔记。
     * 
     * 【初始化行为】
     * - mIsCreate = false：标记为已存在状态
     * - 调用 loadFromCursor(long) 根据ID查询数据库获取游标
     * - 然后调用 loadFromCursor(Cursor) 读取字段值
     * - 如果是 TYPE_NOTE 类型，加载内容块列表
     * 
     * 【参数说明】
     * @param context Android 上下文
     * @param id 笔记在数据库中的 _id
     */
    public SqlNote(Context context, long id) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mIsCreate = false;  // 标记为已存在状态
        loadFromCursor(id);  // 根据ID加载笔记
        mDataList = new ArrayList<SqlData>();
        // 只有普通笔记才需要加载内容块列表
        if (mType == Notes.TYPE_NOTE)
            loadDataContent();
        mDiffNoteValues = new ContentValues();  // 空 diff 记录

    }

    /**
     * 【私有方法】根据笔记ID从数据库加载笔记
     * 
     * 【执行流程】
     * 1. 通过 ContentResolver.query() 查询 Notes.CONTENT_NOTE_URI
     * 2. 使用 _id=? 条件过滤，参数化为防止 SQL 注入
     * 3. 获取 Cursor 后移动到第一行，调用重载的 loadFromCursor(Cursor)
     * 4. 最后关闭 Cursor 释放资源
     * 
     * 【参数说明】
     * @param id 笔记在数据库中的 _id
     */
    private void loadFromCursor(long id) {
        Cursor c = null;
        try {
            // 查询笔记表，使用参数化查询防止 SQL 注入
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI, PROJECTION_NOTE, "(_id=?)",
                    new String[] {
                        String.valueOf(id)
                    }, null);
            if (c != null) {
                c.moveToNext();  // 移动到第一行（应该只有一行）
                loadFromCursor(c);  // 调用重载方法解析游标
            } else {
                Log.w(TAG, "loadFromCursor: cursor = null，ID不存在或查询失败");
            }
        } finally {
            if (c != null)
                c.close();  // 确保游标被关闭
        }
    }

    /**
     * 【私有方法】从 Cursor 加载笔记字段
     * 
     * 【核心功能】
     * 将 Cursor 当前行的数据读取到 SqlNote 的成员变量中。
     * 使用列索引常量来获取对应列的值。
     * 
     * 【读取的字段】
     * - ID、闹钟日期、背景色、创建时间、附件标记、修改时间
     * - 父ID、摘要、类型、小部件ID、小部件类型、版本号
     * 
     * 【注意】
     * - 不读取 GTASK_ID、SYNC_ID 等同步相关字段（由调用方在 GTask 同步层处理）
     * - 不读取 NOTES_COUNT（这是计算字段，由触发器或查询维护）
     * 
     * @param c 已定位到目标行的 Cursor
     */
    private void loadFromCursor(Cursor c) {
        mId = c.getLong(ID_COLUMN);
        mAlertDate = c.getLong(ALERTED_DATE_COLUMN);
        mBgColorId = c.getInt(BG_COLOR_ID_COLUMN);
        mCreatedDate = c.getLong(CREATED_DATE_COLUMN);
        mHasAttachment = c.getInt(HAS_ATTACHMENT_COLUMN);
        mModifiedDate = c.getLong(MODIFIED_DATE_COLUMN);
        mParentId = c.getLong(PARENT_ID_COLUMN);
        mSnippet = c.getString(SNIPPET_COLUMN);
        mType = c.getInt(TYPE_COLUMN);
        mWidgetId = c.getInt(WIDGET_ID_COLUMN);
        mWidgetType = c.getInt(WIDGET_TYPE_COLUMN);
        mVersion = c.getLong(VERSION_COLUMN);
    }

    /**
     * 【私有方法】加载笔记的内容块列表（SqlData）
     * 
     * 【功能说明】
     * 对于 TYPE_NOTE 类型的笔记，需要加载其关联的内容块数据。
     * 每个内容块存储在 notes_data 表中，通过 note_id 关联到笔记。
     * 
     * 【执行流程】
     * 1. 查询 Notes.CONTENT_DATA_URI（notes_data 表）
     * 2. 使用 note_id=? 条件过滤获取该笔记的所有内容块
     * 3. 为每个内容块创建 SqlData 对象并添加到 mDataList
     * 
     * 【数据关系】
     * notes（笔记表） --[1:N]--> notes_data（数据表）
     * 一条笔记可以有多个内容块（如文本块、列表块等）
     */
    private void loadDataContent() {
        Cursor c = null;
        mDataList.clear();  // 先清空现有列表
        try {
            // 查询该笔记关联的所有内容块
            c = mContentResolver.query(Notes.CONTENT_DATA_URI, SqlData.PROJECTION_DATA,
                    "(note_id=?)", new String[] {
                        String.valueOf(mId)
                    }, null);
            if (c != null) {
                if (c.getCount() == 0) {
                    // 正常情况下笔记应该有内容块，如果没有则记录警告
                    Log.w(TAG, "笔记似乎没有任何内容块数据");
                    return;
                }
                // 遍历所有内容块，创建 SqlData 对象
                while (c.moveToNext()) {
                    SqlData data = new SqlData(mContext, c);
                    mDataList.add(data);
                }
            } else {
                Log.w(TAG, "loadDataContent: cursor = null");
            }
        } finally {
            if (c != null)
                c.close();  // 确保游标被关闭
        }
    }

    /**
     * 【公开方法】设置笔记内容（从 JSON 解析）
     * 
     * 【功能说明】
     * 从 GTask 同步传入的 JSONObject 中解析笔记数据，
     * 并将变更的字段记录到 mDiffNoteValues 中。
     * 这是 GTask 同步时将云端数据写入本地数据库的关键入口。
     * 
     * 【JSON 输入格式】
     * {
     *   "note": {
     *     "id": 123,           // 笔记ID
     *     "type": 0,           // 类型（0=笔记，1=文件夹，2=系统文件夹）
     *     "snippet": "...",    // 摘要
     *     ...其他字段...
     *   },
     *   "data": [              // 仅 TYPE_NOTE 有此字段
     *     { "id": 1, "content": "...", "mime": "text/plain" },
     *     { "id": 2, "content": "- [ ] 待办", "mime": "text/plain" }
     *   ]
     * }
     * 
     * 【diff 追踪逻辑】
     * 对于每个字段：
     * - 如果是新建笔记（mIsCreate=true），直接放入 mDiffNoteValues
     * - 如果是更新现有笔记（mIsCreate=false），只有当值确实改变时才放入
     * - 这种方式实现了增量更新，只写入真正变化的字段
     * 
     * 【处理逻辑分支】
     * 1. TYPE_SYSTEM：系统文件夹，不允许设置
     * 2. TYPE_FOLDER：文件夹类型，只能更新 snippet 和 type
     * 3. TYPE_NOTE：普通笔记，更新所有字段和内容块列表
     * 
     * @param js 包含笔记数据的 JSON 对象
     * @return 是否解析成功
     */
    public boolean setContent(JSONObject js) {
        try {
            // 解析 JSON 中的笔记对象
            JSONObject note = js.getJSONObject(GTaskStringUtils.META_HEAD_NOTE);
            
            // 获取笔记类型
            int noteType = note.getInt(NoteColumns.TYPE);
            
            // 【分支1】系统文件夹类型 - 不允许设置
            if (noteType == Notes.TYPE_SYSTEM) {
                Log.w(TAG, "无法设置系统文件夹");
            
            // 【分支2】文件夹类型 - 只能更新摘要和类型
            } else if (noteType == Notes.TYPE_FOLDER) {
                // 解析摘要
                String snippet = note.has(NoteColumns.SNIPPET) ? note
                        .getString(NoteColumns.SNIPPET) : "";
                // diff 追踪：只有新建或值改变时才记录
                if (mIsCreate || !mSnippet.equals(snippet)) {
                    mDiffNoteValues.put(NoteColumns.SNIPPET, snippet);
                }
                mSnippet = snippet;

                // 解析类型
                int type = note.has(NoteColumns.TYPE) ? note.getInt(NoteColumns.TYPE)
                        : Notes.TYPE_NOTE;
                if (mIsCreate || mType != type) {
                    mDiffNoteValues.put(NoteColumns.TYPE, type);
                }
                mType = type;
            
            // 【分支3】普通笔记类型 - 更新所有字段和内容块
            } else if (noteType == Notes.TYPE_NOTE) {
                // 解析内容块数组（用于同步笔记正文内容）
                JSONArray dataArray = js.getJSONArray(GTaskStringUtils.META_HEAD_DATA);
                
                // === 解析并更新各个字段 ===
                
                // ID
                long id = note.has(NoteColumns.ID) ? note.getLong(NoteColumns.ID) : INVALID_ID;
                if (mIsCreate || mId != id) {
                    mDiffNoteValues.put(NoteColumns.ID, id);
                }
                mId = id;

                // 闹钟提醒日期
                long alertDate = note.has(NoteColumns.ALERTED_DATE) ? note
                        .getLong(NoteColumns.ALERTED_DATE) : 0;
                if (mIsCreate || mAlertDate != alertDate) {
                    mDiffNoteValues.put(NoteColumns.ALERTED_DATE, alertDate);
                }
                mAlertDate = alertDate;

                // 背景颜色
                int bgColorId = note.has(NoteColumns.BG_COLOR_ID) ? note
                        .getInt(NoteColumns.BG_COLOR_ID) : ResourceParser.getDefaultBgId(mContext);
                if (mIsCreate || mBgColorId != bgColorId) {
                    mDiffNoteValues.put(NoteColumns.BG_COLOR_ID, bgColorId);
                }
                mBgColorId = bgColorId;

                // 创建时间
                long createDate = note.has(NoteColumns.CREATED_DATE) ? note
                        .getLong(NoteColumns.CREATED_DATE) : System.currentTimeMillis();
                if (mIsCreate || mCreatedDate != createDate) {
                    mDiffNoteValues.put(NoteColumns.CREATED_DATE, createDate);
                }
                mCreatedDate = createDate;

                // 附件标记
                int hasAttachment = note.has(NoteColumns.HAS_ATTACHMENT) ? note
                        .getInt(NoteColumns.HAS_ATTACHMENT) : 0;
                if (mIsCreate || mHasAttachment != hasAttachment) {
                    mDiffNoteValues.put(NoteColumns.HAS_ATTACHMENT, hasAttachment);
                }
                mHasAttachment = hasAttachment;

                // 修改时间
                long modifiedDate = note.has(NoteColumns.MODIFIED_DATE) ? note
                        .getLong(NoteColumns.MODIFIED_DATE) : System.currentTimeMillis();
                if (mIsCreate || mModifiedDate != modifiedDate) {
                    mDiffNoteValues.put(NoteColumns.MODIFIED_DATE, modifiedDate);
                }
                mModifiedDate = modifiedDate;

                // 父文件夹ID
                long parentId = note.has(NoteColumns.PARENT_ID) ? note
                        .getLong(NoteColumns.PARENT_ID) : 0;
                if (mIsCreate || mParentId != parentId) {
                    mDiffNoteValues.put(NoteColumns.PARENT_ID, parentId);
                }
                mParentId = parentId;

                // 摘要
                String snippet = note.has(NoteColumns.SNIPPET) ? note
                        .getString(NoteColumns.SNIPPET) : "";
                if (mIsCreate || !mSnippet.equals(snippet)) {
                    mDiffNoteValues.put(NoteColumns.SNIPPET, snippet);
                }
                mSnippet = snippet;

                // 类型
                int type = note.has(NoteColumns.TYPE) ? note.getInt(NoteColumns.TYPE)
                        : Notes.TYPE_NOTE;
                if (mIsCreate || mType != type) {
                    mDiffNoteValues.put(NoteColumns.TYPE, type);
                }
                mType = type;

                // 小部件ID
                int widgetId = note.has(NoteColumns.WIDGET_ID) ? note.getInt(NoteColumns.WIDGET_ID)
                        : AppWidgetManager.INVALID_APPWIDGET_ID;
                if (mIsCreate || mWidgetId != widgetId) {
                    mDiffNoteValues.put(NoteColumns.WIDGET_ID, widgetId);
                }
                mWidgetId = widgetId;

                // 小部件类型
                int widgetType = note.has(NoteColumns.WIDGET_TYPE) ? note
                        .getInt(NoteColumns.WIDGET_TYPE) : Notes.TYPE_WIDGET_INVALIDE;
                if (mIsCreate || mWidgetType != widgetType) {
                    mDiffNoteValues.put(NoteColumns.WIDGET_TYPE, widgetType);
                }
                mWidgetType = widgetType;

                // 原父文件夹ID
                long originParent = note.has(NoteColumns.ORIGIN_PARENT_ID) ? note
                        .getLong(NoteColumns.ORIGIN_PARENT_ID) : 0;
                if (mIsCreate || mOriginParent != originParent) {
                    mDiffNoteValues.put(NoteColumns.ORIGIN_PARENT_ID, originParent);
                }
                mOriginParent = originParent;

                // === 处理内容块列表（SqlData）===
                // 遍历 JSON 中的每个数据块，与现有的 mDataList 进行匹配或新增
                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject data = dataArray.getJSONObject(i);
                    SqlData sqlData = null;
                    
                    // 尝试在现有列表中找到匹配的 SqlData（通过 ID 匹配）
                    if (data.has(DataColumns.ID)) {
                        long dataId = data.getLong(DataColumns.ID);
                        for (SqlData temp : mDataList) {
                            if (dataId == temp.getId()) {
                                sqlData = temp;
                                break;  // 找到匹配，跳出循环
                            }
                        }
                    }

                    // 如果没找到匹配的，创建新的 SqlData
                    if (sqlData == null) {
                        sqlData = new SqlData(mContext);
                        mDataList.add(sqlData);
                    }

                    // 调用 SqlData.setContent() 解析该数据块的内容
                    sqlData.setContent(data);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * 【公开方法】获取笔记的 JSON 表示
     * 
     * 【功能说明】
     * 将 SqlNote 对象序列化为 JSONObject，用于 GTask 同步时上传到云端。
     * 这是将本地笔记数据转换为同步格式的关键方法。
     * 
     * 【前置条件】
     * - 必须是已存在的笔记（mIsCreate = false）
     * - 如果是新建笔记（尚未 commit），返回 null 并记录错误
     * 
     * 【JSON 输出格式】
     * 
     * 普通笔记（TYPE_NOTE）：
     * {
     *   "note": {
     *     "id": 123,                    // 笔记ID
     *     "alerted_date": 0,            // 闹钟时间戳
     *     "bg_color_id": 0,             // 背景色ID
     *     "created_date": 1234567890,   // 创建时间
     *     "has_attachment": 0,          // 附件标记
     *     "modified_date": 1234567890, // 修改时间
     *     "parent_id": 0,               // 父文件夹ID
     *     "snippet": "笔记内容...",      // 摘要
     *     "type": 0,                    // 类型（0=笔记）
     *     "widget_id": -1,              // 小部件ID
     *     "widget_type": 0,            // 小部件类型
     *     "origin_parent_id": 0        // 原父文件夹ID
     *   },
     *   "data": [                       // 内容块数组
     *     { "id": 1, "content": "...", "mime": "text/plain" },
     *     ...
     *   ]
     * }
     * 
     * 文件夹（TYPE_FOLDER / TYPE_SYSTEM）：
     * {
     *   "note": {
     *     "id": 456,           // 文件夹ID
     *     "type": 1,           // 类型（1=文件夹，2=系统文件夹）
     *     "snippet": "文件夹名" // 文件夹名称
     *   }
     * }
     * 【注意】文件夹不包含 "data" 字段
     * 
     * 【返回值】
     * @return JSONObject 包含笔记数据的 JSON 对象，失败返回 null
     */
    public JSONObject getContent() {
        try {
            JSONObject js = new JSONObject();

            // 检查是否为新建笔记（尚未 commit）
            if (mIsCreate) {
                Log.e(TAG, "该笔记尚未创建到数据库中，无法获取 JSON 内容");
                return null;
            }

            // 创建"note"节点
            JSONObject note = new JSONObject();
            
            if (mType == Notes.TYPE_NOTE) {
                // 【普通笔记】包含所有字段和内容块
                
                // 写入所有笔记字段
                note.put(NoteColumns.ID, mId);
                note.put(NoteColumns.ALERTED_DATE, mAlertDate);
                note.put(NoteColumns.BG_COLOR_ID, mBgColorId);
                note.put(NoteColumns.CREATED_DATE, mCreatedDate);
                note.put(NoteColumns.HAS_ATTACHMENT, mHasAttachment);
                note.put(NoteColumns.MODIFIED_DATE, mModifiedDate);
                note.put(NoteColumns.PARENT_ID, mParentId);
                note.put(NoteColumns.SNIPPET, mSnippet);
                note.put(NoteColumns.TYPE, mType);
                note.put(NoteColumns.WIDGET_ID, mWidgetId);
                note.put(NoteColumns.WIDGET_TYPE, mWidgetType);
                note.put(NoteColumns.ORIGIN_PARENT_ID, mOriginParent);
                
                // 将 note 对象放入根 JSON
                js.put(GTaskStringUtils.META_HEAD_NOTE, note);

                // 构建内容块数组（"data" 节点）
                JSONArray dataArray = new JSONArray();
                for (SqlData sqlData : mDataList) {
                    JSONObject data = sqlData.getContent();
                    if (data != null) {
                        dataArray.put(data);
                    }
                }
                js.put(GTaskStringUtils.META_HEAD_DATA, dataArray);
                
            } else if (mType == Notes.TYPE_FOLDER || mType == Notes.TYPE_SYSTEM) {
                // 【文件夹】只包含基本信息
                
                note.put(NoteColumns.ID, mId);
                note.put(NoteColumns.TYPE, mType);
                note.put(NoteColumns.SNIPPET, mSnippet);
                js.put(GTaskStringUtils.META_HEAD_NOTE, note);
                // 注意：文件夹没有 "data" 字段
            }

            return js;
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 【公开方法】设置笔记的父文件夹ID
     * 
     * 用于移动笔记到其他文件夹。
     * 直接将新值放入 mDiffNoteValues（不检查是否真的改变了），
     * 因为移动操作总是需要记录的。
     * 
     * @param id 新的父文件夹ID
     */
    public void setParentId(long id) {
        mParentId = id;
        mDiffNoteValues.put(NoteColumns.PARENT_ID, id);  // 直接记录变更
    }

    /**
     * 【公开方法】设置 Google Task ID
     * 
     * 用于记录笔记在 Google Task 服务端的唯一标识，
     * 这是 GTask 同步的核心字段，用于建立本地笔记与云端任务的映射关系。
     * 
     * @param gid Google Task 服务端的任务ID
     */
    public void setGtaskId(String gid) {
        mDiffNoteValues.put(NoteColumns.GTASK_ID, gid);
    }

    /**
     * 【公开方法】设置同步ID
     * 
     * 用于区分本地创建和从服务器同步的笔记。
     * 
     * @param syncId 同步ID
     */
    public void setSyncId(long syncId) {
        mDiffNoteValues.put(NoteColumns.SYNC_ID, syncId);
    }

    /**
     * 【公开方法】重置本地修改标记
     * 
     * 将 LOCAL_MODIFIED 设为 0，表示该笔记已与服务器同步，
     * 不再有未同步的本地修改。
     * 通常在同步完成后调用。
     */
    public void resetLocalModified() {
        mDiffNoteValues.put(NoteColumns.LOCAL_MODIFIED, 0);
    }

    /**
     * 【公开方法】获取笔记ID
     * @return 笔记在数据库中的 _id
     */
    public long getId() {
        return mId;
    }

    /**
     * 【公开方法】获取父文件夹ID
     * @return 父笔记/文件夹的ID，0 表示根级别
     */
    public long getParentId() {
        return mParentId;
    }

    /**
     * 【公开方法】获取笔记摘要
     * @return 摘要文本（用于列表显示）
     */
    public String getSnippet() {
        return mSnippet;
    }

    /**
     * 【公开方法】判断是否为普通笔记类型
     * @return true 如果是 TYPE_NOTE，false 如果是文件夹
     */
    public boolean isNoteType() {
        return mType == Notes.TYPE_NOTE;
    }

    /**
     * 【核心方法】提交笔记到数据库
     * 
     * 【功能说明】
     * 这是 SqlNote 最重要的方法，负责将内存中的数据持久化到 SQLite 数据库。
     * 根据 mIsCreate 标志决定执行 INSERT 还是 UPDATE。
     * 
     * 【commit 完整流程】
     * 
     * 【分支A】新建笔记（mIsCreate = true）
     * 1. 移除 mDiffNoteValues 中的 ID（如果是 INVALID_ID）
     *    - 因为新建笔记时不应指定ID，由数据库自动生成
     * 2. 调用 ContentResolver.insert() 插入新记录
     * 3. 从返回的 URI 中解析出新记录的 _id
     *    - URI 格式：content://net.micode.notes/notes/123
     *    - 从路径段中获取 ID = 123
     * 4. 如果 ID 为 0，抛出异常
     * 5. 如果是 TYPE_NOTE，遍历 mDataList 调用 sqlData.commit() 插入内容块
     *    - 注意：内容块需要知道所属笔记的ID，所以先插入笔记，再插入内容块
     * 
     * 【分支B】更新现有笔记（mIsCreate = false）
     * 1. 检查 ID 有效性（不能 <= 0，除非是根文件夹或通话记录文件夹）
     * 2. 如果 mDiffNoteValues 不为空（有变更的字段）：
     *    - 版本号递增（mVersion++）
     *    - 调用 ContentResolver.update() 更新记录
     *    - 可选：版本验证模式（乐观锁）
     *      - validateVersion=true 时添加 VERSION<=? 条件
     *      - 确保只有版本未改变的记录才被更新（防止覆盖他人修改）
     *    - 如果 result==0，说明没有记录被更新（可能已被其他操作修改）
     * 3. 如果是 TYPE_NOTE，遍历 mDataList 调用 sqlData.commit() 更新内容块
     * 
     * 【最后处理】
     * - 调用 loadFromCursor(mId) 重新加载数据，确保与数据库一致
     * - 如果是 TYPE_NOTE，调用 loadDataContent() 重新加载内容块
     * - 清空 mDiffNoteValues（已提交的变更不再需要追踪）
     * - 设置 mIsCreate = false（从此不再是新建状态）
     * 
     * 【参数说明】
     * @param validateVersion 是否启用版本验证（乐观锁）
     *        - true：更新时检查版本号，确保不会覆盖并发修改
     *        - false：不检查版本号，直接更新
     * 
     * 【抛出异常】
     * - ActionFailureException：创建笔记失败
     * - IllegalStateException：ID 无效或版本冲突
     */
    public void commit(boolean validateVersion) {
        // ==================== 【分支A】新建笔记 ====================
        if (mIsCreate) {
            // 【步骤A1】处理ID字段
            // 如果 mId 是 INVALID_ID 但 mDiffNoteValues 中有 ID 键，移除它
            // 因为新建笔记时ID应由数据库自动生成，不应手动指定
            if (mId == INVALID_ID && mDiffNoteValues.containsKey(NoteColumns.ID)) {
                mDiffNoteValues.remove(NoteColumns.ID);
            }

            // 【步骤A2】执行 INSERT 操作
            Uri uri = mContentResolver.insert(Notes.CONTENT_NOTE_URI, mDiffNoteValues);
            
            // 【步骤A3】从返回的 URI 中解析新记录的 ID
            // URI 格式示例：content://net.micode.notes/notes/123
            // getPathSegments() 返回 ["notes", "123"]，get(1) 即为 ID
            try {
                mId = Long.valueOf(uri.getPathSegments().get(1));
            } catch (NumberFormatException e) {
                Log.e(TAG, "获取笔记ID失败：" + e.toString());
                throw new ActionFailureException("创建笔记失败");
            }
            
            // 【步骤A4】验证 ID 有效性
            if (mId == 0) {
                throw new IllegalStateException("创建笔记ID失败，ID为0");
            }

            // 【步骤A5】插入内容块（仅普通笔记）
            // 注意：内容块需要关联到所属笔记，所以必须先有笔记ID
            if (mType == Notes.TYPE_NOTE) {
                for (SqlData sqlData : mDataList) {
                    // false：不验证版本；-1：版本号不适用
                    sqlData.commit(mId, false, -1);
                }
            }
            
        // ==================== 【分支B】更新现有笔记 ====================
        } else {
            // 【步骤B1】验证 ID 有效性
            // ID 不能 <= 0，但根文件夹(ID_ROOT_FOLDER)和通话记录文件夹(ID_CALL_RECORD_FOLDER)例外
            if (mId <= 0 && mId != Notes.ID_ROOT_FOLDER && mId != Notes.ID_CALL_RECORD_FOLDER) {
                Log.e(TAG, "笔记不存在，ID无效");
                throw new IllegalStateException("尝试用无效ID更新笔记");
            }
            
            // 【步骤B2】检查是否有变更需要提交
            if (mDiffNoteValues.size() > 0) {
                // 递增版本号（乐观锁）
                mVersion++;
                int result = 0;
                
                if (!validateVersion) {
                    // 【简单模式】直接更新，不检查版本
                    result = mContentResolver.update(Notes.CONTENT_NOTE_URI, mDiffNoteValues, "("
                            + NoteColumns.ID + "=?)", new String[] {
                        String.valueOf(mId)
                    });
                } else {
                    // 【版本验证模式】确保版本未改变时才更新
                    // SQL: UPDATE ... WHERE _id=? AND version<=?
                    // 这确保了只有当本地版本与数据库版本一致时才更新
                    // 如果有其他操作同时修改了该笔记，本次更新会失败
                    result = mContentResolver.update(Notes.CONTENT_NOTE_URI, mDiffNoteValues, "("
                            + NoteColumns.ID + "=?) AND (" + NoteColumns.VERSION + "<=?)",
                            new String[] {
                                    String.valueOf(mId), String.valueOf(mVersion)
                            });
                }
                
                // 【步骤B3】检查更新结果
                if (result == 0) {
                    // 没有记录被更新，可能是因为：
                    // 1. 笔记已被其他操作删除
                    // 2. 在版本验证模式下，版本号已改变（并发冲突）
                    Log.w(TAG, "没有记录被更新，可能用户在同步时修改了笔记");
                }
            }

            // 【步骤B4】更新内容块（仅普通笔记）
            if (mType == Notes.TYPE_NOTE) {
                for (SqlData sqlData : mDataList) {
                    // 传入 validateVersion 和当前版本号
                    sqlData.commit(mId, validateVersion, mVersion);
                }
            }
        }

        // ==================== 【后处理】 ====================
        
        // 【后处理1】重新加载数据确保一致性
        // commit 后需要重新从数据库读取，确保：
        // 1. 获取数据库生成的值（如自动递增ID）
        // 2. 获取 commit 过程中可能改变的字段（如 version）
        // 3. 同步层需要知道 commit 后的最终状态
        loadFromCursor(mId);
        if (mType == Notes.TYPE_NOTE)
            loadDataContent();

        // 【后处理2】清理 diff 追踪器
        // 已提交的变更不再需要追踪
        mDiffNoteValues.clear();
        
        // 【后处理3】标记为非新建状态
        // 以后对同一笔记的操作都走 UPDATE 分支
        mIsCreate = false;
    }
}
