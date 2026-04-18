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

package net.micode.notes.data;


import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.R;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.NotesDatabaseHelper.TABLE;


/**
 * NotesProvider - 便签数据内容提供者
 * 
 * 功能说明：
 * 本类实现了 Android 的 ContentProvider 接口，作为便签应用的数据访问入口。
 * 它封装了对 SQLite 数据库的 CRUD（创建、读取、更新、删除）操作，
 * 并通过 URI 匹配机制对外提供统一的数据访问接口。
 * 
 * 支持的数据表：
 * 1. NOTE 表 - 存储便签的基本信息（标题、摘要、创建时间等）
 * 2. DATA 表 - 存储便签的详细数据（如文本内容、媒体附件等）
 * 
 * URI 模式：
 * - content://net.micode.notes/note          -> 便签列表（查询所有便签）
 * - content://net.micode.notes/note/#        -> 单个便签（# 为便签ID）
 * - content://net.micode.notes/data          -> 数据列表（查询所有数据）
 * - content://net.micode.notes/data/#        -> 单条数据（# 为数据ID）
 * - content://net.micode.notes/search        -> 搜索便签（需传入 pattern 参数）
 * - content://net.micode.notes/search_suggest_query    -> 搜索建议（快速搜索）
 * - content://net.micode.notes/search_suggest_query/* -> 带查询词的搜索建议
 * 
 * 使用方式：
 * 其他组件（如 Activity、Service）通过 ContentResolver 来访问本 Provider：
 * - 查询：getContentResolver().query(uri, ...)
 * - 插入：getContentResolver().insert(uri, values)
 * - 更新：getContentResolver().update(uri, values, ...)
 * - 删除：getContentResolver().delete(uri, ...)
 */
public class NotesProvider extends ContentProvider {
    
    /** URI 匹配器，用于根据 URI 判断客户端请求的操作类型 */
    private static final UriMatcher mMatcher;

    /** 数据库帮助类实例，用于获取数据库连接 */
    private NotesDatabaseHelper mHelper;

    /** 日志标签，用于区分不同模块的日志输出 */
    private static final String TAG = "NotesProvider";

    // ============================================================
    // URI 匹配码常量定义
    // 这些常量用于在 switch 语句中区分不同的 URI 请求类型
    // ============================================================
    
    /** URI_NOTE: 便签列表 - content://authority/note */
    private static final int URI_NOTE            = 1;
    
    /** URI_NOTE_ITEM: 单个便签 - content://authority/note/# */
    private static final int URI_NOTE_ITEM       = 2;
    
    /** URI_DATA: 数据列表 - content://authority/data */
    private static final int URI_DATA            = 3;
    
    /** URI_DATA_ITEM: 单条数据 - content://authority/data/# */
    private static final int URI_DATA_ITEM       = 4;
    
    /** URI_SEARCH: 搜索便签 - content://authority/search */
    private static final int URI_SEARCH          = 5;
    
    /** URI_SEARCH_SUGGEST: 搜索建议 - content://authority/search_suggest_query */
    private static final int URI_SEARCH_SUGGEST  = 6;

    // ============================================================
    // 静态初始化块 - 配置 URI 匹配规则
    // ============================================================
    
