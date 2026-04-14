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
 * 实现下拉菜单界面
 */
package net.micode.notes.ui;

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnMenuItemClickListener;

import net.micode.notes.R;

public class DropdownMenu {
    //菜单中的子控件
    private Button mButton;
    private PopupMenu mPopupMenu;
    private Menu mMenu;

    // 构造方法
    public DropdownMenu(Context context, Button button, int menuId) {
        mButton = button;
        // 设置按钮背景
        mButton.setBackgroundResource(R.drawable.dropdown_icon);
        // 初始化弹出菜单
        mPopupMenu = new PopupMenu(context, mButton);
        mMenu = mPopupMenu.getMenu();
        // 将子控件注入
        mPopupMenu.getMenuInflater().inflate(menuId, mMenu);
        //绑定点击事件
        mButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mPopupMenu.show();
            }
        });
    }

    // 绑定点击事件
    public void setOnDropdownMenuItemClickListener(OnMenuItemClickListener listener) {
        if (mPopupMenu != null) {
            mPopupMenu.setOnMenuItemClickListener(listener);
        }
    }

    // 返回菜单控件
    public MenuItem findItem(int id) {
        return mMenu.findItem(id);
    }

    // 设置标题
    public void setTitle(CharSequence title) {
        mButton.setText(title);
    }
}
