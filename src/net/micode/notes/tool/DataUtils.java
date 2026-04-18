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
 * 用于数据处理
 */
package net.micode.notes.tool;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.RemoteException;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.ui.NotesListAdapter.AppWidgetAttribute;

import java.util.ArrayList;
import java.util.HashSet;


public class DataUtils {
    public static final String TAG = "DataUtils";

    // 批量删除处理
    public static boolean batchDeleteNotes(ContentResolver resolver, HashSet<Long> ids) {
        if (ids == null) {
            Log.d(TAG, "the ids is null");
            return true;
        }
        if (ids.size() == 0) {
            Log.d(TAG, "no id is in the hashset");
            return true;
        }

        // 遍历id列表删除
        ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();
        for (long id : ids) {
            // 不能删除系统预设的便签文件夹
            if(id == Notes.ID_ROOT_FOLDER) {
                Log.e(TAG, "Don't delete system folder root");
                continue;
            }
            // 将所有删除操作加入操作列表
            ContentProviderOperation.Builder builder = ContentProviderOperation
                    .newDelete(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id));
            operationList.add(builder.build());
        }
        try {
            // 执行操作列表
            ContentProviderResult[] results = resolver.applyBatch(Notes.AUTHORITY, operationList);
            if (results == null || results.length == 0 || results[0] == null) {
                Log.d(TAG, "delete notes failed, ids:" + ids.toString());
                return false;
            }
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        } catch (OperationApplicationException e) {
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        }
        return false;
    }

    // 将笔记移动至文件夹，方法名疑似少写个d，应为moveNoteToFolder（
    public static void moveNoteToFoler(ContentResolver resolver, long id, long srcFolderId, long desFolderId) {
        // 放入键值对，包括parent_id,origin_Parent_id,local_modified
        ContentValues values = new ContentValues();
        values.put(NoteColumns.PARENT_ID, desFolderId);
        values.put(NoteColumns.ORIGIN_PARENT_ID, srcFolderId);
        values.put(NoteColumns.LOCAL_MODIFIED, 1);

        // 在数据库中更新数据
        /**
         *    UPDATE    notes
         *    SET       parent_id = [desFolderId],
         *              origin_parent_id = [srcFolderId],
         *              local_modified = 1
         *    WHERE     _id = [id]
         */
        resolver.update(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id), values, null, null);
    }

    // 批量移动至文件夹处理
    public static boolean batchMoveToFolder(ContentResolver resolver, HashSet<Long> ids,
            long folderId) {
        if (ids == null) {
            Log.d(TAG, "the ids is null");
            return true;
        }

        // 遍历id列表，构造操作列表
        ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();
        for (long id : ids) {
            // 拼接表地址和笔记id
            ContentProviderOperation.Builder builder = ContentProviderOperation
                    .newUpdate(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id));
            // 设置修改内容
            builder.withValue(NoteColumns.PARENT_ID, folderId);
            builder.withValue(NoteColumns.LOCAL_MODIFIED, 1);
            // 加入操作列表
            operationList.add(builder.build());
        }

        try {
            // 执行操作列表
            ContentProviderResult[] results = resolver.applyBatch(Notes.AUTHORITY, operationList);
            if (results == null || results.length == 0 || results[0] == null) {
                Log.d(TAG, "delete notes failed, ids:" + ids.toString());
                return false;
            }
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        } catch (OperationApplicationException e) {
            Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        }
        return false;
    }

    /**
     * Get the all folder count except system folders {@link Notes#TYPE_SYSTEM}}
     * 获取用户创建的文件夹数量
     */
    public static int getUserFolderCount(ContentResolver resolver) {
        // 查询用户创建的并且未移入回收站的文件夹数量
        /**
         *    SELECT    COUNT(*)
         *    FROM      notes
         *    WHERE     type = 1 AND parent_id <> -3
         *    OEDER BY  null
         */
        Cursor cursor =resolver.query(Notes.CONTENT_NOTE_URI,
                new String[] { "COUNT(*)" },
                NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + "<>?",
                new String[] { String.valueOf(Notes.TYPE_FOLDER), String.valueOf(Notes.ID_TRASH_FOLER)},
                null);

        // 获取查询的数据
        int count = 0;
        if(cursor != null) {
            if(cursor.moveToFirst()) {
                try {
                    count = cursor.getInt(0);
                } catch (IndexOutOfBoundsException e) {
                    Log.e(TAG, "get folder count failed:" + e.toString());
                } finally {
                    cursor.close();
                }
            }
        }
        return count;
    }

    // 查询指定类型中指定id笔记是否可见
    public static boolean visibleInNoteDatabase(ContentResolver resolver, long noteId, int type) {
        // 查询指定类型中指定id的可见笔记
        /**
         *    SELECT    *
         *    FROM      notes
         *    WHERE     _id = [noteId] AND type = [type] AND parent_id <> -3
         *    OEDER BY  null
         */
        Cursor cursor = resolver.query(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId),
                null,
                NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + "<>" + Notes.ID_TRASH_FOLER,
                new String [] {String.valueOf(type)},
                null);

        // 根据查询结果返回结果
        boolean exist = false;
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                exist = true;
            }
            // 仅查询到时关闭了cursor，可能导致cursor leak
            cursor.close();
        }
        // 应在此处关闭cursor
        // cursor.close();
        return exist;
    }

    // 查询指定id笔记是否存在于数据库中
    // 与上一个方法的区别：代码中使用的是逻辑删除，故数据库中会存在已删除的笔记，此方法会查询到被删除的笔记
    public static boolean existInNoteDatabase(ContentResolver resolver, long noteId) {
        // 查询指定id笔记
        /**
         *    SELECT    *
         *    FROM      notes
         *    WHERE     _id = [noteId]
         *    OEDER BY  null
         */
        Cursor cursor = resolver.query(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId),
                null, null, null, null);

        // 根据查询结果返回结果
        boolean exist = false;
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                exist = true;
            }
            // 仅查询到时关闭了cursor，可能导致cursor leak
            cursor.close();
        }
        // 应在此处关闭cursor
        // cursor.close();
        return exist;
    }

    // 查询指定id数据是否存在于数据库中
    public static boolean existInDataDatabase(ContentResolver resolver, long dataId) {
        // 查询指定id数据
        /**
         *    SELECT    *
         *    FROM      data
         *    WHERE     _id = [dataId]
         *    OEDER BY  null
         */
        Cursor cursor = resolver.query(ContentUris.withAppendedId(Notes.CONTENT_DATA_URI, dataId),
                null, null, null, null);

        // 根据查询的结果返回
        boolean exist = false;
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                exist = true;
            }
            // 仅查询到时关闭了cursor，可能导致cursor leak
            cursor.close();
        }
        // 应在此处关闭cursor
        // cursor.close();
        return exist;
    }

    // 查询指定类型中指定文件夹是否可见
    public static boolean checkVisibleFolderName(ContentResolver resolver, String name) {
        // 查询指定可见文件夹
        /**
         *    SELECT    *
         *    FROM      notes
         *    WHERE     type = 1 AND parent_id <> -3 AND snippet = [name]
         *    OEDER BY  null
         */
        Cursor cursor = resolver.query(Notes.CONTENT_NOTE_URI, null,
                NoteColumns.TYPE + "=" + Notes.TYPE_FOLDER +
                " AND " + NoteColumns.PARENT_ID + "<>" + Notes.ID_TRASH_FOLER +
                " AND " + NoteColumns.SNIPPET + "=?",
                new String[] { name }, null);
        // 根据查询结果返回
        boolean exist = false;
        if(cursor != null) {
            if(cursor.getCount() > 0) {
                exist = true;
            }
            // 仅查询到时关闭了cursor，可能导致cursor leak
            cursor.close();
        }
        // 应在此处关闭cursor
        // cursor.close();
        return exist;
    }

    // 查询指定路径下所有笔记的组件id，组件类型
    public static HashSet<AppWidgetAttribute> getFolderNoteWidget(ContentResolver resolver, long folderId) {

        /**
         *    SELECT    widget_id, widget_type
         *    FROM      notes
         *    WHERE     parent_id = [folderID]
         *    OEDER BY  null
         */
        Cursor c = resolver.query(Notes.CONTENT_NOTE_URI,
                new String[] { NoteColumns.WIDGET_ID, NoteColumns.WIDGET_TYPE },
                NoteColumns.PARENT_ID + "=?",
                new String[] { String.valueOf(folderId) },
                null);

        HashSet<AppWidgetAttribute> set = null;
        if (c != null) {
            if (c.moveToFirst()) {
                set = new HashSet<AppWidgetAttribute>();
                do {
                    // 遍历查询结果
                    try {
                        // 赋值，加入结果数组内
                        AppWidgetAttribute widget = new AppWidgetAttribute();
                        widget.widgetId = c.getInt(0);
                        widget.widgetType = c.getInt(1);
                        set.add(widget);
                    } catch (IndexOutOfBoundsException e) {
                        Log.e(TAG, e.toString());
                    }
                } while (c.moveToNext());
            }
            // 仅查询到时关闭了cursor，可能导致cursor leak
            c.close();
        }
        // 应在此处关闭cursor
        // cursor.close();
        return set;
    }

    // 查询指定通话笔记的电话号码
    public static String getCallNumberByNoteId(ContentResolver resolver, long noteId) {
        /**
         *    SELECT    data3
         *    FROM      data
         *    WHERE     note_id = [noteID] AND mime_type = 'call_note'
         *    OEDER BY  null
         */
        Cursor cursor = resolver.query(Notes.CONTENT_DATA_URI,
                new String [] { CallNote.PHONE_NUMBER },
                CallNote.NOTE_ID + "=? AND " + CallNote.MIME_TYPE + "=?",
                new String [] { String.valueOf(noteId), CallNote.CONTENT_ITEM_TYPE },
                null);

        // 根据查询到的内容返回
        if (cursor != null && cursor.moveToFirst()) {
            try {
                return cursor.getString(0);
            } catch (IndexOutOfBoundsException e) {
                Log.e(TAG, "Get call number fails " + e.toString());
            } finally {
                cursor.close();
            }
        }
        return "";
    }

    // 根据电话号码和日期查询通话笔记
    public static long getNoteIdByPhoneNumberAndCallDate(ContentResolver resolver, String phoneNumber, long callDate) {
        /**
         *    SELECT    note_id
         *    FROM      data
         *    WHERE     data1 = [callDate] AND mime_type = 'call_note' AND PHONE_NUMBERS_EQUAK( data3, [phoneNumber] )
         *    OEDER BY  null
         */
        Cursor cursor = resolver.query(Notes.CONTENT_DATA_URI,
                new String [] { CallNote.NOTE_ID },
                CallNote.CALL_DATE + "=? AND " + CallNote.MIME_TYPE + "=? AND PHONE_NUMBERS_EQUAL("
                + CallNote.PHONE_NUMBER + ",?)",
                new String [] { String.valueOf(callDate), CallNote.CONTENT_ITEM_TYPE, phoneNumber },
                null);

        // 根据查询返回笔记
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                try {
                    return cursor.getLong(0);
                } catch (IndexOutOfBoundsException e) {
                    Log.e(TAG, "Get call note id fails " + e.toString());
                }
            }
            cursor.close();
        }

        return 0;
    }

    // 查询指定id笔记的摘要，懒得写了
    public static String getSnippetById(ContentResolver resolver, long noteId) {
        Cursor cursor = resolver.query(Notes.CONTENT_NOTE_URI,
                new String [] { NoteColumns.SNIPPET },
                NoteColumns.ID + "=?",
                new String [] { String.valueOf(noteId)},
                null);

        if (cursor != null) {
            String snippet = "";
            if (cursor.moveToFirst()) {
                snippet = cursor.getString(0);
            }
            cursor.close();
            return snippet;
        }
        throw new IllegalArgumentException("Note is not found with id: " + noteId);
    }

    //格式化摘要内容
    public static String getFormattedSnippet(String snippet) {
        if (snippet != null) {
            snippet = snippet.trim();
            int index = snippet.indexOf('\n');
            if (index != -1) {
                snippet = snippet.substring(0, index);
            }
        }
        return snippet;
    }
}
