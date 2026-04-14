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

import android.net.Uri;

/**
 * 小米便签数据常量定义类
 * 
 * 本文件定义了便签应用所有的数据常量，包括：
 * - ContentProvider 的 URI 和 Authority
 * - 笔记和文件夹的数据表列名常量
 * - 数据类型定义（笔记、文件夹、系统类型）
 * - MIME 类型定义
 * - 桌面小部件类型
 * - Intent 传递的 Extra 键名
 * - 系统文件夹 ID
 * - 文本笔记和通话记录的数据结构
 * 
 * 所有常量均使用 static final 修饰，供整个应用统一使用
 * 
 * @author MiCode Team
 * @see android.content.ContentProvider
 */
public class Notes {
    // ============================================================
    // 常量区域
    // ============================================================
    
    /**
     * ContentProvider 的Authority标识符
     * 用于构建ContentProvider的URI，格式为 "content://micode_notes/..."
     * 这是系统用于识别小米便签ContentProvider的唯一标识
     */
    public static final String AUTHORITY = "micode_notes";
    
    /**
     * 日志标签
     * 用于在Log.d()等日志输出时标识来源模块，便于过滤和调试
     */
    public static final String TAG = "Notes";
    
    // ============================================================
    // 数据类型常量
    // ============================================================
    
    /**
     * 笔记类型标识
     * 表示该记录是一条普通的便签笔记
     * 用于区分笔记和文件夹、系统文件夹的类型判断
     */
    public static final int TYPE_NOTE     = 0;
    
    /**
     * 文件夹类型标识
     * 表示该记录是一个用户创建的文件夹
     * 文件夹用于组织和分类管理多条笔记
     */
    public static final int TYPE_FOLDER   = 1;
    
    /**
     * 系统文件夹类型标识
     * 表示该记录是一个系统预置的文件夹（如根目录、通话记录、回收站等）
     * 系统文件夹通常不允许删除，用于特殊用途的笔记管理
     */
    public static final int TYPE_SYSTEM   = 2;

    // ============================================================
    // 系统文件夹ID常量
    // ============================================================
    
    /**
     * 系统文件夹标识常量集合
     * 
     * 这些ID用于标识系统预置的特殊文件夹，每个文件夹都有特定的用途：
     * - 根文件夹：所有笔记的默认存储位置
     * - 临时文件夹：用于暂存不属于任何文件夹的笔记
     * - 通话记录文件夹：专门存储通话时创建的便签
     * - 回收站：存放已删除的笔记
     */
    
    /**
     * 根文件夹ID（默认值）
     * 
     * 这是系统的主文件夹，值为0。当用户创建新文件夹或笔记时，
     * 如果没有指定父文件夹，则默认属于根文件夹。
     * 根文件夹是所有用户创建内容的顶级容器。
     * 
     * @see #ID_TEMPARAY_FOLDER 临时文件夹
     * @see #ID_CALL_RECORD_FOLDER 通话记录文件夹
     * @see #ID_TRASH_FOLER 回收站
     */
    public static final int ID_ROOT_FOLDER = 0;
    
    /**
     * 临时文件夹ID
     * 
     * 值为-1，用于存放不属于任何文件夹的孤散笔记。
     * 当笔记从某个文件夹中移除但尚未归类到新文件夹时，
     * 会暂时存放在临时文件夹中。
     * 
     * 使用场景：
     * - 拖拽排序时临时存放
     * - 搜索结果展示
     * - 未分类的笔记归集
     */
    public static final int ID_TEMPARAY_FOLDER = -1;
    
    /**
     * 通话记录文件夹ID
     * 
     * 值为-2，专门用于存储通话时创建的便签。
     * 当用户在通话过程中快速记录内容时，系统会自动创建
     * 通话记录便签并归类到此文件夹。
     * 
     * 该文件夹帮助用户区分普通笔记和通话相关的备忘内容。
     */
    public static final int ID_CALL_RECORD_FOLDER = -2;
    
    /**
     * 回收站文件夹ID
     * 
     * 值为-3，用于存储已删除的便签。
     * 当用户删除笔记时，系统不会立即永久删除，
     * 而是将其移动到回收站中，提供恢复的可能性。
     * 
     * 回收站机制：
     * - 用户可以恢复误删的笔记
     * - 回收站内容在一定条件后可能被自动清理
     * - 清空回收站才会真正删除笔记
     */
    public static final int ID_TRASH_FOLER = -3;

