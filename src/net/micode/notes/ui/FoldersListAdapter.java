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
 * 用于链接数据库和文件夹
 */
package net.micode.notes.ui;

import android.content.Context;
import android.database.Cursor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;


public class FoldersListAdapter extends CursorAdapter {
    // 投影
    public static final String [] PROJECTION = {
        NoteColumns.ID,
        NoteColumns.SNIPPET
    };

    public static final int ID_COLUMN   = 0;
    public static final int NAME_COLUMN = 1;

    // 构造方法
    public FoldersListAdapter(Context context, Cursor c) {
        super(context, c);
        // TODO Auto-generated constructor stub
    }

    // 创建视图
    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return new FolderListItem(context);
    }

    // 向视图填充数据
    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        if (view instanceof FolderListItem) {
            // 当前目录如果是根目录，则为parent folder，不是则为自身名字
            String folderName = (cursor.getLong(ID_COLUMN) == Notes.ID_ROOT_FOLDER) ? context
                    .getString(R.string.menu_move_parent_folder) : cursor.getString(NAME_COLUMN);
            ((FolderListItem) view).bind(folderName);
        }
    }

    // 获取文件夹名字
    public String getFolderName(Context context, int position) {
        // 从数据库中查询
        Cursor cursor = (Cursor) getItem(position);
        return (cursor.getLong(ID_COLUMN) == Notes.ID_ROOT_FOLDER) ? context
                .getString(R.string.menu_move_parent_folder) : cursor.getString(NAME_COLUMN);
    }

    private class FolderListItem extends LinearLayout {
        private TextView mName;

        // 构造方法初始化控件
        public FolderListItem(Context context) {
            super(context);
            inflate(context, R.layout.folder_list_item, this);
            mName = (TextView) findViewById(R.id.tv_folder_name);
        }

        // 绑定控件名称
        public void bind(String name) {
            mName.setText(name);
        }
    }

}
