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

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;

/**
 * NotesDatabaseHelper - 便签数据库管理助手类
 * 
 * 功能说明：
 * - 管理 SQLite 数据库的创建、升级和版本控制
 * - 定义数据库表结构（note表和data表）
 * - 创建和维护数据库触发器，确保数据一致性和自动同步
 * - 初始化系统文件夹（通话录音文件夹、根文件夹、临时文件夹、回收站）
 * 
 * 数据库版本历史：
 * - v1: 初始版本
 * - v2: 完全重建表结构
 * - v3: 添加gtask_id列，添加回收站系统文件夹
 * - v4: 添加version列用于冲突检测
 * 
 * @author MiCode Open Source Community
 */
public class NotesDatabaseHelper extends SQLiteOpenHelper {
    /** 数据库文件名 */
    private static final String DB_NAME = "note.db";

    /** 当前数据库版本号，版本升级时需要递增 */
    private static final int DB_VERSION = 4;

    /**
     * 表名常量接口
     * 定义数据库中所有表的名称
     */
    public interface TABLE {
        /** 便签表 - 存储便签/文件夹的主信息 */
        public static final String NOTE = "note";

        /** 数据表 - 存储便签的具体内容（支持多种MIME类型） */
        public static final String DATA = "data";
    }

    /** 日志标签 */
    private static final String TAG = "NotesDatabaseHelper";

    /** 单例模式：数据库帮助类的唯一实例 */
    private static NotesDatabaseHelper mInstance;

    // ==================== SQL表结构定义 ====================
    
    /**
     * 创建便签表(NOTE表)的SQL语句
     * 
     * 表结构说明：
     * - ID: 便签唯一标识符，主键
     * - PARENT_ID: 父文件夹ID，0表示根级别便签
     * - ALERTED_DATE: 闹钟提醒日期时间戳（毫秒）
     * - BG_COLOR_ID: 便签背景颜色ID
     * - CREATED_DATE: 创建时间戳（毫秒）
     * - HAS_ATTACHMENT: 是否含有附件（0=无，1=有）
     * - MODIFIED_DATE: 最后修改时间戳（毫秒）
     * - NOTES_COUNT: 文件夹内便签数量（仅文件夹使用）
     * - SNIPPET: 便签内容摘要/预览文本
     * - TYPE: 类型（0=普通便签，1=系统文件夹）
     * - WIDGET_ID: 关联的小部件ID
     * - WIDGET_TYPE: 小部件类型
     * - SYNC_ID: 同步服务ID
     * - LOCAL_MODIFIED: 本地修改标记
     * - ORIGIN_PARENT_ID: 原始父文件夹ID（用于从回收站恢复）
     * - GTASK_ID: Google Task ID（用于与Google Tasks同步）
     * - VERSION: 版本号（用于冲突检测）
     */
    private static final String CREATE_NOTE_TABLE_SQL =
        "CREATE TABLE " + TABLE.NOTE + "(" +
            NoteColumns.ID + " INTEGER PRIMARY KEY," +
            NoteColumns.PARENT_ID + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.ALERTED_DATE + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.BG_COLOR_ID + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.CREATED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
            NoteColumns.HAS_ATTACHMENT + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.MODIFIED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
            NoteColumns.NOTES_COUNT + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.SNIPPET + " TEXT NOT NULL DEFAULT ''," +
            NoteColumns.TYPE + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.WIDGET_ID + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.WIDGET_TYPE + " INTEGER NOT NULL DEFAULT -1," +
            NoteColumns.SYNC_ID + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.LOCAL_MODIFIED + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.ORIGIN_PARENT_ID + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.GTASK_ID + " TEXT NOT NULL DEFAULT ''," +
            NoteColumns.VERSION + " INTEGER NOT NULL DEFAULT 0" +
        ")";