    // ============================================================
    // Intent Extra 常量
    // ============================================================
    
    /**
     * Intent Extra 键名常量集合
     * 
     * 这些常量用于在Activity、Service、BroadcastReceiver之间
     * 传递便签相关的数据。通过Intent的putExtra()和getStringExtra()
     * 等方法进行数据交换。
     */
    
    /**
     * 提醒日期的Extra键名
     * 
     * 用于传递笔记的提醒日期时间。当用户在笔记中设置了提醒时，
     * 该值会通过Intent传递给闹钟服务或通知管理器。
     * 
     * 数据类型：String (格式为时间戳的字符串表示)
     * 使用场景：设置/修改笔记提醒、闹钟响应处理
     */
    public static final String INTENT_EXTRA_ALERT_DATE = "net.micode.notes.alert_date";
    
    /**
     * 背景颜色ID的Extra键名
     * 
     * 用于传递笔记的背景颜色标识。用户可以为不同的笔记
     * 设置不同的背景颜色以区分重要程度或类别。
     * 
     * 数据类型：int (颜色资源ID或颜色值)
     * 使用场景：笔记颜色设置、小部件颜色同步
     */
    public static final String INTENT_EXTRA_BACKGROUND_ID = "net.micode.notes.background_color_id";
    
    /**
     * 小部件ID的Extra键名
     * 
     * 用于标识桌面小部件的唯一ID。用户可以在桌面上添加
     * 多个便签小部件，每个小部件对应一个唯一的ID。
     * 
     * 数据类型：int
     * 使用场景：小部件配置、小部件更新、点击事件处理
     */
    public static final String INTENT_EXTRA_WIDGET_ID = "net.micode.notes.widget_id";
    
    /**
     * 小部件类型的Extra键名
     * 
     * 用于指定桌面小部件的尺寸类型。小米便签支持
     * 不同尺寸的小部件以适应不同的屏幕布局。
     * 
     * 数据类型：int
     * 取值范围：TYPE_WIDGET_2X, TYPE_WIDGET_4X 等
     * @see #TYPE_WIDGET_INVALIDE
     * @see #TYPE_WIDGET_2X
     * @see #TYPE_WIDGET_4X
     */
    public static final String INTENT_EXTRA_WIDGET_TYPE = "net.micode.notes.widget_type";
    
    /**
     * 文件夹ID的Extra键名
     * 
     * 用于传递目标文件夹的ID。当用户选择查看某个文件夹
     * 或将笔记移动到指定文件夹时使用此键。
     * 
     * 数据类型：long (文件夹的唯一ID)
     * 使用场景：文件夹导航、笔记移动操作
     */
    public static final String INTENT_EXTRA_FOLDER_ID = "net.micode.notes.folder_id";
    
    /**
     * 通话日期的Extra键名
     * 
     * 用于传递通话记录的时间戳。当用户从通话记录
     * 创建便签时，记录原始通话的时间信息。
     * 
     * 数据类型：String (通话发生的时间戳)
     * 使用场景：通话便签创建、通话记录关联
     */
    public static final String INTENT_EXTRA_CALL_DATE = "net.micode.notes.call_date";

    // ============================================================
    // 桌面小部件类型常量
    // ============================================================
    
    /**
     * 桌面小部件类型常量集合
     * 
     * 定义了便签桌面小部件的不同尺寸规格，
     * 用户可以根据桌面空间选择合适大小的小部件。
     */
    
    /**
     * 无效的小部件类型
     * 
     * 表示小部件类型无效或未指定。
     * 通常用于初始化或错误处理场景。
     */
    public static final int TYPE_WIDGET_INVALIDE      = -1;
    
    /**
     * 2x尺寸的小部件
     * 
     * 较小尺寸的小部件，占用桌面2格空间（横向2格）。
     * 适合桌面空间有限的用户，仅显示单条笔记内容。
     * 
     * 显示特点：
     * - 紧凑布局
     * - 笔记标题和少量内容
     * - 点击打开完整笔记
     */
    public static final int TYPE_WIDGET_2X            = 0;
    