    static {
        // 创建 URI 匹配器，NO_MATCH 表示不匹配时返回 -1
        mMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        
        /**
         * 添加 URI 匹配规则：
         * 
         * 1. "note" 匹配 content://net.micode.notes/note
         *    用于获取所有便签列表（不带 ID 的查询、插入操作）
         */
        mMatcher.addURI(Notes.AUTHORITY, "note", URI_NOTE);
        
        /**
         * 2. "note/#" 匹配 content://net.micode.notes/note/123
         *    # 号是通配符，匹配任意数字，用于操作单个便签（查询、更新、删除）
         */
        mMatcher.addURI(Notes.AUTHORITY, "note/#", URI_NOTE_ITEM);
        
        /**
         * 3. "data" 匹配 content://net.micode.notes/data
         *    用于获取所有数据记录（批量查询、插入）
         */
        mMatcher.addURI(Notes.AUTHORITY, "data", URI_DATA);
        
        /**
         * 4. "data/#" 匹配 content://net.micode.notes/data/456
         *    # 号匹配数据记录 ID，用于操作单条数据
         */
        mMatcher.addURI(Notes.AUTHORITY, "data/#", URI_DATA_ITEM);
        
        /**
         * 5. "search" 匹配 content://net.micode.notes/search
         *    用于全功能搜索，需要通过查询参数 "pattern" 传入搜索关键词
         *    示例：content://net.micode.notes/search?pattern=关键词
         */
        mMatcher.addURI(Notes.AUTHORITY, "search", URI_SEARCH);
        
        /**
         * 6. "search_suggest_query" 匹配 Android 系统搜索建议的标准路径
         *    用于在用户输入时提供实时搜索建议
         *    SearchManager.SUGGEST_URI_PATH_QUERY 是 Android 搜索框架定义的标准常量
         */
        mMatcher.addURI(Notes.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY, URI_SEARCH_SUGGEST);
        
        /**
         * 7. "search_suggest_query/*" 匹配带搜索词的搜索建议路径
         *    /* 是第二个通配符，匹配搜索词本身
         *    示例：content://net.micode.notes/search_suggest_query/小米
         */
        mMatcher.addURI(Notes.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*", URI_SEARCH_SUGGEST);
    }

    // ============================================================
    // 搜索功能相关常量
    // ============================================================
    
    /**
     * 搜索结果投影（Projection）定义
     * 
     * 说明：
     * - x'0A' 是 SQLite 中表示换行符 '\n' 的十六进制形式
     * - 在搜索结果中，需要将换行符和空白字符trim掉，以便在搜索建议列表中显示更多信息
     * 
     * 投影包含以下列：
     * 1. ID - 便签的唯一标识符
     * 2. SUGGEST_COLUMN_INTENT_EXTRA_DATA - 用于点击搜索结果时传递便签ID
     * 3. SUGGEST_COLUMN_TEXT_1 - 搜索结果的第一行文本（便签摘要/标题）
     * 4. SUGGEST_COLUMN_TEXT_2 - 搜索结果的第二行文本（同样使用便签摘要）
     * 5. SUGGEST_COLUMN_ICON_1 - 搜索结果图标（使用应用定义的搜索结果图标）
     * 6. SUGGEST_COLUMN_INTENT_ACTION - 点击搜索结果时的动作（ACTION_VIEW 打开便签）
     * 7. SUGGEST_COLUMN_INTENT_DATA - 点击搜索结果时打开的便签数据类型
     */
    private static final String NOTES_SEARCH_PROJECTION = NoteColumns.ID + ","
        + NoteColumns.ID + " AS " + SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA + ","
        + "TRIM(REPLACE(" + NoteColumns.SNIPPET + ", x'0A','')) AS " + SearchManager.SUGGEST_COLUMN_TEXT_1 + ","
        + "TRIM(REPLACE(" + NoteColumns.SNIPPET + ", x'0A','')) AS " + SearchManager.SUGGEST_COLUMN_TEXT_2 + ","
        + R.drawable.search_result + " AS " + SearchManager.SUGGEST_COLUMN_ICON_1 + ","
        + "'" + Intent.ACTION_VIEW + "' AS " + SearchManager.SUGGEST_COLUMN_INTENT_ACTION + ","
        + "'" + Notes.TextNote.CONTENT_TYPE + "' AS " + SearchManager.SUGGEST_COLUMN_INTENT_DATA;

    /**
     * 便签片段搜索查询语句
     * 
     * 功能：
     * 在 NOTE 表中搜索摘要（SNIPPET）包含指定关键词的便签
     * 
     * 查询条件说明：
     * 1. SNIPPET LIKE ? - 便签摘要包含搜索关键词（使用 LIKE 进行模糊匹配）
     * 2. PARENT_ID <> Notes.ID_TRASH_FOLER - 排除垃圾桶中的便签（不在废纸篓中）
     * 3. TYPE = Notes.TYPE_NOTE - 只搜索普通便签类型，不搜索文件夹等
     * 
     * 参数绑定：
     * - 第一个 ? 参数会被替换为格式化后的搜索字符串（添加了 % 通配符）
     *   例如：用户搜索"小米"，实际查询时变为 "%小米%"
     */
    private static String NOTES_SNIPPET_SEARCH_QUERY = "SELECT " + NOTES_SEARCH_PROJECTION
        + " FROM " + TABLE.NOTE
        + " WHERE " + NoteColumns.SNIPPET + " LIKE ?"
        + " AND " + NoteColumns.PARENT_ID + "<>" + Notes.ID_TRASH_FOLER
        + " AND " + NoteColumns.TYPE + "=" + Notes.TYPE_NOTE;

    // ============================================================
    // ContentProvider 生命周期方法
    // ============================================================

    /**
     * onCreate - Provider 创建时的初始化回调
     * 
     * 执行时机：
     * 当 ContentProvider 被首次请求时，系统会调用此方法，且只调用一次
     * 
     * 初始化工作：
     * - 获取 NotesDatabaseHelper 单例实例
     * - 数据库帮助类负责创建/升级数据库，确保数据库就绪
     * 
     * @return true 表示初始化成功，Provider 可以正常工作
     */
    @Override
    public boolean onCreate() {
        // 获取数据库帮助类单例（使用单例模式确保只有一个数据库连接）
        mHelper = NotesDatabaseHelper.getInstance(getContext());
        return true;
    }

    // ============================================================
    // CRUD 操作实现
    // ============================================================

    /**
     * query - 查询数据
     * 
     * 功能：
     * 根据传入的 URI 查询便签或数据记录
     * 
     * URI 支持：
     * - URI_NOTE: 查询所有便签
     * - URI_NOTE_ITEM: 根据 ID 查询单个便签
     * - URI_DATA: 查询所有数据
     * - URI_DATA_ITEM: 根据 ID 查询单条数据
     * - URI_SEARCH: 全功能搜索（需 pattern 参数）
     * - URI_SEARCH_SUGGEST: 搜索建议（快速搜索）
     * 
     * 参数说明：
     * @param uri 要查询的数据 URI
     * @param projection 要返回的列名数组，null 表示返回所有列
     * @param selection WHERE 条件语句
     * @param selectionArgs WHERE 条件中的参数值数组
     * @param sortOrder 结果排序方式
     * @return 包含查询结果的 Cursor 对象
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        Cursor c = null;
        
        // 获取可读数据库实例
        SQLiteDatabase db = mHelper.getReadableDatabase();
        
        // 从 URI 中提取 ID（用于单条记录查询）
        String id = null;
        
        // 根据 URI 匹配码执行不同的查询逻辑
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                /**
                 * URI_NOTE: 查询所有便签
                 * 直接查询 NOTE 表，使用完整的 selection、selectionArgs 和 sortOrder
                 */
                c = db.query(TABLE.NOTE, projection, selection, selectionArgs, null, null,
                        sortOrder);
                break;
                
            case URI_NOTE_ITEM:
                /**
                 * URI_NOTE_ITEM: 查询单个便签
                 * 
                 * 解析 URI 获取便签 ID：
                 * - getPathSegments() 返回 URI 路径各部分的列表
                 * - get(0) 是 "note"，get(1) 才是实际的 ID
                 * 
                 * 组合查询条件：
                 * - 首先按 ID 精确匹配
                 * - 然后拼接额外的 selection 条件
                 */
                id = uri.getPathSegments().get(1);
                c = db.query(TABLE.NOTE, projection, NoteColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs, null, null, sortOrder);
                break;
                
            case URI_DATA:
                /**
                 * URI_DATA: 查询所有数据记录
                 * 数据表存储便签的详细内容和媒体附件信息
                 */
                c = db.query(TABLE.DATA, projection, selection, selectionArgs, null, null,
                        sortOrder);
                break;
                
            case URI_DATA_ITEM:
                /**
                 * URI_DATA_ITEM: 查询单条数据记录
                 * 与 URI_NOTE_ITEM 类似，通过 ID 精确匹配
                 */
                id = uri.getPathSegments().get(1);
                c = db.query(TABLE.DATA, projection, DataColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs, null, null, sortOrder);
                break;
                
            case URI_SEARCH:
            case URI_SEARCH_SUGGEST:
                /**
                 * URI_SEARCH / URI_SEARCH_SUGGEST: 搜索功能
                 * 
                 * 这两个 URI 都用于搜索，但获取搜索词的方式不同：
                 * - URI_SEARCH: 通过查询参数获取，如 ?pattern=关键词
                 * - URI_SEARCH_SUGGEST: 通过路径获取，如 /search_suggest_query/关键词
                 * 
                 * 注意：
                 * - 搜索操作不允许指定 sortOrder 和 projection
                 * - 这是因为搜索结果格式是预定义的（见 NOTES_SEARCH_PROJECTION）
                 */
                if (sortOrder != null || projection != null) {
                    throw new IllegalArgumentException(
                            "do not specify sortOrder, selection, selectionArgs, or projection" + "with this query");
                }

                String searchString = null;
                
                // 根据不同的 URI 类型获取搜索词
                if (mMatcher.match(uri) == URI_SEARCH_SUGGEST) {
                    /**
                     * URI_SEARCH_SUGGEST 方式：
                     * 从路径中获取搜索词
                     * URI 格式：content://authority/search_suggest_query/搜索词
                     * getPathSegments().get(1) 获取搜索词部分
                     */
                    if (uri.getPathSegments().size() > 1) {
                        searchString = uri.getPathSegments().get(1);
                    }
                } else {
                    /**
                     * URI_SEARCH 方式：
                     * 从查询参数获取搜索词
                     * URI 格式：content://authority/search?pattern=搜索词
                     */
                    searchString = uri.getQueryParameter("pattern");
                }

                // 空搜索词直接返回 null
                if (TextUtils.isEmpty(searchString)) {
                    return null;
                }

                try {
                    /**
                     * 格式化搜索词：
                     * - 在搜索词两侧添加 % 通配符
                     * - 使 LIKE 查询变为模糊匹配
                     * - 例如："小米" 变为 "%小米%"
                     */
                    searchString = String.format("%%%s%%", searchString);
                    
                    /**
                     * 执行原始 SQL 查询
                     * 使用预定义的搜索查询语句
                     * @see NOTES_SNIPPET_SEARCH_QUERY
                     */
                    c = db.rawQuery(NOTES_SNIPPET_SEARCH_QUERY,
                            new String[] { searchString });
                } catch (IllegalStateException ex) {
                    Log.e(TAG, "got exception: " + ex.toString());
                }
                break;
                
            default:
                // 未知的 URI 类型，抛出异常
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        
        /**
         * 注册 ContentObserver：
         * - 当数据库数据发生变化时，Cursor 会通知观察者
         * - 这允许 ContentResolver 自动更新缓存的 Cursor 数据
         * - 第三个参数设置为 true 表示这是后台线程创建的 Cursor
         */
        if (c != null) {
            c.setNotificationUri(getContext().getContentResolver(), uri);
        }
        return c;
    }