    /**
     * 创建数据表(DATA表)的SQL语句
     * 
     * 表结构说明：
     * - ID: 数据记录唯一标识符，主键
     * - MIME_TYPE: 数据类型（如DataConstants.NOTE表示便签文本内容）
     * - NOTE_ID: 关联的便签ID，外键关联到NOTE表
     * - CREATED_DATE: 创建时间戳
     * - MODIFIED_DATE: 修改时间戳
     * - CONTENT: 数据内容（文本内容或其他数据）
     * - DATA1~DATA5: 扩展字段，用于存储不同MIME类型的数据
     * 
     * 设计说明：
     * - 一个便签可以有多条DATA记录（支持多种内容类型）
     * - 通过MIME_TYPE区分不同类型的数据
     */
    private static final String CREATE_DATA_TABLE_SQL =
        "CREATE TABLE " + TABLE.DATA + "(" +
            DataColumns.ID + " INTEGER PRIMARY KEY," +
            DataColumns.MIME_TYPE + " TEXT NOT NULL," +
            DataColumns.NOTE_ID + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.CREATED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
            NoteColumns.MODIFIED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
            DataColumns.CONTENT + " TEXT NOT NULL DEFAULT ''," +
            DataColumns.DATA1 + " INTEGER," +
            DataColumns.DATA2 + " INTEGER," +
            DataColumns.DATA3 + " TEXT NOT NULL DEFAULT ''," +
            DataColumns.DATA4 + " TEXT NOT NULL DEFAULT ''," +
            DataColumns.DATA5 + " TEXT NOT NULL DEFAULT ''" +
        ")";

    /**
     * 创建DATA表的NOTE_ID索引
     * 
     * 作用：加速根据便签ID查询关联数据的操作
     * 索引字段：NOTE_ID
     */
    private static final String CREATE_DATA_NOTE_ID_INDEX_SQL =
        "CREATE INDEX IF NOT EXISTS note_id_index ON " +
        TABLE.DATA + "(" + DataColumns.NOTE_ID + ");";

    // ==================== 触发器定义 ====================
    
    /**
     * 【触发器1】将便签移入文件夹时，增加目标文件夹的便签计数
     * 
     * 触发时机：当NOTE表中某条记录的PARENT_ID（父文件夹ID）字段被更新时触发
     * 
     * 业务逻辑：
     * - 用户将便签移动到某个文件夹时，目标文件夹的NOTES_COUNT需要+1
     * - 例如：将便签从"根目录"移动到"工作"文件夹
     * 
     * SQL逻辑：
     * - 找到被更新的记录新的PARENT_ID对应的文件夹
     * - 将该文件夹的NOTES_COUNT字段加1
     */
    private static final String NOTE_INCREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER =
        "CREATE TRIGGER increase_folder_count_on_update "+
        " AFTER UPDATE OF " + NoteColumns.PARENT_ID + " ON " + TABLE.NOTE +
        " BEGIN " +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + " + 1" +
        "  WHERE " + NoteColumns.ID + "=new." + NoteColumns.PARENT_ID + ";" +
        " END";

    /**
     * 【触发器2】将便签从文件夹移出时，减少原文件夹的便签计数
     * 
     * 触发时机：当NOTE表中某条记录的PARENT_ID（父文件夹ID）字段被更新时触发
     * 
     * 业务逻辑：
     * - 用户将便签从某个文件夹移出时，原文件夹的NOTES_COUNT需要-1
     * - 添加NOTES_COUNT>0条件防止计数变为负数
     * 
     * SQL逻辑：
     * - 找到被更新的记录原来的PARENT_ID对应的文件夹（原文件夹）
     * - 将该文件夹的NOTES_COUNT字段减1（但不低于0）
     */
    private static final String NOTE_DECREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER =
        "CREATE TRIGGER decrease_folder_count_on_update " +
        " AFTER UPDATE OF " + NoteColumns.PARENT_ID + " ON " + TABLE.NOTE +
        " BEGIN " +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + "-1" +
        "  WHERE " + NoteColumns.ID + "=old." + NoteColumns.PARENT_ID +
        "  AND " + NoteColumns.NOTES_COUNT + ">0" + ";" +
        " END";