    /**
     * 4x尺寸的小部件
     * 
     * 较大尺寸的小部件，占用桌面4格空间（横向4格）。
     * 可以显示更多笔记内容，适合需要快速预览的场景。
     * 
     * 显示特点：
     * - 宽敞布局
     * - 完整笔记内容预览
     * - 支持简短编辑
     */
    public static final int TYPE_WIDGET_4X            = 1;

    // ============================================================
    // 数据内容类型常量
    // ============================================================
    
    /**
     * 数据内容类型常量集合
     * 
     * 用于ContentProvider查询时标识返回数据的具体类型，
     * 帮助调用方正确解析返回的Cursor数据。
     */
    
    /**
     * 普通文本笔记的内容类型标识
     * 
     * 当查询返回的是文本笔记数据时，使用此类型标识。
     * 对应 TextNote.CONTENT_ITEM_TYPE。
     * 
     * @see TextNote
     */
    public static final String NOTE = TextNote.CONTENT_ITEM_TYPE;
    
    /**
     * 通话记录笔记的内容类型标识
     * 
     * 当查询返回的是通话记录数据时，使用此类型标识。
     * 对应 CallNote.CONTENT_ITEM_TYPE。
     * 
     * @see CallNote
     */
    public static final String CALL_NOTE = CallNote.CONTENT_ITEM_TYPE;

    // ============================================================
    // ContentProvider URI 常量
    // ============================================================
    
    /**
     * 笔记内容URI
     * 
     * 用于查询所有笔记和文件夹的ContentProvider URI。
     * 应用通过此URI访问便签数据库中的主数据表。
     * 
     * URI格式：content://micode_notes/note
     * 
     * 支持的操作：
     * - query: 查询笔记/文件夹列表
     * - insert: 创建新笔记/文件夹
     * - update: 更新笔记/文件夹信息
     * - delete: 删除笔记/文件夹
     * 
     * @see android.content.ContentResolver#query(Uri, String[], String, String[], String)
     */
    public static final Uri CONTENT_NOTE_URI = Uri.parse("content://" + AUTHORITY + "/note");

    /**
     * 数据内容URI
     * 
     * 用于查询笔记附件数据的ContentProvider URI。
     * 当笔记包含多媒体附件（如图片、录音等）时，
     * 通过此URI访问附件数据表。
     * 
     * URI格式：content://micode_notes/data
     * 
     * 注意：此URI用于访问笔记的详细数据内容，
     * 包括文本笔记的内容、通话记录的电话号码等。
     * 
     * @see android.content.ContentResolver#query(Uri, String[], String, String[], String)
     */
    public static final Uri CONTENT_DATA_URI = Uri.parse("content://" + AUTHORITY + "/data");

    // ============================================================
    // 笔记表列名接口
    // ============================================================
    
    /**
     * 笔记和文件夹表的列名定义接口
     * 
     * 此接口定义了便签数据库中 notes 表的所有列名常量。
     * notes 表存储所有笔记和文件夹的基本信息，包括：
     * - 笔记的文本内容和摘要
     * - 创建和修改时间
     * - 文件夹归属关系
     * - 提醒设置
     * - 小部件配置
     * - 同步状态等
     * 
     * 该接口被 ContentProvider 和数据访问层广泛使用，
     * 确保列名在整个应用中保持一致。
     * 
     * @see android.database.Cursor
     */
    public interface NoteColumns {
        /**
         * 行唯一ID
         * 
         * 数据库自动生成的主键，用于唯一标识每一条记录。
         * 无论是笔记还是文件夹，都有各自独立的ID系统。
         * 
         * 数据类型：INTEGER (long)
         * 特点：自增、唯一、非空
         */
        public static final String ID = "_id";

        /**
         * 父文件夹ID
         * 
         * 标识当前笔记或文件夹所属的父文件夹。
         * - 对于普通笔记：指向其所属的文件夹ID
         * - 对于文件夹：指向其父文件夹ID
         * - 根级别的项目：值为 ID_ROOT_FOLDER (0)
         * 
         * 数据类型：INTEGER (long)
         * 
         * @see #ID_ROOT_FOLDER
         */
        public static final String PARENT_ID = "parent_id";