    /**
     * insert - 插入新数据
     * 
     * 功能：
     * 根据 URI 类型向 NOTE 表或 DATA 表插入新记录
     * 
     * URI 支持：
     * - URI_NOTE: 向便签表插入新便签
     * - URI_DATA: 向数据表插入新数据记录
     * 
     * @param uri 目标数据表的 URI
     * @param values 要插入的数据（ContentValues 键值对）
     * @return 新插入记录的 URI（包含记录 ID）
     */
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // 获取可写数据库实例（插入操作需要写权限）
        SQLiteDatabase db = mHelper.getWritableDatabase();
        
        long dataId = 0, noteId = 0, insertedId = 0;
        
        // 根据 URI 类型执行不同的插入操作
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                /**
                 * URI_NOTE: 插入新便签
                 * 
                 * 流程：
                 * 1. 向 NOTE 表插入数据
                 * 2. 获取新插入记录的 ID（noteId 和 insertedId 相同）
                 * 3. insertedId 用于返回给调用者
                 */
                insertedId = noteId = db.insert(TABLE.NOTE, null, values);
                break;
                
            case URI_DATA:
                /**
                 * URI_DATA: 插入新数据记录
                 * 
                 * 数据记录说明：
                 * - DATA 表存储便签的详细数据（文本内容、媒体附件等）
                 * - 每个数据记录必须关联一个便签（通过 NOTE_ID 字段）
                 * 
                 * 流程：
                 * 1. 从 values 中获取关联的便签 ID
                 * 2. 如果没有提供 NOTE_ID，记录错误日志
                 * 3. 向 DATA 表插入数据
                 */
                if (values.containsKey(DataColumns.NOTE_ID)) {
                    noteId = values.getAsLong(DataColumns.NOTE_ID);
                } else {
                    Log.d(TAG, "Wrong data format without note id:" + values.toString());
                }
                insertedId = dataId = db.insert(TABLE.DATA, null, values);
                break;
                
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        
        /**
         * 通知观察者数据已变化
         * 
         * 通知机制说明：
         * - 当数据发生变化时，需要通知所有观察者刷新数据
         * - 这样使用 ContentResolver.query() 的组件会自动更新
         * 
         * 通知规则：
         * 1. 如果插入了便签（noteId > 0），通知便签 URI
         *    - 使用 Notes.CONTENT_NOTE_URI 通知所有便签查询
         *    - 因为新便签可能影响任何便签列表
         * 
         * 2. 如果插入了数据（dataId > 0），通知数据 URI
         *    - 使用 Notes.CONTENT_DATA_URI 通知所有数据查询
         */
        