    /**
     * 【触发器3】在文件夹中新建便签时，增加文件夹的便签计数
     * 
     * 触发时机：当在NOTE表中插入新记录时触发
     * 
     * 业务逻辑：
     * - 用户在某个文件夹中创建新便签时，该文件夹的NOTES_COUNT需要+1
     * - 自动维护文件夹的便签数量统计
     * 
     * SQL逻辑：
     * - 找到新插入记录的PARENT_ID对应的文件夹
     * - 将该文件夹的NOTES_COUNT字段加1
     */
    private static final String NOTE_INCREASE_FOLDER_COUNT_ON_INSERT_TRIGGER =
        "CREATE TRIGGER increase_folder_count_on_insert " +
        " AFTER INSERT ON " + TABLE.NOTE +
        " BEGIN " +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + " + 1" +
        "  WHERE " + NoteColumns.ID + "=new." + NoteColumns.PARENT_ID + ";" +
        " END";

    /**
     * 【触发器4】删除便签时，减少所属文件夹的便签计数
     * 
     * 触发时机：当从NOTE表中删除记录时触发
     * 
     * 业务逻辑：
     * - 删除便签后，需要减少其所属文件夹的便签计数
     * - 添加NOTES_COUNT>0条件防止计数变为负数
     * 
     * SQL逻辑：
     * - 找到被删除记录原来的PARENT_ID对应的文件夹
     * - 将该文件夹的NOTES_COUNT字段减1（但不低于0）
     */
    private static final String NOTE_DECREASE_FOLDER_COUNT_ON_DELETE_TRIGGER =
        "CREATE TRIGGER decrease_folder_count_on_delete " +
        " AFTER DELETE ON " + TABLE.NOTE +
        " BEGIN " +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + "-1" +
        "  WHERE " + NoteColumns.ID + "=old." + NoteColumns.PARENT_ID +
        "  AND " + NoteColumns.NOTES_COUNT + ">0;" +
        " END";

    /**
     * 【触发器5】插入便签内容数据时，同步更新便签的摘要字段
     * 
     * 触发时机：当在DATA表中插入新记录，且MIME_TYPE为DataConstants.NOTE时触发
     * 
     * 业务逻辑：
     * - 便签的实际文本内容存储在DATA表中
     * - 为了快速显示便签列表，需要在NOTE表中维护SNIPPET（摘要）字段
     * - 当插入新的便签文本内容时，自动同步更新NOTE表的SNIPPET
     * 
     * SQL逻辑：
     * - 将新插入的DATA记录的CONTENT字段值
     * - 更新到对应NOTE_ID的SNIPPET字段中
     */
    private static final String DATA_UPDATE_NOTE_CONTENT_ON_INSERT_TRIGGER =
        "CREATE TRIGGER update_note_content_on_insert " +
        " AFTER INSERT ON " + TABLE.DATA +
        " WHEN new." + DataColumns.MIME_TYPE + "='" + DataConstants.NOTE + "'" +
        " BEGIN" +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.SNIPPET + "=new." + DataColumns.CONTENT +
        "  WHERE " + NoteColumns.ID + "=new." + DataColumns.NOTE_ID + ";" +
        " END";

    /**
     * 【触发器6】更新便签内容数据时，同步更新便签的摘要字段
     * 
     * 触发时机：当在DATA表中更新记录，且MIME_TYPE为DataConstants.NOTE时触发
     * 
     * 业务逻辑：
     * - 当用户编辑便签内容时，需要同步更新NOTE表的SNIPPET字段
     * - 使用old.MIME_TYPE判断原记录是否为便签类型
     * 
     * SQL逻辑：
     * - 将更新后的DATA记录的CONTENT字段值
     * - 更新到对应NOTE_ID的SNIPPET字段中
     */
    private static final String DATA_UPDATE_NOTE_CONTENT_ON_UPDATE_TRIGGER =
        "CREATE TRIGGER update_note_content_on_update " +
        " AFTER UPDATE ON " + TABLE.DATA +
        " WHEN old." + DataColumns.MIME_TYPE + "='" + DataConstants.NOTE + "'" +
        " BEGIN" +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.SNIPPET + "=new." + DataColumns.CONTENT +
        "  WHERE " + NoteColumns.ID + "=new." + DataColumns.NOTE_ID + ";" +
        " END";