        /**
         * 创建时间戳
         * 
         * 记录笔记或文件夹被创建时的时间。
         * 时间以毫秒为单位，从1970年1月1日 UTC开始计算。
         * 
         * 数据类型：INTEGER (long)
         * 使用场景：按时间排序、创建时间显示
         */
        public static final String CREATED_DATE = "created_date";

        /**
         * 最后修改时间戳
         * 
         * 记录笔记或文件夹最近一次被修改的时间。
         * 每次对笔记内容的更新都会同步更新此字段。
         * 
         * 数据类型：INTEGER (long)
         * 使用场景：
         * - 修改时间显示
         * - 按修改时间排序
         * - 同步冲突检测
         */
        public static final String MODIFIED_DATE = "modified_date";

        /**
         * 提醒日期
         * 
         * 设置的提醒时间点。当到达此时间时，系统会发送通知提醒用户。
         * 如果未设置提醒，此字段为0或null。
         * 
         * 数据类型：INTEGER (long)
         * 格式：毫秒时间戳
         * 
         * @see #INTENT_EXTRA_ALERT_DATE
         */
        public static final String ALERTED_DATE = "alert_date";

        /**
         * 摘要内容
         * 
         * 用于快速预览的简短文本：
         * - 对于文件夹：显示文件夹名称
         * - 对于笔记：显示笔记内容的前N个字符（通常70字左右）
         * 
         * 摘要用于列表视图中的显示，避免加载完整内容造成性能问题。
         * 
         * 数据类型：TEXT
         * 
         * @see android.widget.ListView
         */
        public static final String SNIPPET = "snippet";

        /**
         * 关联的小部件ID
         * 
         * 标识当前笔记关联的桌面小部件。
         * 当笔记被添加到桌面作为小部件时，此字段存储小部件的唯一ID。
         * - 无小部件关联：值为0或null
         * - 有小部件关联：值为小部件的配置ID
         * 
         * 数据类型：INTEGER (long)
         * 
         * @see #INTENT_EXTRA_WIDGET_ID
         */
        public static final String WIDGET_ID = "widget_id";

        /**
         * 小部件类型
         * 
         * 指定关联小部件的尺寸类型。
         * 用于控制小部件在桌面上的显示布局。
         * 
         * 数据类型：INTEGER (long)
         * 取值：
         * - TYPE_WIDGET_2X (0): 2格宽度
         * - TYPE_WIDGET_4X (1): 4格宽度
         * - TYPE_WIDGET_INVALIDE (-1): 无效类型
         * 
         * @see #TYPE_WIDGET_2X
         * @see #TYPE_WIDGET_4X
         * @see #TYPE_WIDGET_INVALIDE
         */
        public static final String WIDGET_TYPE = "widget_type";

        /**
         * 背景颜色ID
         * 
         * 笔记的背景颜色标识。用户可以为不同类型的笔记
         * 设置不同的背景颜色，如黄色表示工作、蓝色表示私人等。
         * 
         * 数据类型：INTEGER (long)
         * 取值范围：预定义的颜色资源ID数组中的索引
         * 
         * @see #INTENT_EXTRA_BACKGROUND_ID
         */
        public static final String BG_COLOR_ID = "bg_color_id";

        /**
         * 附件存在标志
         * 
         * 标识笔记是否包含多媒体附件。
         * - 0: 普通文本笔记，无附件
         * - 1: 多媒体笔记，至少包含一个附件
         * 
         * 此字段用于快速判断是否需要加载附件数据，
         * 避免不必要的数据库查询。
         * 
         * 数据类型：INTEGER
         * 
         * @see CallNote
         */
        public static final String HAS_ATTACHMENT = "has_attachment";

        /**
         * 文件夹内笔记数量
         * 
         * 统计当前文件夹下直接包含的笔记总数。
         * 注意：此数量不包含子文件夹中的笔记（递归统计）。
         * 
         * 数据类型：INTEGER (long)
         * 使用场景：
         * - 文件夹列表显示 "(N条笔记)"
         * - 判断文件夹是否为空
         */
        public static final String NOTES_COUNT = "notes_count";

