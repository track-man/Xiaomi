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

/*
 * 处理便签项数据
 */
package net.micode.notes.ui;

import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;

import net.micode.notes.data.Contact;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.tool.DataUtils;


public class NoteItemData {
    // 投影
    static final String [] PROJECTION = new String [] {
        NoteColumns.ID,
        NoteColumns.ALERTED_DATE,
        NoteColumns.BG_COLOR_ID,
        NoteColumns.CREATED_DATE,
        NoteColumns.HAS_ATTACHMENT,
        NoteColumns.MODIFIED_DATE,
        NoteColumns.NOTES_COUNT,
        NoteColumns.PARENT_ID,
        NoteColumns.SNIPPET,
        NoteColumns.TYPE,
        NoteColumns.WIDGET_ID,
        NoteColumns.WIDGET_TYPE,
    };

    // 下标常量
    private static final int ID_COLUMN                    = 0;
    private static final int ALERTED_DATE_COLUMN          = 1;
    private static final int BG_COLOR_ID_COLUMN           = 2;
    private static final int CREATED_DATE_COLUMN          = 3;
    private static final int HAS_ATTACHMENT_COLUMN        = 4;
    private static final int MODIFIED_DATE_COLUMN         = 5;
    private static final int NOTES_COUNT_COLUMN           = 6;
    private static final int PARENT_ID_COLUMN             = 7;
    private static final int SNIPPET_COLUMN               = 8;
    private static final int TYPE_COLUMN                  = 9;
    private static final int WIDGET_ID_COLUMN             = 10;
    private static final int WIDGET_TYPE_COLUMN           = 11;

    // 笔记数据
    // 笔记id
    private long mId;
    // 提醒时间
    private long mAlertDate;
    // 背景颜色
    private int mBgColorId;
    // 创建日期
    private long mCreatedDate;
    // 是否带有附件
    private boolean mHasAttachment;
    // 修改日期
    private long mModifiedDate;
    // 如果是文件夹，则记录里面笔记的数量
    private int mNotesCount;
    // 父节点id，存储上级文件夹id
    private long mParentId;
    // 笔记摘要
    private String mSnippet;
    // 笔记类型
    private int mType;
    // 关联的组件id
    private int mWidgetId;
    // 关联的组件类型
    private int mWidgetType;

    // 通话笔记相关
    // 姓名
    private String mName;
    // 电话号码
    private String mPhoneNumber;

    // ui相关属性
    // 是否是列表末项
    private boolean mIsLastItem;
    // 是否是列表首项
    private boolean mIsFirstItem;
    // 是否是列表中唯一的项
    private boolean mIsOnlyOneItem;
    // 如果是文件夹，后面是否只有一条笔记
    private boolean mIsOneNoteFollowingFolder;
    // 如果是文件夹，后面是否有多条笔记
    private boolean mIsMultiNotesFollowingFolder;

    // 构造方法，通过游标查询到的信息进行初始化
    public NoteItemData(Context context, Cursor cursor) {
        mId = cursor.getLong(ID_COLUMN);
        mAlertDate = cursor.getLong(ALERTED_DATE_COLUMN);
        mBgColorId = cursor.getInt(BG_COLOR_ID_COLUMN);
        mCreatedDate = cursor.getLong(CREATED_DATE_COLUMN);
        mHasAttachment = (cursor.getInt(HAS_ATTACHMENT_COLUMN) > 0) ? true : false;
        mModifiedDate = cursor.getLong(MODIFIED_DATE_COLUMN);
        mNotesCount = cursor.getInt(NOTES_COUNT_COLUMN);
        mParentId = cursor.getLong(PARENT_ID_COLUMN);
        mSnippet = cursor.getString(SNIPPET_COLUMN);
        mSnippet = mSnippet.replace(NoteEditActivity.TAG_CHECKED, "").replace(
                NoteEditActivity.TAG_UNCHECKED, "");
        mType = cursor.getInt(TYPE_COLUMN);
        mWidgetId = cursor.getInt(WIDGET_ID_COLUMN);
        mWidgetType = cursor.getInt(WIDGET_TYPE_COLUMN);

        mPhoneNumber = "";
        if (mParentId == Notes.ID_CALL_RECORD_FOLDER) {
            mPhoneNumber = DataUtils.getCallNumberByNoteId(context.getContentResolver(), mId);
            if (!TextUtils.isEmpty(mPhoneNumber)) {
                mName = Contact.getContact(context, mPhoneNumber);
                if (mName == null) {
                    mName = mPhoneNumber;
                }
            }
        }

        if (mName == null) {
            mName = "";
        }
        checkPostion(cursor);
    }

    // ui渲染属性判断
    private void checkPostion(Cursor cursor) {
        mIsLastItem = cursor.isLast() ? true : false;
        mIsFirstItem = cursor.isFirst() ? true : false;
        mIsOnlyOneItem = (cursor.getCount() == 1);
        mIsMultiNotesFollowingFolder = false;
        mIsOneNoteFollowingFolder = false;

        // 如果不是列表首项，并且是普通笔记
        if (mType == Notes.TYPE_NOTE && !mIsFirstItem) {
            // 记录游标当前位置
            int position = cursor.getPosition();
            // 游标移动到前一项
            if (cursor.moveToPrevious()) {
                // 前一项是文件夹或系统文件夹
                if (cursor.getInt(TYPE_COLUMN) == Notes.TYPE_FOLDER
                        || cursor.getInt(TYPE_COLUMN) == Notes.TYPE_SYSTEM) {
                    // 文件夹后有多个笔记
                    if (cursor.getCount() > (position + 1)) {
                        mIsMultiNotesFollowingFolder = true;
                    // 文件夹后只有一个笔记
                    } else {
                        mIsOneNoteFollowingFolder = true;
                    }
                }
                if (!cursor.moveToNext()) {
                    throw new IllegalStateException("cursor move to previous but can't move back");
                }
            }
        }
    }

    // 一大堆getter方法
    public boolean isOneFollowingFolder() {
        return mIsOneNoteFollowingFolder;
    }

    public boolean isMultiFollowingFolder() {
        return mIsMultiNotesFollowingFolder;
    }

    public boolean isLast() {
        return mIsLastItem;
    }

    public String getCallName() {
        return mName;
    }

    public boolean isFirst() {
        return mIsFirstItem;
    }

    public boolean isSingle() {
        return mIsOnlyOneItem;
    }

    public long getId() {
        return mId;
    }

    public long getAlertDate() {
        return mAlertDate;
    }

    public long getCreatedDate() {
        return mCreatedDate;
    }

    public boolean hasAttachment() {
        return mHasAttachment;
    }

    public long getModifiedDate() {
        return mModifiedDate;
    }

    public int getBgColorId() {
        return mBgColorId;
    }

    public long getParentId() {
        return mParentId;
    }

    public int getNotesCount() {
        return mNotesCount;
    }

    public long getFolderId () {
        return mParentId;
    }

    public int getType() {
        return mType;
    }

    public int getWidgetType() {
        return mWidgetType;
    }

    public int getWidgetId() {
        return mWidgetId;
    }

    public String getSnippet() {
        return mSnippet;
    }

    // 有无提醒
    public boolean hasAlert() {
        return (mAlertDate > 0);
    }

    // 是否为通话笔记
    public boolean isCallRecord() {
        return (mParentId == Notes.ID_CALL_RECORD_FOLDER && !TextUtils.isEmpty(mPhoneNumber));
    }

    // 得到笔记类型
    public static int getNoteType(Cursor cursor) {
        return cursor.getInt(TYPE_COLUMN);
    }
}