    /**
     * 【触发器7】删除便签内容数据时，清空便签的摘要字段
     * 
     * 触发时机：当从DATA表中删除记录，且MIME_TYPE为DataConstants.NOTE时触发
     * 
     * 业务逻辑：
     * - 当便签内容被删除时，需要将NOTE表的SNIPPET字段清空
     * - 避免显示已删除的内容
     * 
     * SQL逻辑：
     * - 将对应NOTE_ID的SNIPPET字段设置为空字符串
     */
    private static final String DATA_UPDATE_NOTE_CONTENT_ON_DELETE_TRIGGER =
        "CREATE TRIGGER update_note_content_on_delete " +
        " AFTER delete ON " + TABLE.DATA +
        " WHEN old." + DataColumns.MIME_TYPE + "='" + DataConstants.NOTE + "'" +
        " BEGIN" +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.SNIPPET + "=''" +
        "  WHERE " + NoteColumns.ID + "=old." + DataColumns.NOTE_ID + ";" +
        " END";

    /**
     * 【触发器8】删除便签时，自动删除其关联的所有数据记录
     * 
     * 触发时机：当从NOTE表中删除记录时触发
     * 
     * 业务逻辑：
     * - 便签的内容数据存储在DATA表中，通过NOTE_ID关联
     * - 删除便签时，必须级联删除其所有关联的DATA记录
     * - 防止产生孤儿数据（没有所属便签的数据）
     * 
     * SQL逻辑：
     * - 删除DATA表中NOTE_ID等于被删除便签ID的所有记录
     */
    private static final String NOTE_DELETE_DATA_ON_DELETE_TRIGGER =
        "CREATE TRIGGER delete_data_on_delete " +
        " AFTER DELETE ON " + TABLE.NOTE +
        " BEGIN" +
        "  DELETE FROM " + TABLE.DATA +
        "   WHERE " + DataColumns.NOTE_ID + "=old." + NoteColumns.ID + ";" +
        " END";

    /**
     * 【触发器9】删除文件夹时，自动删除文件夹内的所有便签
     * 
     * 触发时机：当从NOTE表中删除记录，且该记录为文件夹类型时触发
     * 
     * 业务逻辑：
     * - 删除文件夹时，文件夹内的所有便签也应该被删除
     * - 形成级联删除效果
     * 
     * SQL逻辑：
     * - 删除NOTE表中PARENT_ID等于被删除文件夹ID的所有记录
     * - 注意：这会触发递归删除（如果文件夹内嵌套了子文件夹）
     */
    private static final String FOLDER_DELETE_NOTES_ON_DELETE_TRIGGER =
        "CREATE TRIGGER folder_delete_notes_on_delete " +
        " AFTER DELETE ON " + TABLE.NOTE +
        " BEGIN" +
        "  DELETE FROM " + TABLE.NOTE +
        "   WHERE " + NoteColumns.PARENT_ID + "=old." + NoteColumns.ID + ";" +
        " END";

    /**
     * 【触发器10】将文件夹移入回收站时，自动将其内的便签也移入回收站
     * 
     * 触发时机：当NOTE表中某条记录的PARENT_ID被更新，且新的PARENT_ID为回收站文件夹ID时触发
     * 
     * 业务逻辑：
     * - 将文件夹移动到回收站时，文件夹内的所有便签也应该进入回收站
     * - 保持文件夹和便签的逻辑一致性
     * 
     * SQL逻辑：
     * - 将PARENT_ID等于被更新文件夹ID的所有便签
     * - 的PARENT_ID也更新为回收站文件夹ID
     */
    private static final String FOLDER_MOVE_NOTES_ON_TRASH_TRIGGER =
        "CREATE TRIGGER folder_move_notes_on_trash " +
        " AFTER UPDATE ON " + TABLE.NOTE +
        " WHEN new." + NoteColumns.PARENT_ID + "=" + Notes.ID_TRASH_FOLER +
        " BEGIN" +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.PARENT_ID + "=" + Notes.ID_TRASH_FOLER +
        "  WHERE " + NoteColumns.PARENT_ID + "=old." + NoteColumns.ID + ";" +
        " END";