        /**
         * 记录类型
         * 
         * 标识当前记录的类型（笔记、文件夹或系统文件夹）。
         * 用于在查询结果中区分不同类型的记录。
         * 
         * 数据类型：INTEGER
         * 取值：
         * - TYPE_NOTE (0): 普通便签
         * - TYPE_FOLDER (1): 用户文件夹
         * - TYPE_SYSTEM (2): 系统文件夹
         * 
         * @see #TYPE_NOTE
         * @see #TYPE_FOLDER
         * @see #TYPE_SYSTEM
         */
        public static final String TYPE = "type";

        /**
         * 同步ID
         * 
         * 与云端服务同步时使用的外部唯一标识。
         * 当笔记成功同步到云端后，会获得一个云端分配的sync_id。
         * 
         * 数据类型：INTEGER (long)
         * 用途：
         * - 增量同步识别
         * - 冲突检测
         * - 云端操作追溯
         */
        public static final String SYNC_ID = "sync_id";

        /**
         * 本地修改标志
         * 
         * 标识笔记是否在本地有未同步的修改。
         * - 0: 已同步，无本地修改
         * - 1: 本地有修改，未同步到云端
         * 
         * 此字段用于优化同步策略，
         * 只有标记为本地修改的笔记才需要上传到云端。
         * 
         * 数据类型：INTEGER
         */
        public static final String LOCAL_MODIFIED = "local_modified";

        /**
         * 原始父文件夹ID
         * 
         * 记录笔记在移动到临时文件夹前的原始父文件夹ID。
         * 用于支持"撤销移动"操作。
         * 
         * 使用场景：
         * 1. 用户将笔记从文件夹A移动到回收站
         * 2. 系统记录原始父文件夹ID到本字段
         * 3. 用户执行"撤销"，笔记可恢复到原文件夹
         * 
         * 数据类型：INTEGER
         * 
         * @see #ID_TEMPARAY_FOLDER
         * @see #ID_TRASH_FOLER
         */
        public static final String ORIGIN_PARENT_ID = "origin_parent_id";

        /**
         * Google Task ID
         * 
         * 关联的Google Tasks任务的ID。
         * 如果便签与Google Tasks同步，此字段存储对应的任务标识。
         * 
         * 数据类型：TEXT
         * 格式：Google Tasks API返回的任务资源ID
         */
        public static final String GTASK_ID = "gtask_id";

        /**
         * 版本号
         * 
         * 记录笔记的版本号，用于乐观锁和冲突解决。
         * 每次修改时版本号递增，当同步时检测版本号变化。
         * 
         * 数据类型：INTEGER (long)
         * 用途：
         * - 检测并发修改冲突
         * - 确定是否需要更新
         * - 审计追踪
         */
        public static final String VERSION = "version";
    }

    // ============================================================
    // 数据表列名接口
    // ============================================================
    
    /**
     * 数据内容表的列名定义接口
     * 
     * 此接口定义了便签数据库中 data 表的所有列名常量。
     * data 表存储笔记的具体内容数据，支持多种数据类型：
     * - 文本笔记内容
     * - 通话记录的电话号码和日期
     * - 通用扩展数据字段
     * 
     * 设计与 notes 表的关系：
     * - notes 表存储笔记的元数据（标题、时间、分类等）
     * - data 表存储笔记的实际内容（正文、附件信息等）
     * - 两者通过 note_id 字段关联（一对多关系）
     * 
     * @see NoteColumns
     */
    public interface DataColumns {
        /**
         * 行唯一ID
         * 
         * 数据库自动生成的主键，唯一标识每条数据记录。
         * 与 notes 表的 ID 不同，data 表有独立的ID序列。
         * 
         * 数据类型：INTEGER (long)
         */
        public static final String ID = "_id";

        /**
         * MIME类型
         * 
         * 标识这条数据记录的具体类型，
         * 用于ContentProvider正确解析不同类型的数据。
         * 
         * 数据类型：TEXT
         * 可能的取值：
         * - vnd.android.cursor.item/text_note: 文本笔记
         * - vnd.android.cursor.item/call_note: 通话记录
         * 
         * @see TextNote#CONTENT_ITEM_TYPE
         * @see CallNote#CONTENT_ITEM_TYPE
         */
        public static final String MIME_TYPE = "mime_type";