        // 通知便签 URI（第二个参数为 null 表示通知所有观察者）
        if (noteId > 0) {
            getContext().getContentResolver().notifyChange(
                    ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId), null);
        }

        // 通知数据 URI
        if (dataId > 0) {
            getContext().getContentResolver().notifyChange(
                    ContentUris.withAppendedId(Notes.CONTENT_DATA_URI, dataId), null);
        }

        /**
         * 返回新插入记录的 URI
         * - 将 insertedId 附加到原始 URI 上
         * - 例如：content://net.micode.notes/note -> content://net.micode.notes/note/123
         */
        return ContentUris.withAppendedId(uri, insertedId);
    }

    /**
     * delete - 删除数据
     * 
     * 功能：
     * 根据 URI 删除便签或数据记录
     * 
     * URI 支持：
     * - URI_NOTE: 删除所有满足条件的便签
     * - URI_NOTE_ITEM: 根据 ID 删除单个便签
     * - URI_DATA: 删除所有满足条件的数据记录
     * - URI_DATA_ITEM: 根据 ID 删除单条数据记录
     * 
     * 安全限制：
     * - ID <= 0 的记录是系统文件夹，不允许删除
     * - ID > 0 的条件确保不会误删系统数据
     * 
     * @param uri 要删除的数据 URI
     * @param selection WHERE 条件语句
     * @param selectionArgs WHERE 条件中的参数值数组
     * @return 被删除的记录数量
     */
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int count = 0;
        String id = null;
        
        // 获取可写数据库实例
        SQLiteDatabase db = mHelper.getWritableDatabase();
        
        // 标记是否需要通知便签 URI（DATA 变化时需要通知 NOTE）
        boolean deleteData = false;
        
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                /**
                 * URI_NOTE: 批量删除便签
                 * 
                 * 安全措施：
                 * - 在 selection 前添加 ID > 0 的条件
                 * - 这确保不会删除系统文件夹（如根文件夹、垃圾箱等）
                 * - 系统文件夹的 ID 通常是负数或零
                 * 
                 * 拼接方式："(原selection) AND ID>0"
                 * 如果原 selection 为空，则变为 "ID>0"
                 */
                selection = "(" + selection + ") AND " + NoteColumns.ID + ">0 ";
                count = db.delete(TABLE.NOTE, selection, selectionArgs);
                break;
                
            case URI_NOTE_ITEM:
                /**
                 * URI_NOTE_ITEM: 删除单个便签
                 * 
                 * 安全限制：
                 * - ID <= 0 表示系统文件夹，不允许删除
                 * - 只有正常便签（ID > 0）才能被删除到垃圾箱或彻底删除
                 * - 这是为了保护系统内置文件夹不被误删
                 */
                id = uri.getPathSegments().get(1);
                
                /**
                 * ID 分类说明：
                 * - ID < 0: 系统特殊文件夹（如根目录 ID_ROOT_FOLER, 垃圾箱 ID_TRASH_FOLDER）
                 * - ID = 0: 无效 ID
                 * - ID > 0: 用户创建的便签
                 */
                long noteId = Long.valueOf(id);
                if (noteId <= 0) {
                    break;  // 系统文件夹不删除，直接返回 count=0
                }
                
                /**
                 * 删除指定的便签
                 * - 首先按 ID 精确匹配
                 * - 然后拼接额外的 selection 条件
                 */
                count = db.delete(TABLE.NOTE,
                        NoteColumns.ID + "=" + id + parseSelection(selection), selectionArgs);
                break;
                
            case URI_DATA:
                /**
                 * URI_DATA: 批量删除数据记录
                 * - 删除数据时需要同时通知便签 URI 变化
                 * - 因为 DATA 变化可能影响 NOTE 的显示（如摘要更新）
                 */
                count = db.delete(TABLE.DATA, selection, selectionArgs);
                deleteData = true;
                break;
                
            case URI_DATA_ITEM:
                /**
                 * URI_DATA_ITEM: 删除单条数据记录
                 * 与 URI_NOTE_ITEM 类似，通过 ID 精确匹配删除
                 */
                id = uri.getPathSegments().get(1);
                count = db.delete(TABLE.DATA,
                        DataColumns.ID + "=" + id + parseSelection(selection), selectionArgs);
                deleteData = true;
                break;
                
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        
        /**
         * 通知数据观察者
         * 
         * 通知规则：
         * 1. 如果删除了数据记录（deleteData = true），需要通知便签 URI
         *    因为便签的摘要等信息可能依赖数据内容
         * 2. 无论删除什么，都通知操作的具体 URI
         */
        if (count > 0) {
            if (deleteData) {
                // 通知便签 URI，让所有便签相关查询重新加载
                getContext().getContentResolver().notifyChange(Notes.CONTENT_NOTE_URI, null);
            }
            // 通知操作的 URI
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    /**
     * update - 更新数据
     * 
     * 功能：
     * 根据 URI 更新便签或数据记录
     * 
     * URI 支持：
     * - URI_NOTE: 批量更新便签
     * - URI_NOTE_ITEM: 根据 ID 更新单个便签
     * - URI_DATA: 批量更新数据记录
     * - URI_DATA_ITEM: 根据 ID 更新单条数据记录
     * 
     * 副作用：
     * - 更新便签时，会自动增加 VERSION 字段值
     * - VERSION 用于数据同步和冲突检测
     * 
     * @param uri 要更新的数据 URI
     * @param values 要更新的字段值
     * @param selection WHERE 条件语句
     * @param selectionArgs WHERE 条件中的参数值数组
     * @return 被更新的记录数量
     */
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        int count = 0;
        String id = null;
        
        // 获取可写数据库实例
        SQLiteDatabase db = mHelper.getWritableDatabase();
        
        // 标记是否需要通知便签 URI（DATA 变化时需要通知 NOTE）
        boolean updateData = false;
        
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                /**
                 * URI_NOTE: 批量更新便签
                 * 
                 * 版本递增：
                 * - 在更新前，先递增所有匹配记录的 VERSION 字段
                 * - 这用于追踪数据的修改历史，支持同步和冲突检测
                 * - id = -1 表示批量更新，不需要按 ID 筛选
                 */
                increaseNoteVersion(-1, selection, selectionArgs);
                count = db.update(TABLE.NOTE, values, selection, selectionArgs);
                break;
                
            case URI_NOTE_ITEM:
                /**
                 * URI_NOTE_ITEM: 更新单个便签
                 * 
                 * 流程：
                 * 1. 解析 URI 获取便签 ID
                 * 2. 递增该便签的 VERSION
                 * 3. 执行更新操作
                 */
                id = uri.getPathSegments().get(1);
                increaseNoteVersion(Long.valueOf(id), selection, selectionArgs);
                count = db.update(TABLE.NOTE, values, NoteColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs);
                break;
                
            case URI_DATA:
                /**
                 * URI_DATA: 批量更新数据记录
                 * - 更新数据时需要同时通知便签 URI 变化
                 */
                count = db.update(TABLE.DATA, values, selection, selectionArgs);
                updateData = true;
                break;
                
            case URI_DATA_ITEM:
                /**
                 * URI_DATA_ITEM: 更新单条数据记录
                 */
                id = uri.getPathSegments().get(1);
                count = db.update(TABLE.DATA, values, DataColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs);
                updateData = true;
                break;
                
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        /**
         * 通知数据观察者
         * 与 delete() 方法的通知逻辑相同
         */
        if (count > 0) {
            if (updateData) {
                // 数据变化可能影响便签显示，需要通知便签 URI
                getContext().getContentResolver().notifyChange(Notes.CONTENT_NOTE_URI, null);
            }
            // 通知操作的 URI
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * parseSelection - 解析并组合 WHERE 条件
     * 
     * 功能：
     * 将原有的 selection 条件与 ID 条件组合
     * 
     * 组合逻辑：
     * - 如果原 selection 不为空，返回 " AND (原selection)"
     * - 如果原 selection 为空，返回空字符串 ""
     * 
     * 示例：
     * - 原 selection: "TYPE=1"，结果: " AND (TYPE=1)"
     * - 原 selection: null 或 ""，结果: ""
     * 
     * @param selection 原始的 WHERE 条件语句
     * @return 组合后的条件字符串
     */
    private String parseSelection(String selection) {
        return (!TextUtils.isEmpty(selection) ? " AND (" + selection + ')' : "");
    }

    /**
     * increaseNoteVersion - 递增便签版本号
     * 
     * 功能：
     * 为匹配的便签记录递增 VERSION 字段
     * 
     * 版本号用途：
     * 1. 数据同步：追踪哪些记录被修改过
     * 2. 冲突检测：多个设备修改同一便签时的冲突解决
     * 3. 缓存管理：客户端可以使用版本号判断数据是否过期
     * 
     * SQL 构建逻辑：
     * 1. UPDATE table SET VERSION = VERSION + 1
     * 2. 如果指定了 ID 或 selection，添加 WHERE 条件
     * 3. 将 selectionArgs 中的参数替换到 WHERE 条件中
     * 
     * @param id 要更新的便签 ID，-1 表示不按 ID 筛选（用于批量更新）
     * @param selection WHERE 条件语句
     * @param selectionArgs WHERE 条件中的参数值数组
     */
    private void increaseNoteVersion(long id, String selection, String[] selectionArgs) {
        StringBuilder sql = new StringBuilder(120);
        
        // 构建 UPDATE 语句
        sql.append("UPDATE ");
        sql.append(TABLE.NOTE);
        sql.append(" SET ");
        sql.append(NoteColumns.VERSION);
        sql.append("=" + NoteColumns.VERSION + "+1 ");
        
        // 如果有 ID 限制或 selection，添加 WHERE 子句
        if (id > 0 || !TextUtils.isEmpty(selection)) {
            sql.append(" WHERE ");
        }
        
        // 添加 ID 条件（如果指定了）
        if (id > 0) {
            sql.append(NoteColumns.ID + "=" + String.valueOf(id));
        }
        
        // 添加 selection 条件并替换参数
        if (!TextUtils.isEmpty(selection)) {
            /**
             * 处理 selection 参数：
             * 1. 如果同时指定了 ID，使用 parseSelection() 包装
             * 2. 如果没有指定 ID，直接使用 selection
             * 3. 遍历 selectionArgs，替换 SQL 中的 ? 占位符
             */
            String selectString = id > 0 ? parseSelection(selection) : selection;
            for (String args : selectionArgs) {
                selectString = selectString.replaceFirst("\\?", args);
            }
            sql.append(selectString);
        }

        /**
         * 执行原生 SQL 语句
         * 使用 execSQL() 而不是 update() 是因为：
         * - 需要精确控制 VERSION 字段的自增操作
         * - 批量更新时减少数据库操作次数
         */
        mHelper.getWritableDatabase().execSQL(sql.toString());
    }

    // ============================================================
    // 其他必需方法
    // ============================================================

    /**
     * getType - 获取 URI 对应的 MIME 类型
     * 
     * 功能：
     * 返回指定 URI 指向的数据的 MIME 类型
     * 
     * MIME 类型格式：
     * - 单条记录：vnd.android.cursor.item/vnd.authority.type
     * - 记录列表：vnd.android.cursor.dir/vnd.authority.type
     * 
     * 当前实现：
     * 返回 null，因为该 Provider 目前不需要返回 MIME 类型
     * 如需支持，可以根据 URI 类型返回对应的 MIME 类型
     * 
     * @param uri 要查询的数据 URI
     * @return URI 对应的 MIME 类型字符串
     */
    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }

}