    // ==================== 构造函数和单例 ====================
    
    /**
     * 构造函数
     * 
     * @param context Android上下文环境
     */
    public NotesDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    // ==================== 表创建方法 ====================
    
    /**
     * 创建便签表及关联资源
     * 
     * 完整流程：
     * 1. 执行CREATE TABLE SQL创建NOTE表
     * 2. 创建NOTE表相关的所有触发器
     * 3. 初始化系统文件夹（根目录、通话录音文件夹、临时文件夹、回收站）
     * 
     * @param db SQLite数据库实例
     */
    public void createNoteTable(SQLiteDatabase db) {
        db.execSQL(CREATE_NOTE_TABLE_SQL);
        reCreateNoteTableTriggers(db);
        createSystemFolder(db);
        Log.d(TAG, "note table has been created");
    }

    /**
     * 重新创建NOTE表的所有触发器
     * 
     * 方法说明：
     * - 先删除所有已存在的触发器（防止重复创建错误）
     * - 再按照正确的顺序创建新的触发器
     * 
     * 触发的触发器列表：
     * 1. increase_folder_count_on_update - 更新时增加文件夹计数
     * 2. decrease_folder_count_on_update - 更新时减少文件夹计数
     * 3. decrease_folder_count_on_delete - 删除时减少文件夹计数
     * 4. delete_data_on_delete - 删除时删除关联数据
     * 5. increase_folder_count_on_insert - 插入时增加文件夹计数
     * 6. folder_delete_notes_on_delete - 删除文件夹时删除子项
     * 7. folder_move_notes_on_trash - 移入回收站时移动子项
     * 
     * @param db SQLite数据库实例
     */
    private void reCreateNoteTableTriggers(SQLiteDatabase db) {
        // 先删除已存在的触发器（如果存在）
        db.execSQL("DROP TRIGGER IF EXISTS increase_folder_count_on_update");
        db.execSQL("DROP TRIGGER IF EXISTS decrease_folder_count_on_update");
        db.execSQL("DROP TRIGGER IF EXISTS decrease_folder_count_on_delete");
        db.execSQL("DROP TRIGGER IF EXISTS delete_data_on_delete");
        db.execSQL("DROP TRIGGER IF EXISTS increase_folder_count_on_insert");
        db.execSQL("DROP TRIGGER IF EXISTS folder_delete_notes_on_delete");
        db.execSQL("DROP TRIGGER IF EXISTS folder_move_notes_on_trash");

        // 创建所有触发器
        db.execSQL(NOTE_INCREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER);
        db.execSQL(NOTE_DECREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER);
        db.execSQL(NOTE_DECREASE_FOLDER_COUNT_ON_DELETE_TRIGGER);
        db.execSQL(NOTE_DELETE_DATA_ON_DELETE_TRIGGER);
        db.execSQL(NOTE_INCREASE_FOLDER_COUNT_ON_INSERT_TRIGGER);
        db.execSQL(FOLDER_DELETE_NOTES_ON_DELETE_TRIGGER);
        db.execSQL(FOLDER_MOVE_NOTES_ON_TRASH_TRIGGER);
    }

    /**
     * 创建系统文件夹
     * 
     * 系统文件夹说明：
     * 1. 通话录音文件夹 (ID_CALL_RECORD_FOLDER)
     *    - 用于存储通话时创建的录音便签
     *    
     * 2. 根文件夹 (ID_ROOT_FOLDER)
     *    - 所有便签的默认父文件夹
     *    - 类似于根目录概念
     *    
     * 3. 临时文件夹 (ID_TEMPARAY_FOLDER)
     *    - 便签移动过程中的临时存放位置
     *    - 防止移动操作中断导致数据丢失
     *    
     * 4. 回收站 (ID_TRASH_FOLER)
     *    - 删除的便签/文件夹暂时存放在这里
     *    - 可以恢复或永久删除
     * 
     * @param db SQLite数据库实例
     */
    private void createSystemFolder(SQLiteDatabase db) {
        ContentValues values = new ContentValues();

        /**
         * 通话录音文件夹 - 用于通话时创建的便签
         */
        values.put(NoteColumns.ID, Notes.ID_CALL_RECORD_FOLDER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);

        /**
         * 根文件夹 - 所有便签的默认父文件夹
         */
        values.clear();
        values.put(NoteColumns.ID, Notes.ID_ROOT_FOLDER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);

        /**
         * 临时文件夹 - 便签移动操作时的临时存放位置
         */
        values.clear();
        values.put(NoteColumns.ID, Notes.ID_TEMPARAY_FOLDER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);

        /**
         * 回收站 - 存放已删除的便签和文件夹
         */
        values.clear();
        values.put(NoteColumns.ID, Notes.ID_TRASH_FOLER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);
    }