        /**
         * 关联的笔记ID
         * 
         * 指向所属笔记的外键，建立 data 与 notes 表的关联。
         * 一条笔记可以有多条数据记录（如正文+附件元数据）。
         * 
         * 数据类型：INTEGER (long)
         * 
         * @see NoteColumns#ID
         */
        public static final String NOTE_ID = "note_id";

        /**
         * 创建时间戳
         * 
         * 记录这条数据被创建时的时间。
         * 
         * 数据类型：INTEGER (long)
         * 
         * @see NoteColumns#CREATED_DATE
         */
        public static final String CREATED_DATE = "created_date";

        /**
         * 最后修改时间戳
         * 
         * 记录这条数据最近一次被修改的时间。
         * 
         * 数据类型：INTEGER (long)
         * 
         * @see NoteColumns#MODIFIED_DATE
         */
        public static final String MODIFIED_DATE = "modified_date";

        /**
         * 数据内容
         * 
         * 存储数据的主要文本内容。
         * - 对于文本笔记：存储笔记的完整正文
         * - 对于其他类型：可能为空或存储描述信息
         * 
         * 数据类型：TEXT
         */
        public static final String CONTENT = "content";

        /**
         * 通用数据字段1（整型）
         * 
         * 通用的整数类型扩展字段，具体含义取决于MIME_TYPE：
         * - 文本笔记：MODE（是否为清单模式）
         * - 通话记录：CALL_DATE（通话日期）
         * 
         * 数据类型：INTEGER
         * 
         * @see TextNote#MODE
         * @see CallNote#CALL_DATE
         */
        public static final String DATA1 = "data1";

        /**
         * 通用数据字段2（整型）
         * 
         * 备用的整数类型扩展字段。
         * 目前主要用于特定数据类型扩展预留。
         * 
         * 数据类型：INTEGER
         */
        public static final String DATA2 = "data2";

        /**
         * 通用数据字段3（文本型）
         * 
         * 通用的文本类型扩展字段，具体含义取决于MIME_TYPE：
         * - 通话记录：存储来电/去电的电话号码
         * 
         * 数据类型：TEXT
         * 
         * @see CallNote#PHONE_NUMBER
         */
        public static final String DATA3 = "data3";

        /**
         * 通用数据字段4（文本型）
         * 
         * 备用的文本类型扩展字段。
         * 可用于存储联系人姓名、通话时长等附加信息。
         * 
         * 数据类型：TEXT
         */
        public static final String DATA4 = "data4";

        /**
         * 通用数据字段5（文本型）
         * 
         * 备用的文本类型扩展字段。
         * 设计用于未来扩展或存储额外元数据。
         * 
         * 数据类型：TEXT
         */
        public static final String DATA5 = "data5";
    }

    // ============================================================
    // 文本笔记内部类
    // ============================================================
    
    /**
     * 文本笔记数据结构定义
     * 
     * 此类定义了文本笔记的完整数据结构，包括：
     * - 特定字段映射（使用DataColumns的通用字段）
     * - MIME类型定义
     * - ContentProvider URI
     * 
     * 文本笔记是小米便签最基础的笔记类型，
     * 支持普通模式和清单模式（复选框）两种编辑方式。
     * 
     * 数据存储方式：
     * - 笔记元数据存储在 notes 表
     * - 文本内容存储在 data 表的 CONTENT 字段
     * - 清单模式标识存储在 DATA1 (MODE) 字段
     * 
     * @implements DataColumns 继承通用数据列接口
     * @see DataColumns
     * @see NoteColumns
     */
    public static final class TextNote implements DataColumns {
        /**
         * 清单模式标识字段
         * 
         * 映射到 data 表的 DATA1 字段，
         * 用于标识文本笔记的编辑模式。
         * 
         * 数据类型：String (实际存储整数值)
         * 
         * @see #MODE_CHECK_LIST
         */
        public static final String MODE = DATA1;

        /**
         * 清单模式常量
         * 
         * 当 MODE 字段等于此值时，笔记处于清单模式。
         * 清单模式下，每行文本前会显示复选框，
         * 用户可以勾选完成的项目。
         * 
         * 模式对比：
         * - 0 或未设置: 普通文本模式
         * - 1: 清单/任务模式
         * 
         * @see #MODE
         */
        public static final int MODE_CHECK_LIST = 1;

