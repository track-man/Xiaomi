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


/* *
 * 用于数据备份、读取、显示
 */
package net.micode.notes.tool;

import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;


public class BackupUtils {
    private static final String TAG = "BackupUtils";
    // Singleton stuff
    //单例模式相关
    private static BackupUtils sInstance;
    //唯一实例

    //用于外部获得实例
    public static synchronized BackupUtils getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new BackupUtils(context);
        }
        return sInstance;
    }

    /**
     * Following states are signs to represents backup or restore
     * status
     * 以下一组状态常量是代表备份储存工作的标志
     * STATE_SD_CARD_UNMOUONTED      SD卡未挂载
     * STATE_BACKUP_FILE_NOT_EXIST   备份文件不存在
     * STATE_DATA_DESTROIED          数据格式错误
     * STATE_SYSTEM_ERROR            运行时异常
     * STATE_SUCCESS                 成功
     */
    // Currently, the sdcard is not mounted
    // SD卡在当前未挂载
    public static final int STATE_SD_CARD_UNMOUONTED           = 0;

    // The backup file not exist
    // 备份文件不存在
    public static final int STATE_BACKUP_FILE_NOT_EXIST        = 1;

    // The data is not well formated, may be changed by other programs
    // 数据没有完全格式化，可能是被其他程序修改
    public static final int STATE_DATA_DESTROIED               = 2;

    // Some run-time exception which causes restore or backup fails
    // 发生运行时异常（Runtime Exception）导致备份存储失败
    public static final int STATE_SYSTEM_ERROR                 = 3;

    // Backup or restore success
    // 备份存储成功
    public static final int STATE_SUCCESS                      = 4;


    // 私有的TextExport对象
    private TextExport mTextExport;

    // 单例模式下使用private限制外部创建对象
    private BackupUtils(Context context) {
        mTextExport = new TextExport(context);
    }

    // 检查外部存储是否可用
    private static boolean externalStorageAvailable() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    // 面向外部的输出文本
    public int exportToText() {
        return mTextExport.exportToText();
    }

    // 面向外部的输出文件名
    public String getExportedTextFileName() {
        return mTextExport.mFileName;
    }

    // 面向外部的输出文件路径
    public String getExportedTextFileDir() {
        return mTextExport.mFileDirectory;
    }

    // 内部类
    private static class TextExport {
        // 需要查询笔记行数据库的字段，包括ID，修改日期，摘要（文件夹名or文本行内容），类型（文件夹or笔记）
        private static final String[] NOTE_PROJECTION = {
                NoteColumns.ID,
                NoteColumns.MODIFIED_DATE,
                NoteColumns.SNIPPET,
                NoteColumns.TYPE
        };

        // 将查询格式字符串数组时的常量下标
        private static final int NOTE_COLUMN_ID = 0;

        private static final int NOTE_COLUMN_MODIFIED_DATE = 1;

        private static final int NOTE_COLUMN_SNIPPET = 2;

        // 需要查询数据库行的字段，包括数据内容，数据的格式，槽位1-4的数据
        private static final String[] DATA_PROJECTION = {
                DataColumns.CONTENT,
                DataColumns.MIME_TYPE,
                DataColumns.DATA1,
                DataColumns.DATA2,
                DataColumns.DATA3,
                DataColumns.DATA4,
        };

        // 将查询投影数组时的常量下标，方便理解
        private static final int DATA_COLUMN_CONTENT = 0;

        private static final int DATA_COLUMN_MIME_TYPE = 1;

        private static final int DATA_COLUMN_CALL_DATE = 2;

        private static final int DATA_COLUMN_PHONE_NUMBER = 4;

        // 数据格式数组
        private final String [] TEXT_FORMAT;
        private static final int FORMAT_FOLDER_NAME          = 0;
        private static final int FORMAT_NOTE_DATE            = 1;
        private static final int FORMAT_NOTE_CONTENT         = 2;

        // 内部存储的便签内容，文件名，文件路径
        private Context mContext;
        private String mFileName;
        private String mFileDirectory;

        // 通过外部输入数据，生成TextExport对象，初始化格式信息数据
        public TextExport(Context context) {
            TEXT_FORMAT = context.getResources().getStringArray(R.array.format_for_exported_note);
            mContext = context;
            mFileName = "";
            mFileDirectory = "";
        }

        // 根据数据的类型id从格式数组中获得对应的格式
        private String getFormat(int id) {
            return TEXT_FORMAT[id];
        }

        /**
         * Export the folder identified by folder id to text
         * 根据父id从数据库中查询符合的文件夹，输出修改时间，获得笔记id，交给下层方法循环输出笔记
         */
        private void exportFolderToText(String folderId, PrintStream ps) {

            // Query notes belong to this folder
            // 根据父id查询文件夹
            /**
             *    SELECT    _id, modified_date, snippet, type
             *    FROM      notes
             *    WHERE     parent_id = 'folderId'
             *    ORDER BY  null
             */
            Cursor notesCursor = mContext.getContentResolver().query(
                    Notes.CONTENT_NOTE_URI,
                    NOTE_PROJECTION,
                    NoteColumns.PARENT_ID + "=?",
                    new String[] { folderId },
                    null
            );

            if (notesCursor != null) {
                if (notesCursor.moveToFirst()) {
                    do {
                        // Print note's last modified date
                        // 输出格式化后的日期字符串
                        ps.println(String.format(
                            getFormat(FORMAT_NOTE_DATE),
                            DateFormat.format(
                                mContext.getString(R.string.format_datetime_mdhm),
                                notesCursor.getLong(NOTE_COLUMN_MODIFIED_DATE)
                            )
                        ));
                        // Query data belong to this note
                        // 获得笔记的id
                        String noteId = notesCursor.getString(NOTE_COLUMN_ID);
                        // 调用下层方法，根据笔记id循环输出
                        exportNoteToText(noteId, ps);
                    } while (notesCursor.moveToNext());
                }
                notesCursor.close();
            }
        }

        /**
         * Export note identified by id to a print stream
         * 通过笔记id查询笔记内容并输出内容
         */
        private void exportNoteToText(String noteId, PrintStream ps) {
            // 根据笔记id查询内容
            /**
             *    SELECT    content, mime_type, data1, data2, data3, data4
             *    FROM      content
             *    WHERE     note_id = 'noteId'
             *    ORDER BY  null
             */
            Cursor dataCursor = mContext.getContentResolver().query(
                Notes.CONTENT_DATA_URI,
                DATA_PROJECTION,
                DataColumns.NOTE_ID + "=?",
                new String[] { noteId },
                null
            );

            if (dataCursor != null) {
                if (dataCursor.moveToFirst()) {
                    do {
                        // 获得数据类型（普通笔记or通话笔记）
                        String mimeType = dataCursor.getString(DATA_COLUMN_MIME_TYPE);
                        // 处理通话笔记
                        if (DataConstants.CALL_NOTE.equals(mimeType)) {
                            // Print phone number

                            // 获取电话号码，拨打日期，归属地
                            String phoneNumber = dataCursor.getString(DATA_COLUMN_PHONE_NUMBER);
                            long callDate = dataCursor.getLong(DATA_COLUMN_CALL_DATE);
                            String location = dataCursor.getString(DATA_COLUMN_CONTENT);

                            // 输出格式化的电话号码
                            if (!TextUtils.isEmpty(phoneNumber)) {
                                ps.println(String.format(getFormat(FORMAT_NOTE_CONTENT),
                                        phoneNumber));
                            }
                            // Print call date
                            // 输出格式化的拨打日期
                            ps.println(String.format(getFormat(FORMAT_NOTE_CONTENT), DateFormat
                                    .format(mContext.getString(R.string.format_datetime_mdhm),
                                            callDate)));
                            // Print call attachment location
                            // 输出格式化的归属地
                            if (!TextUtils.isEmpty(location)) {
                                ps.println(String.format(getFormat(FORMAT_NOTE_CONTENT),
                                        location));
                            }
                        // 处理普通笔记
                        } else if (DataConstants.NOTE.equals(mimeType)) {
                            // 获得笔记格式并格式化输出
                            String content = dataCursor.getString(DATA_COLUMN_CONTENT);
                            if (!TextUtils.isEmpty(content)) {
                                ps.println(String.format(getFormat(FORMAT_NOTE_CONTENT),
                                        content));
                            }
                        }
                    } while (dataCursor.moveToNext());
                }
                dataCursor.close();
            }
            // print a line separator between note
            try {
                // 在笔记之间插入一个回车符一个换行符
                ps.write(new byte[] {
                        Character.LINE_SEPARATOR, Character.LETTER_NUMBER
                });
            } catch (IOException e) {
                // 异常处理，输出日志
                Log.e(TAG, e.toString());
            }
        }

        /**
         * Note will be exported as text which is user readable
         * 输出用户可读的笔记文本
         */
        public int exportToText() {
            // 检查外部存储是否可用
            if (!externalStorageAvailable()) {
                Log.d(TAG, "Media was not mounted");
                return STATE_SD_CARD_UNMOUONTED;
            }

            PrintStream ps = getExportToTextPrintStream();
            // 检查输出流是否可用
            if (ps == null) {
                Log.e(TAG, "get print stream error");
                return STATE_SYSTEM_ERROR;
            }
            // First export folder and its notes
            // 首先输出笔记文件夹和里面的笔记
            /**
             *    SELECT    _id, modified_date, snippet, type
             *    FROM      notes
             *    WHERE     ( type = 1 AND parent_id <> -3 ) OR _id = -2
             *    ORDER BY  null
             */
            Cursor folderCursor = mContext.getContentResolver().query(
                    Notes.CONTENT_NOTE_URI,
                    NOTE_PROJECTION,
                    "(" + NoteColumns.TYPE + "=" + Notes.TYPE_FOLDER + " AND "
                            + NoteColumns.PARENT_ID + "<>" + Notes.ID_TRASH_FOLER + ") OR "
                            + NoteColumns.ID + "=" + Notes.ID_CALL_RECORD_FOLDER, null, null);

            if (folderCursor != null) {
                if (folderCursor.moveToFirst()) {
                    do {
                        // Print folder's name
                        String folderName = "";
                        // 获得通话记录文件夹名
                        if(folderCursor.getLong(NOTE_COLUMN_ID) == Notes.ID_CALL_RECORD_FOLDER) {
                            folderName = mContext.getString(R.string.call_record_folder_name);
                        // 获得普通笔记文件夹名
                        } else {
                            folderName = folderCursor.getString(NOTE_COLUMN_SNIPPET);
                        }
                        // 打印有名字的文件夹
                        if (!TextUtils.isEmpty(folderName)) {
                            ps.println(String.format(getFormat(FORMAT_FOLDER_NAME), folderName));
                        }
                        // 获得文件夹id，交给下层方法处理
                        String folderId = folderCursor.getString(NOTE_COLUMN_ID);
                        exportFolderToText(folderId, ps);
                    } while (folderCursor.moveToNext());
                }
                folderCursor.close();
            }

            // 输出根文件夹下的笔记
            // Export notes in root's folder
            /**
             *    SELECT    _id, modified_date, snippet, type
             *    FROM      notes
             *    WHERE     type = 0 AND parent_id = 0
             *    ORDER BY  null
             */
            Cursor noteCursor = mContext.getContentResolver().query(
                    Notes.CONTENT_NOTE_URI,
                    NOTE_PROJECTION,
                    NoteColumns.TYPE + "=" + +Notes.TYPE_NOTE + " AND " + NoteColumns.PARENT_ID
                            + "=0", null, null);

            if (noteCursor != null) {
                if (noteCursor.moveToFirst()) {
                    do {
                        // 输出格式化的修改日期
                        ps.println(String.format(
                            getFormat(FORMAT_NOTE_DATE),
                            DateFormat.format(
                                mContext.getString(R.string.format_datetime_mdhm),
                                noteCursor.getLong(NOTE_COLUMN_MODIFIED_DATE)
                            )
                        ));
                        // Query data belong to this note
                        // 输出属于此笔记的数据
                        String noteId = noteCursor.getString(NOTE_COLUMN_ID);
                        exportNoteToText(noteId, ps);
                    } while (noteCursor.moveToNext());
                }
                noteCursor.close();
            }
            ps.close();

            return STATE_SUCCESS;
        }

        /**
         * Get a print stream pointed to the file {@generateExportedTextFile}
         * 获得指向文件的输出流
         */
        private PrintStream getExportToTextPrintStream() {
            // 调用下层方法创建文件
            File file = generateFileMountedOnSDcard(mContext, R.string.file_path,
                    R.string.file_name_txt_format);
            if (file == null) {
                Log.e(TAG, "create file to exported failed");
                return null;
            }
            mFileName = file.getName();
            mFileDirectory = mContext.getString(R.string.file_path);
            // 创建输出流
            PrintStream ps = null;
            try {
                FileOutputStream fos = new FileOutputStream(file);
                ps = new PrintStream(fos);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return null;
            } catch (NullPointerException e) {
                e.printStackTrace();
                return null;
            }
            return ps;
        }
    }

    /**
     * Generate the text file to store imported data
     * 生成用于储存输入数据的文件
     */
    private static File generateFileMountedOnSDcard(Context context, int filePathResId, int fileNameFormatResId) {
        // 构造目标路径
        StringBuilder sb = new StringBuilder();
        sb.append(Environment.getExternalStorageDirectory());
        sb.append(context.getString(filePathResId));
        File filedir = new File(sb.toString());
        // 根据当前时间生成文件名，并构造完整的路径
        sb.append(context.getString(
                fileNameFormatResId,
                DateFormat.format(context.getString(R.string.format_date_ymd),
                        System.currentTimeMillis())
        ));
        // 根据完整路径生成文件句柄
        File file = new File(sb.toString());
        // 新建文件
        try {
            if (!filedir.exists()) {
                filedir.mkdir();
            }
            if (!file.exists()) {
                file.createNewFile();
            }
            return file;
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }
}