    /**
     * 创建数据表及关联资源
     * 
     * 完整流程：
     * 1. 执行CREATE TABLE SQL创建DATA表
     * 2. 创建DATA表相关的所有触发器
     * 3. 创建NOTE_ID索引（加速关联查询）
     * 
     * @param db SQLite数据库实例
     */
    public void createDataTable(SQLiteDatabase db) {
        db.execSQL(CREATE_DATA_TABLE_SQL);
        reCreateDataTableTriggers(db);
        db.execSQL(CREATE_DATA_NOTE_ID_INDEX_SQL);
        Log.d(TAG, "data table has been created");
    }

    /**
     * 重新创建DATA表的所有触发器
     * 
     * 触发的触发器列表：
     * 1. update_note_content_on_insert - 插入内容时更新便签摘要
     * 2. update_note_content_on_update - 更新内容时更新便签摘要
     * 3. update_note_content_on_delete - 删除内容时清空便签摘要
     * 
     * @param db SQLite数据库实例
     */
    private void reCreateDataTableTriggers(SQLiteDatabase db) {
        // 先删除已存在的触发器
        db.execSQL("DROP TRIGGER IF EXISTS update_note_content_on_insert");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_content_on_update");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_content_on_delete");

        // 创建所有触发器
        db.execSQL(DATA_UPDATE_NOTE_CONTENT_ON_INSERT_TRIGGER);
        db.execSQL(DATA_UPDATE_NOTE_CONTENT_ON_UPDATE_TRIGGER);
        db.execSQL(DATA_UPDATE_NOTE_CONTENT_ON_DELETE_TRIGGER);
    }

    /**
     * 获取数据库帮助类单例实例
     * 
     * 单例模式说明：
     * - 整个应用只需要一个数据库连接实例
     * - 避免频繁创建/关闭数据库连接带来的性能开销
     * 
     * @param context Android上下文环境
     * @return NotesDatabaseHelper单例实例
     */
    static synchronized NotesDatabaseHelper getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new NotesDatabaseHelper(context);
        }
        return mInstance;
    }

    // ==================== 数据库生命周期回调 ====================
    
    /**
     * 数据库首次创建时调用
     * 
     * 触发时机：当数据库文件不存在时（即首次打开应用时）
     * 
     * 创建内容：
     * 1. NOTE表及其触发器、系统文件夹
     * 2. DATA表及其触发器、索引
     * 
     * @param db SQLite数据库实例
     */
    @Override
    public void onCreate(SQLiteDatabase db) {
        createNoteTable(db);
        createDataTable(db);
    }

    /**
     * 数据库版本升级时调用
     * 
     * 升级策略说明：
     * - 支持增量升级：从任意旧版本升级到新版本
     * - 每个版本升级逻辑独立，可以选择性跳过某些版本
     * 
     * 升级流程：
     * 1. 检查当前版本，如果是v1则执行v1→v2升级
     * 2. 如果当前版本是v2，执行v2→v3升级
     * 3. 如果当前版本是v3，执行v3→v4升级
     * 
     * 特别注意：
     * - v1→v2升级是"破坏性升级"，完全重建表结构
     * - 从v1升级后，不需要再执行v2→v3升级（因为v1→v2已包含）
     * - 需要重新创建触发器时，设置reCreateTriggers标志
     * 
     * @param db SQLite数据库实例
     * @param oldVersion 旧数据库版本号
     * @param newVersion 新数据库版本号
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        boolean reCreateTriggers = false;  // 是否需要重新创建触发器
        boolean skipV2 = false;             // 是否跳过v2升级（v1升级已包含）

        // 处理 v1 → v2 升级
        if (oldVersion == 1) {
            upgradeToV2(db);
            skipV2 = true; // v1→v2升级已包含完整表重建，不需要再执行v2→v3
            oldVersion++;
        }

        // 处理 v2 → v3 升级
        if (oldVersion == 2 && !skipV2) {
            upgradeToV3(db);
            reCreateTriggers = true; // v3需要重新创建触发器
            oldVersion++;
        }

        // 处理 v3 → v4 升级
        if (oldVersion == 3) {
            upgradeToV4(db);
            oldVersion++;
        }

        // 根据标志决定是否重新创建触发器
        if (reCreateTriggers) {
            reCreateNoteTableTriggers(db);
            reCreateDataTableTriggers(db);
        }

        // 验证升级是否成功
        if (oldVersion != newVersion) {
            throw new IllegalStateException("升级便签数据库到版本 " + newVersion
                    + " 失败");
        }
    }

    // ==================== 数据库升级方法 ====================
    
    /**
     * 升级数据库从v1到v2
     * 
     * v2升级说明：
     * 这是一个"破坏性升级"，完全重建数据库表结构
     * - 删除原有的NOTE表和DATA表
     * - 重新创建新的表结构和所有触发器
     * - 重新初始化系统文件夹
     * 
     * 使用场景：
     * - 数据库结构发生重大变化时使用
     * - 适合v1版本存在重大设计缺陷的情况
     * 
     * 注意：此升级会导致原有数据丢失！
     * 
     * @param db SQLite数据库实例
     */
    private void upgradeToV2(SQLiteDatabase db) {
        // 删除旧表
        db.execSQL("DROP TABLE IF EXISTS " + TABLE.NOTE);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE.DATA);
        // 重新创建表和触发器
        createNoteTable(db);
        createDataTable(db);
    }

    /**
     * 升级数据库从v2到v3
     * 
     * v3升级内容：
     * 1. 删除无用的旧触发器（update_note_modified_date_on_*）
     *    - 这些触发器可能已不再使用
     *    
     * 2. 添加GTASK_ID列到NOTE表
     *    - 用于与Google Tasks服务同步
     *    - TEXT类型，存储Google Task的任务ID
     *    - 默认空字符串
     *    
     * 3. 添加回收站系统文件夹
     *    - 回收站ID：Notes.ID_TRASH_FOLER
     *    - TYPE：系统文件夹类型
     * 
     * @param db SQLite数据库实例
     */
    private void upgradeToV3(SQLiteDatabase db) {
        // 1. 删除不再使用的旧触发器
        db.execSQL("DROP TRIGGER IF EXISTS update_note_modified_date_on_insert");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_modified_date_on_delete");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_modified_date_on_update");
        
        // 2. 添加gtask_id列用于Google Tasks同步
        db.execSQL("ALTER TABLE " + TABLE.NOTE + " ADD COLUMN " + NoteColumns.GTASK_ID
                + " TEXT NOT NULL DEFAULT ''");
        
        // 3. 添加回收站系统文件夹
        ContentValues values = new ContentValues();
        values.put(NoteColumns.ID, Notes.ID_TRASH_FOLER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);
    }

    /**
     * 升级数据库从v3到v4
     * 
     * v4升级内容：
     * 添加VERSION列到NOTE表
     * - INTEGER类型，用于冲突检测
     * - 默认值为0
     * 
     * 使用场景：
     * - 支持离线编辑时的冲突检测和解决
     * - 当多设备同步时，检测是否有编辑冲突
     * 
     * @param db SQLite数据库实例
     */
    private void upgradeToV4(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE " + TABLE.NOTE + " ADD COLUMN " + NoteColumns.VERSION
                + " INTEGER NOT NULL DEFAULT 0");
    }
}