        /**
         * 文本笔记的目录类型MIME类型
         * 
         * 用于Cursor返回多条文本笔记记录时的MIME类型。
         * 格式遵循Android标准：vnd.android.cursor.dir/类型名
         * 
         * 应用场景：
         * - ContentProvider query() 返回多行数据时
         * - Intent.setType() 设置数据类型过滤器
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/text_note";

        /**
         * 文本笔记的条目类型MIME类型
         * 
         * 用于Cursor返回单条文本笔记记录时的MIME类型。
         * 格式遵循Android标准：vnd.android.cursor.item/类型名
         * 
         * 应用场景：
         * - ContentProvider query() 返回单行数据时
         * - Intent匹配单个笔记时
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/text_note";

        /**
         * 文本笔记ContentProvider访问URI
         * 
         * 用于直接访问文本笔记数据的ContentProvider路径。
         * 提供比通用 URI 更精确的查询入口。
         * 
         * URI格式：content://micode_notes/text_note
         * 
         * 支持的操作：
         * - query: 查询文本笔记数据
         * - insert: 添加文本笔记
         * - update: 更新文本笔记
         * - delete: 删除文本笔记
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/text_note");
    }

    // ============================================================
    // 通话记录笔记内部类
    // ============================================================
    
    /**
     * 通话记录笔记数据结构定义
     * 
     * 此类定义了通话记录便签的完整数据结构。
     * 通话记录是一种特殊类型的便签，在通话过程中快速创建，
     * 包含通话的时间信息和电话号码。
     * 
     * 与普通文本笔记的区别：
     * - 自动关联通话信息（日期、电话号码）
     * - 归类到专用的通话记录文件夹
     * - MIME类型为通话记录专用类型
     * 
     * 数据存储方式：
     * - 笔记元数据存储在 notes 表
     * - 通话日期存储在 data 表的 DATA1 (CALL_DATE) 字段
     * - 电话号码存储在 data 表的 DATA3 (PHONE_NUMBER) 字段
     * 
     * @implements DataColumns 继承通用数据列接口
     * @see DataColumns
     * @see NoteColumns
     * @see #ID_CALL_RECORD_FOLDER
     */
    public static final class CallNote implements DataColumns {
        /**
         * 通话日期字段
         * 
         * 映射到 data 表的 DATA1 字段，
         * 存储原始通话发生的日期和时间。
         * 
         * 数据类型：String (毫秒时间戳)
         * 格式：yyyy-MM-dd HH:mm:ss (解析后)
         * 
         * 用途：
         * - 显示"通话于 XX:XX 创建"
         * - 按通话时间排序
         */
        public static final String CALL_DATE = DATA1;

        /**
         * 电话号码字段
         * 
         * 映射到 data 表的 DATA3 字段，
         * 存储关联的来电或去电号码。
         * 
         * 数据类型：TEXT
         * 格式：E.164国际电话号码格式或本地格式
         * 
         * 可能包含的信息：
         * - 来电号码
         * - 去电号码
         * - 未知号码（如屏蔽来电）
         */
        public static final String PHONE_NUMBER = DATA3;

        /**
         * 通话记录的目录类型MIME类型
         * 
         * 用于Cursor返回多条通话记录时指定数据类型。
         * 
         * 应用场景：
         * - ContentProvider query() 返回多条记录
         * - Intent过滤器匹配通话记录类型
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/call_note";

        /**
         * 通话记录的条目类型MIME类型
         * 
         * 用于Cursor返回单条通话记录时指定数据类型。
         * 
         * 应用场景：
         * - ContentProvider query() 返回单条记录
         * - Intent匹配单个通话记录
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/call_note";

        /**
         * 通话记录ContentProvider访问URI
         * 
         * 用于直接访问通话记录数据的ContentProvider路径。
         * 
         * URI格式：content://micode_notes/call_note
         * 
         * 支持的操作：
         * - query: 查询通话记录
         * - insert: 添加通话记录
         * - update: 更新通话记录
         * - delete: 删除通话记录
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/call_note");
    }
}
