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
 * 便签列表单个条目
 */
package net.micode.notes.ui;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ResourceParser.NoteItemBgResources;


public class NotesListItem extends LinearLayout {
    // 相关控件
    // 状态指示图标
    private ImageView mAlert;
    // 标题
    private TextView mTitle;
    // 时间
    private TextView mTime;
    // 如果是通话笔记，则为联系人
    private TextView mCallName;
    // 绑定的数据模型对象
    private NoteItemData mItemData;
    // 多选框
    private CheckBox mCheckBox;

    // 构造方法，进行初始化
    public NotesListItem(Context context) {
        super(context);
        inflate(context, R.layout.note_item, this);
        mAlert = (ImageView) findViewById(R.id.iv_alert_icon);
        mTitle = (TextView) findViewById(R.id.tv_title);
        mTime = (TextView) findViewById(R.id.tv_time);
        mCallName = (TextView) findViewById(R.id.tv_name);
        mCheckBox = (CheckBox) findViewById(android.R.id.checkbox);
    }

    // 根据NoteItemData对象决定列表项
    public void bind(Context context, NoteItemData data, boolean choiceMode, boolean checked) {
        // 选择模式并且当前条目是笔记
        if (choiceMode && data.getType() == Notes.TYPE_NOTE) {
            // 复选框可见，设为选中
            mCheckBox.setVisibility(View.VISIBLE);
            mCheckBox.setChecked(checked);
        // 否则不显示复选框
        } else {
            mCheckBox.setVisibility(View.GONE);
        }

        mItemData = data;
        // 当前是通话记录文件夹
        if (data.getId() == Notes.ID_CALL_RECORD_FOLDER) {
            // 隐藏联系人，展示提醒
            mCallName.setVisibility(View.GONE);
            mAlert.setVisibility(View.VISIBLE);
            // 设置标题和图标
            mTitle.setTextAppearance(context, R.style.TextAppearancePrimaryItem);
            mTitle.setText(context.getString(R.string.call_record_folder_name)
                    + context.getString(R.string.format_folder_files_count, data.getNotesCount()));
            mAlert.setImageResource(R.drawable.call_record);
        // 当前是通话记录
        } else if (data.getParentId() == Notes.ID_CALL_RECORD_FOLDER) {
            // 联系人可见
            mCallName.setVisibility(View.VISIBLE);
            mCallName.setText(data.getCallName());
            // 设置标题
            mTitle.setTextAppearance(context,R.style.TextAppearanceSecondaryItem);
            mTitle.setText(DataUtils.getFormattedSnippet(data.getSnippet()));
            // 有提醒展示提醒文本和图标
            if (data.hasAlert()) {
                mAlert.setImageResource(R.drawable.clock);
                mAlert.setVisibility(View.VISIBLE);
            } else {
                mAlert.setVisibility(View.GONE);
            }
        // 当前是笔记文件夹或笔记
        } else {
            // 隐藏联系人，设置标题样式
            mCallName.setVisibility(View.GONE);
            mTitle.setTextAppearance(context, R.style.TextAppearancePrimaryItem);

            // 当前是文件夹
            if (data.getType() == Notes.TYPE_FOLDER) {
                // 设置标题文本，显示提醒
                mTitle.setText(data.getSnippet()
                        + context.getString(R.string.format_folder_files_count,
                                data.getNotesCount()));
                mAlert.setVisibility(View.GONE);
            // 当前是笔记
            } else {
                // 设置标题文本
                mTitle.setText(DataUtils.getFormattedSnippet(data.getSnippet()));
                // 有提醒展示提醒文本和图标
                if (data.hasAlert()) {
                    mAlert.setImageResource(R.drawable.clock);
                    mAlert.setVisibility(View.VISIBLE);
                } else {
                    mAlert.setVisibility(View.GONE);
                }
            }
        }
        // 设置时间
        mTime.setText(DateUtils.getRelativeTimeSpanString(data.getModifiedDate()));

        setBackground(data);
    }

    // 设置背景颜色
    private void setBackground(NoteItemData data) {
        // 获取背景颜色
        int id = data.getBgColorId();
        // 笔记
        if (data.getType() == Notes.TYPE_NOTE) {
            // 单独的一条 or 紧跟在文件夹后的一条
            if (data.isSingle() || data.isOneFollowingFolder()) {
                setBackgroundResource(NoteItemBgResources.getNoteBgSingleRes(id));
            // 最后一条
            } else if (data.isLast()) {
                setBackgroundResource(NoteItemBgResources.getNoteBgLastRes(id));
            // 第一条
            } else if (data.isFirst() || data.isMultiFollowingFolder()) {
                setBackgroundResource(NoteItemBgResources.getNoteBgFirstRes(id));
            // 普通笔记
            } else {
                setBackgroundResource(NoteItemBgResources.getNoteBgNormalRes(id));
            }
        // 文件夹
        } else {
            setBackgroundResource(NoteItemBgResources.getFolderBgRes());
        }
    }

    // getter方法
    public NoteItemData getItemData() {
        return mItemData;
    }
}
